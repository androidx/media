/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common.video;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.hardware.DataSpace;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.HardwareBufferPool.HardwareBufferWithFence;
import java.io.IOException;
import java.util.concurrent.Executor;

/** A {@link FrameWriter} that outputs frames to a {@link SurfaceHolder}. */
@RequiresApi(28)
@ExperimentalApi // TODO: b/498176910 Remove once FrameWriter is production ready.
public final class SurfaceHolderFrameWriter implements FrameWriter, SurfaceHolder.Callback {

  /** A listener for {@link SurfaceHolderFrameWriter} events. */
  public interface Listener {
    /**
     * Called when a video frame is about to be rendered.
     *
     * @param presentationTimeUs The presentation time of the frame, in microseconds.
     * @param releaseTimeNs The system time at which the frame should be displayed, in nanoseconds.
     *     Can be compared to {@link System#nanoTime()}. It will be {@link C#TIME_UNSET}, if the
     *     frame is rendered immediately.
     * @param format The format associated with the frame.
     */
    void onFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs, Format format);

    /**
     * Called when an error occurs.
     *
     * @param videoFrameProcessingException The error.
     */
    void onError(VideoFrameProcessingException videoFrameProcessingException);

    /** Called when video output ends. */
    void onEnded();
  }

  private static final int CAPACITY = 2;

  private final Object lock;

  private final Executor surfaceHolderExecutor;

  @GuardedBy("lock")
  @Nullable
  private SurfaceHolder surfaceHolder;

  @GuardedBy("lock")
  @Nullable
  private Format currentFormat;

  @GuardedBy("lock")
  private long currentUsageFlags;

  @GuardedBy("lock")
  @Nullable
  private ImageWriter imageWriter;

  @GuardedBy("lock")
  @Nullable
  private Surface surface;

  @GuardedBy("lock")
  private boolean isSurfaceChangeRequested;

  @GuardedBy("lock")
  @Nullable
  private Runnable wakeupListener;

  private final Listener listener;

  private final Executor listenerExecutor;

  private final HardwareBufferPool hardwareBufferPool;
  @Nullable private final HardwareBufferNativeHelpers hardwareBufferNativeHelpers;

  /**
   * Creates a new instance that returns video frames that are directly dequeued from an {@link
   * ImageWriter} linked to the output {@link SurfaceHolder#getSurface()}.
   *
   * @param surfaceHolder The {@link SurfaceHolder} to which frames will be written, or {@code null}
   *     if not yet available.
   * @param surfaceHolderExecutor The {@link Executor} on which the surface holder methods will be
   *     called.
   * @param listener The {@link Listener}.
   * @param listenerExecutor The {@link Executor} on which the listener methods will be called.
   */
  @RequiresApi(33)
  public static SurfaceHolderFrameWriter create(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor) {
    return new SurfaceHolderFrameWriter(
        surfaceHolder,
        surfaceHolderExecutor,
        listener,
        listenerExecutor,
        /* hardwareBufferNativeHelpers= */ null);
  }

  /**
   * Creates a new instance, that will allocate intermediate {@link HardwareBufferFrame}s that are
   * filled by the caller, and are then copied to {@link HardwareBuffer}s dequeued from an {@link
   * ImageWriter} linked to the output {@link SurfaceHolder#getSurface()}.
   *
   * @param surfaceHolder The {@link SurfaceHolder} to which frames will be written, or {@code null}
   *     if not yet available.
   * @param surfaceHolderExecutor The {@link Executor} on which the surface holder methods will be
   *     called.
   * @param listener The {@link Listener}.
   * @param listenerExecutor The {@link Executor} on which the listener methods will be called.
   * @param hardwareBufferNativeHelpers The {@link HardwareBufferNativeHelpers} used to copy
   *     intermediate {@link HardwareBuffer}s to {@link HardwareBuffer}s dequeued from {@link
   *     ImageWriter}.
   */
  public static SurfaceHolderFrameWriter create(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor,
      HardwareBufferNativeHelpers hardwareBufferNativeHelpers) {
    return new SurfaceHolderFrameWriter(
        surfaceHolder,
        surfaceHolderExecutor,
        listener,
        listenerExecutor,
        hardwareBufferNativeHelpers);
  }

  private SurfaceHolderFrameWriter(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor,
      @Nullable HardwareBufferNativeHelpers hardwareBufferNativeHelpers) {
    if (SDK_INT < 33) {
      checkState(hardwareBufferNativeHelpers != null);
    }
    lock = new Object();
    this.surfaceHolder = surfaceHolder;
    this.surfaceHolderExecutor = surfaceHolderExecutor;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
    this.hardwareBufferNativeHelpers = hardwareBufferNativeHelpers;
    this.hardwareBufferPool = new HardwareBufferPool(CAPACITY);
    if (surfaceHolder != null) {
      surfaceHolder.addCallback(this);
    }
  }

  /**
   * Sets the {@link SurfaceHolder} to which frames will be written.
   *
   * @param surfaceHolder The {@link SurfaceHolder}, or {@code null} to clear the output.
   */
  public void setSurfaceHolder(@Nullable SurfaceHolder surfaceHolder) {
    synchronized (lock) {
      if (this.surfaceHolder != null) {
        this.surfaceHolder.removeCallback(this);
      }
      this.surfaceHolder = surfaceHolder;
      if (imageWriter != null) {
        imageWriter.close();
        imageWriter = null;
      }
      if (surfaceHolder != null) {
        surfaceHolder.addCallback(this);
        // Re-trigger surface format change for the new surface.
        Format currentFormat = this.currentFormat;
        if (currentFormat != null) {
          isSurfaceChangeRequested = true;
          surfaceHolderExecutor.execute(
              () -> surfaceHolder.setFixedSize(/* width= */ 1, /* height= */ 1));
        }
      }
    }
  }

  @Override
  public Info getInfo() {
    return (format, usageFlags) ->
        format.colorInfo != null && format.width > 0 && format.height > 0;
  }

  @Override
  public void configure(Format format, @Frame.Usage long usage) {
    checkArgument(format.colorInfo != null);
    checkArgument(format.width > 0);
    checkArgument(format.height > 0);
    synchronized (lock) {
      if (format.equals(currentFormat) && usage == currentUsageFlags) {
        return;
      }
      currentFormat = format;
      currentUsageFlags = usage;
      if (imageWriter != null) {
        imageWriter.close();
        imageWriter = null;
      }
      if (surfaceHolder == null) {
        return;
      }
      isSurfaceChangeRequested = true;
      // Set the size to an arbitrary value in order to trigger a surfaceChanged() callback,
      // in case the previously set SurfaceHolder size matches the requested format.
      // There is no getter for size or format.
      SurfaceHolder surfaceHolder = checkNotNull(this.surfaceHolder);
      surfaceHolderExecutor.execute(
          () -> surfaceHolder.setFixedSize(/* width= */ 1, /* height= */ 1));
    }
  }

  @Override
  @Nullable
  // TODO: b/507446982 - Handle ImageWriter blocking.
  public AsyncFrame dequeueInputFrame(Executor wakeupExecutor, Runnable wakeupListener) {
    synchronized (lock) {
      Format currentFormat = this.currentFormat;
      long currentUsageFlags = this.currentUsageFlags;
      checkState(currentFormat != null, "configure() must be called before dequeueInputFrame()");

      if (imageWriter != null && !isSurfaceChangeRequested) {
        try {
          // On API 33+ the usage flags can be directly set on ImageWriter, so the dequeued image
          // can be directly written to by the caller.
          // On API < 33, if no specific usage flags are requested, or CPU usage only is requested,
          // we can also use the dequeued buffer directly.
          if (SDK_INT >= 33
              || currentUsageFlags == 0
              || currentUsageFlags == HardwareBuffer.USAGE_CPU_WRITE_OFTEN) {
            InternalImage internalImage = dequeueInternalImage(/* needsCopy= */ false);
            HardwareBuffer hardwareBuffer = checkNotNull(internalImage.image.getHardwareBuffer());
            DefaultHardwareBufferFrame frame =
                new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
                    .setInternalImage(internalImage)
                    .setFormat(currentFormat)
                    .build();
            return new AsyncFrame(frame, null);
          }

          checkState(hardwareBufferNativeHelpers != null);
          // On API <33, create an intermediate frame with usage equal to the requested usage, that
          // can also be read by the CPU. This will be filled by the caller, then copied into the
          // buffer dequeued from ImageWriter via the JNI.
          @Nullable
          HardwareBufferWithFence bufferWithFence =
              hardwareBufferPool.get(
                  currentFormat,
                  currentUsageFlags | Frame.USAGE_CPU_READ_OFTEN,
                  () -> wakeupExecutor.execute(wakeupListener));
          // The pool is at capacity, wakeupListener will be invoked when there is an available
          // buffer.
          if (bufferWithFence == null) {
            return null;
          }
          InternalImage internalImage = dequeueInternalImage(/* needsCopy= */ true);
          DefaultHardwareBufferFrame frame =
              new DefaultHardwareBufferFrame.Builder(bufferWithFence.hardwareBuffer)
                  .setInternalImage(internalImage)
                  .setFormat(currentFormat)
                  .build();
          return new AsyncFrame(frame, bufferWithFence.acquireFence);
        } catch (IllegalStateException e) {
          listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
          return null;
        }
      }
      // Waiting for an earlier surface format change or surface holder is not set. Do nothing.
      this.wakeupListener = () -> wakeupExecutor.execute(wakeupListener);
      return null;
    }
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    checkArgument(frame instanceof DefaultHardwareBufferFrame);
    DefaultHardwareBufferFrame hardwareBufferFrame = (DefaultHardwareBufferFrame) frame;
    InternalImage internalImage =
        (InternalImage) checkNotNull(hardwareBufferFrame.getInternalImage());
    Image image = internalImage.image;
    ImageWriter imageWriterFromFrame = internalImage.imageWriter;
    long releaseTimeNs;

    synchronized (lock) {
      if (writeCompleteFence != null && SDK_INT >= 33) {
        try {
          image.setFence(writeCompleteFence.asSyncFence());
        } catch (IOException e) {
          listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
          writeCompleteFence.close();
          releaseFrameResources(hardwareBufferFrame);
          return;
        }
      }

      if (this.imageWriter != imageWriterFromFrame) {
        // The ImageWriter has changed since this frame was dequeued. The previous writer was
        // closed and this image can no longer be used.
        releaseFrameResources(hardwareBufferFrame);
        return;
      }

      // Copy the input frame's content to the ImageWriter's HardwareBuffer if an intermediate
      // pooled buffer was used.
      if (internalImage.needsCopy) {
        checkState(hardwareBufferNativeHelpers != null);
        // Every call to image.getHardwareBuffer() returns a new reference that needs to be closed.
        try (HardwareBuffer imageWriterHardwareBuffer = checkNotNull(image.getHardwareBuffer())) {
          boolean copySuccess =
              hardwareBufferNativeHelpers.nativeCopyHardwareBufferToHardwareBuffer(
                  hardwareBufferFrame.getHardwareBuffer(), imageWriterHardwareBuffer);
          if (!copySuccess) {
            listenerExecutor.execute(
                () ->
                    listener.onError(
                        new VideoFrameProcessingException(
                            "Failed to copy HardwareBuffer via JNI.")));
            releaseFrameResources(hardwareBufferFrame);
            return;
          }
          // Recycle the pooled buffer. The ImageWriter's buffer is now filled.
          hardwareBufferPool.recycle(hardwareBufferFrame.getHardwareBuffer(), /* fence= */ null);
        }
      } else {
        // Only close the input buffer if it was directly created from an ImageWriter buffer, so
        // needs to be closed separately.
        hardwareBufferFrame.getHardwareBuffer().close();
      }

      Object value = hardwareBufferFrame.getMetadata().get(Frame.KEY_DISPLAY_TIME_NS);
      if (value instanceof Number) {
        releaseTimeNs = ((Number) value).longValue();
        if (releaseTimeNs != C.TIME_UNSET) {
          image.setTimestamp(releaseTimeNs);
        }
      } else {
        releaseTimeNs = C.TIME_UNSET;
      }
      try {
        imageWriterFromFrame.queueInputImage(image);
      } catch (IllegalStateException e) {
        listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
        // If queueInputImage fails, the Image is not consumed. We must close it.
        image.close();
      }
    }

    listenerExecutor.execute(
        () ->
            listener.onFrameAboutToBeRendered(
                hardwareBufferFrame.getContentTimeUs(),
                releaseTimeNs,
                hardwareBufferFrame.getFormat()));
  }

  private void releaseFrameResources(DefaultHardwareBufferFrame frame) {
    frame.getHardwareBuffer().close();
    checkNotNull((InternalImage) frame.getInternalImage()).image.close();
  }

  @Override
  public void signalEndOfStream() {
    listenerExecutor.execute(listener::onEnded);
  }

  @Override
  public void close() {
    synchronized (lock) {
      if (imageWriter != null) {
        imageWriter.close();
        imageWriter = null;
      }
      if (surfaceHolder != null) {
        surfaceHolder.removeCallback(this);
        surfaceHolder = null;
      }
    }
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {}

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    synchronized (lock) {
      if (holder != surfaceHolder) {
        return;
      }
      Format currentFormat = this.currentFormat;
      if (currentFormat == null) {
        return;
      }
      // TODO: b/498547782 - Add pixel format to media3 Format.
      int expectedPixelFormat = HardwareBuffer.RGBA_8888;
      if (width != currentFormat.width
          || height != currentFormat.height
          || format != expectedPixelFormat) {
        this.surface = null;
        holder.setFixedSize(currentFormat.width, currentFormat.height);
        holder.setFormat(expectedPixelFormat);
        return;
      }
      ImageWriter currentImageWriter = imageWriter;
      if (currentImageWriter != null) {
        if (holder.getSurface().equals(this.surface)) {
          return;
        }
        currentImageWriter.close();
      }

      if (SDK_INT >= 33) {
        imageWriter =
            new ImageWriter.Builder(holder.getSurface())
                .setMaxImages(CAPACITY)
                .setUsage(currentUsageFlags)
                .setHardwareBufferFormat(expectedPixelFormat)
                .setDataSpace(
                    DataSpace.pack(
                        colorSpaceToDataSpaceStandard(
                            checkNotNull(currentFormat.colorInfo).colorSpace),
                        colorTransferToDataSpaceTransfer(currentFormat.colorInfo.colorTransfer),
                        colorRangeToDataSpaceRange(currentFormat.colorInfo.colorRange)))
                .build();
      } else {
        imageWriter = ImageWriter.newInstance(holder.getSurface(), CAPACITY);
      }
      surface = holder.getSurface();
      isSurfaceChangeRequested = false;
      Runnable wakeupListener = this.wakeupListener;
      this.wakeupListener = null;
      if (wakeupListener != null) {
        wakeupListener.run();
      }
    }
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    synchronized (lock) {
      ImageWriter imageWriter = this.imageWriter;
      if (imageWriter != null) {
        imageWriter.close();
        this.imageWriter = null;
      }
    }
  }

  @GuardedBy("lock")
  private InternalImage dequeueInternalImage(boolean needsCopy) {
    Image image = checkNotNull(imageWriter).dequeueInputImage();
    return new InternalImage(image, checkNotNull(imageWriter), needsCopy);
  }

  /**
   * Helper class that stores an {@link android.media.Image} along with the {@link
   * android.media.ImageWriter} it was dequeued from, and a flag indicating whether an intermediate
   * copy is needed to write to the {@link android.media.Image}.
   */
  private static final class InternalImage {
    final Image image;
    final ImageWriter imageWriter;
    final boolean needsCopy;

    InternalImage(Image image, ImageWriter imageWriter, boolean needsCopy) {
      this.image = image;
      this.imageWriter = imageWriter;
      this.needsCopy = needsCopy;
    }
  }

  @RequiresApi(33)
  private static int colorSpaceToDataSpaceStandard(@C.ColorSpace int colorSpace) {
    switch (colorSpace) {
      case C.COLOR_SPACE_BT709:
        return DataSpace.STANDARD_BT709;
      case C.COLOR_SPACE_BT601:
        return DataSpace.STANDARD_BT601_625;
      case C.COLOR_SPACE_BT2020:
        return DataSpace.STANDARD_BT2020;
      case Format.NO_VALUE:
      default:
        return DataSpace.STANDARD_UNSPECIFIED;
    }
  }

  @RequiresApi(33)
  private static int colorTransferToDataSpaceTransfer(@C.ColorTransfer int colorTransfer) {
    switch (colorTransfer) {
      case C.COLOR_TRANSFER_ST2084:
        return DataSpace.TRANSFER_ST2084;
      case C.COLOR_TRANSFER_HLG:
        return DataSpace.TRANSFER_HLG;
      case C.COLOR_TRANSFER_SDR:
        return DataSpace.TRANSFER_SMPTE_170M;
      case C.COLOR_TRANSFER_SRGB:
        return DataSpace.TRANSFER_SRGB;
      case C.COLOR_TRANSFER_GAMMA_2_2:
        return DataSpace.TRANSFER_GAMMA2_2;
      case C.COLOR_TRANSFER_LINEAR:
        return DataSpace.TRANSFER_LINEAR;
      case Format.NO_VALUE:
      default:
        return DataSpace.TRANSFER_UNSPECIFIED;
    }
  }

  @RequiresApi(33)
  private static int colorRangeToDataSpaceRange(@C.ColorRange int colorRange) {
    switch (colorRange) {
      case C.COLOR_RANGE_FULL:
        return DataSpace.RANGE_FULL;
      case C.COLOR_RANGE_LIMITED:
        return DataSpace.RANGE_LIMITED;
      case Format.NO_VALUE:
      default:
        return DataSpace.RANGE_UNSPECIFIED;
    }
  }
}
