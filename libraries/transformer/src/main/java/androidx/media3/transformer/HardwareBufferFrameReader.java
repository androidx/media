/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law-or-agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.view.Surface;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.TimestampIterator;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.SyncFenceCompat;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An adaptor between a sequence player and a {@link Consumer} of {@link HardwareBufferFrame}
 * instances.
 */
/* package */ final class HardwareBufferFrameReader {

  /** A listener for events. */
  public interface Listener {
    /** Reports an error. */
    void onError(Exception cause);
  }

  /**
   * The maximum number of frames that can be in use by the downstream components.
   *
   * <p>Starting API 29, android_graphics_cts_MediaVulkanGpuTest verifies that 3 outstanding images
   * can be held by the consumer.
   *
   * <p>Prior to API 28, cts/ImageReaderDecoderTest verified that video decoders support only a
   * single outstanding image with the consumer. A single outstanding image makes double buffering
   * impossible.
   *
   * <p>Using 2 images seems to work on older API levels in our tests, and allows double buffering.
   */
  private static final int CAPACITY = 2;

  private static final int DEFAULT_FRAME_RATE = 30;
  private static final String TAG = "HBFrameReader";

  private final Composition composition;
  private final int sequenceIndex;
  private final Consumer<HardwareBufferFrame> frameConsumer;
  private final ImageReader imageReader;
  private final Listener listener;
  private final HandlerWrapper listenerHandler;
  private final PlaybackExecutor playbackExecutor;

  // TODO: b/478781219 - Ensure this class is only accessed from a single thread.
  /**
   * Information about frames that are waiting to be output.
   *
   * <p>At most one frame from {@link #queueFrameViaSurface} can be pending at a time.
   *
   * <p>Multiple frames from {@link #outputBitmap} can be pending.
   */
  @GuardedBy("this")
  private final Queue<FrameInfo> pendingFrameInfo;

  private @MonotonicNonNull Surface imageReaderSurface;

  @Nullable private Format lastFormat;
  private @MonotonicNonNull Format lastAdjustedFormat;

  // TODO: b/478781219 - Ensure this class is only accessed from a single thread.
  /** The number of frames that are currently in use by the downstream consumer. */
  @GuardedBy("this")
  private int framesInUse;

  /**
   * Creates an instance.
   *
   * @param composition The {@link Composition} for which this reader produces frames.
   * @param sequenceIndex The index of the {@link EditedMediaItemSequence} in the composition.
   * @param frameConsumer The downstream consumer of frames.
   * @param playbackLooper The looper associated with the playback thread.
   * @param defaultSurfacePixelFormat The default pixel format used by the {@linkplain #getSurface()
   *     surface}. Some producers override this format, but the behavior is device-specific.
   * @param listener The listener.
   * @param listenerHandler A {@link HandlerWrapper} to dispatch {@link Listener} callbacks.
   */
  HardwareBufferFrameReader(
      Composition composition,
      int sequenceIndex,
      Consumer<HardwareBufferFrame> frameConsumer,
      Looper playbackLooper,
      int defaultSurfacePixelFormat,
      Listener listener,
      HandlerWrapper listenerHandler) {
    this.composition = composition;
    this.sequenceIndex = sequenceIndex;
    this.frameConsumer = frameConsumer;
    this.listener = listener;
    this.listenerHandler = listenerHandler;
    Handler playbackHandler = new Handler(playbackLooper);
    playbackExecutor = new PlaybackExecutor(playbackHandler);
    // The default width and height are ignored when writing from MediaCodec.
    // Sensible values greater than 1 allow HardwareBufferFrameReaderTest to pass.
    if (SDK_INT >= 29) {
      imageReader =
          ImageReader.newInstance(
              /* width= */ 640,
              /* height= */ 360,
              defaultSurfacePixelFormat,
              /* maxImages= */ CAPACITY,
              // Setting the HardwareBuffer usage to GPU_SAMPLED_IMAGE allows the ImageReader to
              // run on emulators.
              /* usage= */ HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
    } else {
      imageReader =
          ImageReader.newInstance(
              /* width= */ 640,
              /* height= */ 360,
              defaultSurfacePixelFormat,
              /* maxImages= */ CAPACITY);
    }
    imageReader.setOnImageAvailableListener(playbackExecutor, playbackHandler);
    pendingFrameInfo = new ArrayDeque<>();
  }

  /** Returns a Surface which can be used to produce frames into. */
  Surface getSurface() {
    if (imageReaderSurface == null) {
      imageReaderSurface = imageReader.getSurface();
    }
    return imageReaderSurface;
  }

  /**
   * Returns whether the frame reader can accept another frame from the sequence player via the
   * {@linkplain #getSurface() input surface}.
   */
  boolean canAcceptFrameViaSurface() {
    // Prior to API 29 MediaCodec drops frames when outputting to a Surface. Do not allow multiple
    // frames to be pending over the ImageReader Surface simultaneously. See
    // https://developer.android.com/reference/android/media/MediaCodec#using-an-output-surface
    // Even after API 29, some devices still drop frames on the Surface. See
    // https://github.com/androidx/media/blob/7cc1056f840ce226598d3b990d4a6f7cd17e2831/libraries/common/src/main/java/androidx/media3/common/util/Util.java#L3651
    synchronized (this) {
      if (!pendingFrameInfo.isEmpty()) {
        return false;
      }
      return framesInUse < CAPACITY;
    }
  }

  /**
   * Signals that a frame will be sent via the {@linkplain #getSurface() input surface}.
   *
   * <p>This method must be called prior to {@linkplain android.media.MediaCodec#releaseOutputBuffer
   * releasing} the frame to the {@linkplain #getSurface() input surface}.
   *
   * @param presentationTimeUs The presentation time of the frame, in microseconds.
   * @param indexOfItem The position of the edited media item in the sequence.
   * @param format The {@link Format} of the edited media item.
   */
  void queueFrameViaSurface(long presentationTimeUs, int indexOfItem, Format format) {
    synchronized (this) {
      pendingFrameInfo.add(
          FrameInfo.createForSurface(presentationTimeUs, indexOfItem, getAdjustedFormat(format)));
    }
  }

  /**
   * Repeats a {@link Bitmap} to the downstream consumer, using timestamps from the {@code
   * timestampIterator}.
   *
   * @param bitmap The {@link Bitmap} to be repeated.
   * @param timestampIterator The {@link TimestampIterator} that provides the presentation
   *     timestamps of the frames to output.
   * @param indexOfItem The position of the edited media item in the sequence.
   */
  // TODO: b/319484746 - Move Bitmap repeating into the Renderer when ImageRenderer takes positionUs
  // into account.
  void outputBitmap(Bitmap bitmap, TimestampIterator timestampIterator, int indexOfItem) {
    Format format =
        new Format.Builder()
            .setSampleMimeType(MimeTypes.IMAGE_RAW)
            .setWidth(bitmap.getWidth())
            .setHeight(bitmap.getHeight())
            .setColorInfo(ColorInfo.SRGB_BT709_FULL)
            .setFrameRate(/* frameRate= */ DEFAULT_FRAME_RATE)
            .build();
    synchronized (this) {
      pendingFrameInfo.add(
          FrameInfo.createForBitmap(bitmap, timestampIterator, indexOfItem, format));
    }
    maybeOutputPendingBitmaps();
  }

  /**
   * Forwards a {@link HardwareBufferFrame#END_OF_STREAM_FRAME} to the downstream consumer after all
   * pending frames have been forwarded.
   */
  void queueEndOfStream() {
    synchronized (this) {
      pendingFrameInfo.add(FrameInfo.END_OF_STREAM);
      maybeOutputPendingBitmaps();
    }
  }

  /** Clears all pending frames. */
  void flush() {
    synchronized (this) {
      pendingFrameInfo.clear();
    }
  }

  /** Releases any resources. */
  void release() {
    if (imageReaderSurface != null) {
      imageReaderSurface.release();
    }
    imageReader.close();
  }

  /** Sets a default {@link ColorInfo} on the given {@link Format}. */
  private Format getAdjustedFormat(Format format) {
    if (format.equals(lastFormat)) {
      return checkNotNull(lastAdjustedFormat);
    }
    lastFormat = format;
    lastAdjustedFormat =
        format
            .buildUpon()
            .setColorInfo(
                format.colorInfo == null || !format.colorInfo.isDataSpaceValid()
                    ? ColorInfo.SDR_BT709_LIMITED
                    : format.colorInfo)
            .build();
    return lastAdjustedFormat;
  }

  private void onImageAvailable() {
    Image image = null;
    synchronized (this) {
      try {
        // Increment the framesInUse count before dequeueing pendingFrameInfo, to ensure only up to
        // CAPACITY images are queued.
        framesInUse++;
        image = imageReader.acquireNextImage();
        @Nullable FrameInfo frameInfo = pendingFrameInfo.poll();
        // If the HardwareBufferFrameReader is flushed after queueFrameViaSurface is called, but
        // before onImageAvailable, then when onImageAvailable is called there will be no matching
        // pendingFrameInfo. Ignore Images with no matching pendingFrameInfo to avoid crashing in
        // this scenario.
        if (frameInfo == null) {
          image.close();
          framesInUse--;
          return;
        }
        long presentationTimeUs = frameInfo.presentationTimeUs;
        int indexOfItem = frameInfo.itemIndex;
        checkState(
            image.getTimestamp() == presentationTimeUs * 1000,
            "%s != %s",
            image.getTimestamp(),
            presentationTimeUs * 1000);

        frameConsumer.accept(
            createHardwareBufferFrameFromImage(
                image, presentationTimeUs, indexOfItem, frameInfo.format));
        maybeOutputPendingBitmaps();
      } catch (RuntimeException e) {
        if (image != null) {
          image.close();
        }
        framesInUse--;
        throw e;
      }
    }
  }

  private void maybeOutputPendingBitmaps() {
    synchronized (this) {
      @Nullable FrameInfo frameInfo = pendingFrameInfo.peek();
      while (framesInUse < CAPACITY && frameInfo != null && frameInfo.timestampIterator != null) {
        if (!frameInfo.timestampIterator.hasNext()) {
          pendingFrameInfo.remove();
          frameInfo = pendingFrameInfo.peek();
          continue;
        }

        long presentationTimeUs = frameInfo.timestampIterator.next();
        framesInUse++;
        HardwareBufferFrame hardwareBufferFrame =
            createHardwareBufferFrameFromBitmap(
                checkNotNull(frameInfo.bitmap),
                presentationTimeUs,
                frameInfo.itemIndex,
                frameInfo.format);
        frameConsumer.accept(hardwareBufferFrame);
      }
    }
    maybeOutputEndOfStream();
  }

  private void maybeOutputEndOfStream() {
    synchronized (this) {
      @Nullable FrameInfo frameInfo = pendingFrameInfo.peek();
      while (frameInfo == FrameInfo.END_OF_STREAM) {
        frameConsumer.accept(HardwareBufferFrame.END_OF_STREAM_FRAME);
        pendingFrameInfo.remove();
        frameInfo = pendingFrameInfo.peek();
      }
    }
  }

  private HardwareBufferFrame createHardwareBufferFrameFromImage(
      Image image, long presentationTimeUs, int indexOfItem, Format format) {
    HardwareBufferFrame.Builder frameBuilder;
    // TODO: b/449956936 - Add support for HardwareBuffer on API 26 using Media NDK methods such as
    // AImage_getHardwareBuffer.
    if (SDK_INT >= 28) {
      HardwareBuffer hardwareBuffer = checkNotNull(image.getHardwareBuffer());
      checkState(!hardwareBuffer.isClosed());
      frameBuilder =
          new HardwareBufferFrame.Builder(
              hardwareBuffer,
              playbackExecutor,
              /* releaseCallback= */ (releaseFence) -> {
                // TODO: b/449956936 - Notify the video renderer's WakeupListener that new capacity
                // is freed up, and run another render loop.
                releaseFrame(image, hardwareBuffer, releaseFence);
              });
    } else {
      // TODO: b/449956936 - Support earlier API levels via HardwareBufferFrame.internalFrame.
      frameBuilder =
          new HardwareBufferFrame.Builder(
              /* hardwareBuffer= */ null,
              playbackExecutor,
              /* releaseCallback= */ (releaseFence) -> {
                releaseFrame(image, /* hardwareBuffer= */ null, releaseFence);
              });
    }
    // TODO: b/449956936 - Set the acquire fence from image on the frameBuilder.
    frameBuilder.setInternalFrame(image);
    return createHardwareBufferFrame(frameBuilder, presentationTimeUs, indexOfItem, format);
  }

  private HardwareBufferFrame createHardwareBufferFrameFromBitmap(
      Bitmap bitmap, long presentationTimeUs, int itemIndex, Format format) {
    HardwareBufferFrame.Builder frameBuilder;
    // TODO: b/449956936 - Copy the Bitmap into a HardwareBuffer using NDK on earlier API levels.
    if (SDK_INT >= 31 && checkNotNull(bitmap).getConfig() == Bitmap.Config.HARDWARE) {
      HardwareBuffer hardwareBuffer = checkNotNull(bitmap).getHardwareBuffer();
      checkState(!hardwareBuffer.isClosed());
      frameBuilder =
          new HardwareBufferFrame.Builder(
              hardwareBuffer,
              playbackExecutor,
              /* releaseCallback= */ (releaseFence) -> {
                // Do not manually release the hardware buffer backing the bitmap, it will be reused
                // when the bitmap is repeated, and cleaned up when the bitmap is garbage collected.
                releaseFrame(/* image= */ null, /* hardwareBuffer= */ null, releaseFence);
              });
    } else {
      frameBuilder =
          new HardwareBufferFrame.Builder(
              /* hardwareBuffer= */ null,
              playbackExecutor,
              /* releaseCallback= */ (releaseFence) -> {
                releaseFrame(/* image= */ null, /* hardwareBuffer= */ null, releaseFence);
              });
    }
    frameBuilder.setInternalFrame(bitmap);
    return createHardwareBufferFrame(frameBuilder, presentationTimeUs, itemIndex, format);
  }

  private HardwareBufferFrame createHardwareBufferFrame(
      HardwareBufferFrame.Builder frameBuilder,
      long presentationTimeUs,
      int itemIndex,
      Format format) {
    // COLOR_TRANSFER_SRGB may not be supported by the encoder or display, but is equivalent to
    // COLOR_TRANSFER_SDR on Android which is widely supported.
    if (format.colorInfo != null && format.colorInfo.colorTransfer == C.COLOR_TRANSFER_SRGB) {
      ColorInfo adjustedColorInfo =
          format.colorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_SDR).build();
      format = format.buildUpon().setColorInfo(adjustedColorInfo).build();
    }
    return frameBuilder
        .setPresentationTimeUs(presentationTimeUs)
        .setMetadata(new CompositionFrameMetadata(composition, sequenceIndex, itemIndex))
        .setFormat(format)
        .build();
  }

  private void releaseFrame(
      @Nullable Image image,
      @Nullable HardwareBuffer hardwareBuffer,
      @Nullable SyncFenceCompat releaseFence) {
    synchronized (this) {
      framesInUse--;
      if (releaseFence != null) {
        try {
          // TODO: b/475744934 - Use the NDK to set the fence on the Image.
          boolean signaled = releaseFence.await(/* timeoutMs= */ 500);
          if (!signaled) {
            Log.w(TAG, "releaseFence timed out.");
          }
          releaseFence.close();
        } catch (ErrnoException | IOException e) {
          listener.onError(e);
        }
      }
      if (SDK_INT >= 26 && hardwareBuffer != null) {
        hardwareBuffer.close();
      }
      if (image != null) {
        image.close();
      }
    }
    maybeOutputPendingBitmaps();
  }

  /**
   * A {@link Executor} which executes commands on a {@link Handler} and propagates {@linkplain
   * RuntimeException errors} to the {@link Listener}.
   */
  private class PlaybackExecutor implements ImageReader.OnImageAvailableListener, Executor {

    final Handler playbackHandler;

    PlaybackExecutor(Handler playbackHandler) {
      this.playbackHandler = playbackHandler;
    }

    @Override
    public void execute(Runnable command) {
      playbackHandler.post(
          () -> {
            try {
              command.run();
            } catch (RuntimeException e) {
              listenerHandler.post(() -> listener.onError(e));
            }
          });
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
      try {
        HardwareBufferFrameReader.this.onImageAvailable();
      } catch (RuntimeException e) {
        listenerHandler.post(() -> listener.onError(e));
      }
    }
  }

  private static final class FrameInfo {

    /** A {@link FrameInfo} marking that the sequence has ended. */
    private static final FrameInfo END_OF_STREAM =
        new FrameInfo(
            null,
            null,
            /* presentationTimeUs= */ C.TIME_END_OF_SOURCE,
            /* itemIndex= */ C.INDEX_UNSET,
            /* format= */ new Format.Builder().build());

    /**
     * The pending frame {@link Bitmap}, or {@code null} if the frame will be output via {@link
     * #getSurface()}.
     */
    @Nullable final Bitmap bitmap;

    /**
     * The pending frame timestamps, or {@code null} if the frame will be output via {@link
     * #getSurface()}.
     */
    @Nullable final TimestampIterator timestampIterator;

    final Format format;
    final long presentationTimeUs;
    final int itemIndex;

    static FrameInfo createForBitmap(
        Bitmap bitmap, TimestampIterator timestampIterator, int itemIndex, Format format) {
      return new FrameInfo(
          bitmap, timestampIterator, /* presentationTimeUs= */ C.TIME_UNSET, itemIndex, format);
    }

    static FrameInfo createForSurface(long presentationTimeUs, int itemIndex, Format format) {
      return new FrameInfo(
          /* bitmap= */ null, /* timestampIterator= */ null, presentationTimeUs, itemIndex, format);
    }

    private FrameInfo(
        @Nullable Bitmap bitmap,
        @Nullable TimestampIterator timestampIterator,
        long presentationTimeUs,
        int itemIndex,
        Format format) {
      this.bitmap = bitmap;
      this.timestampIterator = timestampIterator;
      this.presentationTimeUs = presentationTimeUs;
      this.itemIndex = itemIndex;
      this.format = format;
    }
  }
}
