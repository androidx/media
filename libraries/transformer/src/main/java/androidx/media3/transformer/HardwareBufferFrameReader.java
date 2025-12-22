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

import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import androidx.media3.common.util.Consumer;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.effect.HardwareBufferFrame;
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

  private final Composition composition;
  private final int sequenceIndex;
  private final Consumer<HardwareBufferFrame> frameConsumer;
  private final ImageReader imageReader;
  private final Listener listener;
  private final HandlerWrapper listenerHandler;
  private final PlaybackExecutor playbackExecutor;
  private final Queue<FrameInfo> pendingFrameInfo;

  private @MonotonicNonNull Surface imageReaderSurface;

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
    imageReader =
        ImageReader.newInstance(
            /* width= */ 640,
            /* height= */ 360,
            defaultSurfacePixelFormat,
            /* maxImages= */ CAPACITY);
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

  /** Returns whether the frame reader can accept another frame from the sequence player. */
  boolean canAcceptFrame() {
    // Prior to API 29 MediaCodec drops frames when outputting to a Surface. Do not allow multiple
    // frames to be pending over the ImageReader Surface simultaneously. See
    // https://developer.android.com/reference/android/media/MediaCodec#using-an-output-surface
    // Even after API 29, some devices still drop frames on the Surface. See
    // https://github.com/androidx/media/blob/7cc1056f840ce226598d3b990d4a6f7cd17e2831/libraries/common/src/main/java/androidx/media3/common/util/Util.java#L3651
    if (!pendingFrameInfo.isEmpty()) {
      return false;
    }
    return framesInUse < CAPACITY;
  }

  /**
   * Reads the next frame from the {@linkplain #getSurface() input surface} and forwards it to the
   * {@link Consumer}.
   *
   * @param presentationTimeUs The presentation time of the frame, in microseconds.
   * @param indexOfItem The position of the edited media item in the sequence.
   */
  void willOutputFrameViaSurface(long presentationTimeUs, int indexOfItem) {
    framesInUse += 1;
    pendingFrameInfo.add(new FrameInfo(presentationTimeUs, indexOfItem));
  }

  /** Releases any resources. */
  void release() {
    if (imageReaderSurface != null) {
      imageReaderSurface.release();
    }
    imageReader.close();
  }

  private void onImageAvailable() {
    FrameInfo frameInfo = checkNotNull(pendingFrameInfo.poll());
    long presentationTimeUs = frameInfo.presentationTimeUs;
    int indexOfItem = frameInfo.itemIndex;
    Image image = imageReader.acquireNextImage();
    checkState(
        image.getTimestamp() == presentationTimeUs * 1000,
        "%s != %s",
        image.getTimestamp(),
        presentationTimeUs * 1000);

    HardwareBufferFrame.Builder frameBuilder;
    // TODO: b/449956936 - Add support for HardwareBuffer on API 26 using Media NDK methods such as
    // AImage_getHardwareBuffer.
    if (SDK_INT >= 28) {
      HardwareBuffer hardwareBuffer = checkNotNull(image.getHardwareBuffer());
      frameBuilder =
          new HardwareBufferFrame.Builder(
              hardwareBuffer,
              playbackExecutor,
              () -> {
                // TODO: b/449956936 - Notify the video renderer's WakeupListener that new capacity
                // is freed up, and run another render loop.
                hardwareBuffer.close();
                image.close();
                framesInUse -= 1;
              });
    } else {
      // TODO: b/449956936 - Support earlier API levels via HardwareBufferFrame.internalFrame.
      frameBuilder =
          new HardwareBufferFrame.Builder(
              /* hardwareBuffer= */ null,
              playbackExecutor,
              () -> {
                image.close();
                framesInUse -= 1;
              });
    }
    // TODO: b/449956936 - Set the acquire fence from image on the frameBuilder.
    HardwareBufferFrame hardwareBufferFrame =
        frameBuilder
            .setInternalFrame(image)
            .setPresentationTimeUs(presentationTimeUs)
            .setMetadata(new CompositionFrameMetadata(composition, sequenceIndex, indexOfItem))
            .build();
    frameConsumer.accept(hardwareBufferFrame);
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
    final long presentationTimeUs;
    final int itemIndex;

    FrameInfo(long presentationTimeUs, int itemIndex) {
      this.presentationTimeUs = presentationTimeUs;
      this.itemIndex = itemIndex;
    }
  }
}
