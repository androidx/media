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
package androidx.media3.effect;

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

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
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.HardwareBufferPool;
import androidx.media3.common.video.HardwareBufferPool.HardwareBufferWithFence;
import java.io.IOException;
import java.util.concurrent.Executor;

/** A {@link HardwareBufferFrameQueue} that outputs frames to a {@link SurfaceHolder}. */
// TODO: b/483974351 - Reduce required API level by copying HardwareBuffer contents into
// android.media.Image and avoid using ImageWriter.Builder().
@RequiresApi(28)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class SurfaceHolderHardwareBufferFrameQueue
    implements HardwareBufferFrameQueue, SurfaceHolder.Callback {

  /** A listener for {@link SurfaceHolderHardwareBufferFrameQueue} events. */
  public interface Listener {
    /**
     * Called when a video frame is about to be rendered.
     *
     * @param presentationTimeUs The presentation time of the frame, in microseconds.
     * @param releaseTimeNs The system time at which the frame should be displayed, in nanoseconds.
     *     Can be compared to {@link System#nanoTime()}. It will be {@link C#TIME_UNSET}, if the
     *     frame is rendered immediately automatically, this is typically the last frame that is
     *     rendered.
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
  private FrameFormat currentFormat;

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
  @Nullable private final HardwareBufferJniWrapper hardwareBufferJniWrapper;

  /**
   * Creates a new instance, that returns {@link HardwareBufferFrame}s that are directly dequeued
   * from an {@link ImageWriter} linked to the output {@link SurfaceHolder#getSurface()}.
   *
   * @param surfaceHolder The {@link SurfaceHolder} to which frames will be written, or {@code null}
   *     if not yet available.
   * @param surfaceHolderExecutor The {@link Executor} on which the surface holder methods will be
   *     called.
   * @param listener The {@link Listener}.
   * @param listenerExecutor The {@link Executor} on which the listener methods will be called.
   */
  @RequiresApi(33)
  public static SurfaceHolderHardwareBufferFrameQueue create(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor) {
    return new SurfaceHolderHardwareBufferFrameQueue(
        surfaceHolder,
        surfaceHolderExecutor,
        listener,
        listenerExecutor,
        /* hardwareBufferJniWrapper= */ null);
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
   * @param hardwareBufferJniWrapper The {@link HardwareBufferJniWrapper} used to copy intermediate
   *     {@link HardwareBuffer}s to {@link HardwareBuffer}s dequeued from {@link ImageWriter}.
   */
  public static SurfaceHolderHardwareBufferFrameQueue create(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor,
      HardwareBufferJniWrapper hardwareBufferJniWrapper) {
    return new SurfaceHolderHardwareBufferFrameQueue(
        surfaceHolder, surfaceHolderExecutor, listener, listenerExecutor, hardwareBufferJniWrapper);
  }

  private SurfaceHolderHardwareBufferFrameQueue(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor,
      @Nullable HardwareBufferJniWrapper hardwareBufferJniWrapper) {
    if (SDK_INT < 33) {
      checkState(hardwareBufferJniWrapper != null);
    }
    lock = new Object();
    this.surfaceHolder = surfaceHolder;
    this.surfaceHolderExecutor = surfaceHolderExecutor;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
    this.hardwareBufferJniWrapper = hardwareBufferJniWrapper;
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
        FrameFormat currentFormat = this.currentFormat;
        if (currentFormat != null) {
          isSurfaceChangeRequested = true;
          surfaceHolderExecutor.execute(
              () -> surfaceHolder.setFixedSize(/* width= */ 1, /* height= */ 1));
        }
      }
    }
  }

  @Override
  @Nullable
  public HardwareBufferFrame dequeue(FrameFormat format, Runnable wakeupListener) {
    synchronized (lock) {
      if (format.equals(currentFormat) && imageWriter != null) {
        try {
          // On API 33+ the usage flags can be directly set on ImageWriter, so the dequeued image
          // can be directly written to by the caller.
          // On API < 33, if no specific usage flags are requested, or CPU usage only is requested,
          // we can also use the dequeued buffer directly.
          if (SDK_INT >= 33
              || format.usageFlags == 0
              || format.usageFlags == HardwareBuffer.USAGE_CPU_WRITE_OFTEN) {
            InternalImage internalImage = dequeueInternalImage(/* needsCopy= */ false);
            return new HardwareBufferFrame.Builder(
                    checkNotNull(internalImage.image.getHardwareBuffer()),
                    directExecutor(),
                    /* releaseCallback= */ (releaseFence) -> {
                      handleEarlyRelease(internalImage);
                    })
                .setInternalFrame(internalImage)
                .build();
          }

          // On API <33, create an intermediate frame with usage equal to the requested usage, that
          // can also be read by the CPU. This will be filled be the caller, then copied into the
          // buffer dequeued from ImageWriter via the JNI.
          checkState(hardwareBufferJniWrapper != null);
          Format requestedFormat =
              new Format.Builder()
                  .setWidth(format.width)
                  .setHeight(format.height)
                  .setColorInfo(format.colorInfo)
                  .build();
          @Nullable
          HardwareBufferWithFence bufferWithFence =
              hardwareBufferPool.get(
                  requestedFormat, format.usageFlags | Frame.USAGE_CPU_READ_OFTEN, wakeupListener);
          // The pool is at capacity, wakeupListener will be invoked when there is an available
          // buffer.
          if (bufferWithFence == null) {
            return null;
          }
          InternalImage internalImage = dequeueInternalImage(/* needsCopy= */ true);
          return new HardwareBufferFrame.Builder(
                  bufferWithFence.hardwareBuffer,
                  directExecutor(),
                  /* releaseCallback= */ (releaseFence) -> {
                    hardwareBufferPool.recycle(bufferWithFence.hardwareBuffer, releaseFence);
                    handleEarlyRelease(internalImage);
                  })
              .setInternalFrame(internalImage)
              .setAcquireFence(bufferWithFence.acquireFence)
              .build();
        } catch (IllegalStateException e) {
          // TODO: b/502865100 - Ensure the error is propagated correctly to avoid pipeline
          //  timeouts.
          listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
          return null;
        }
      }
      if (isSurfaceChangeRequested || surfaceHolder == null) {
        // Waiting for an earlier surface format change or surface holder is not set. Do nothing.
        this.wakeupListener = wakeupListener;
        return null;
      }

      // Request a new surface change.
      isSurfaceChangeRequested = true;
      currentFormat = format;
      if (imageWriter != null) {
        imageWriter.close();
        imageWriter = null;
      }
      this.wakeupListener = wakeupListener;
      // Set the size to an arbitrary value in order to trigger a surfaceChanged() callback,
      // in case the previously set SurfaceHolder size matches the requested format.
      // There is no getter for size or format.
      SurfaceHolder surfaceHolder = checkNotNull(this.surfaceHolder);
      surfaceHolderExecutor.execute(
          () -> surfaceHolder.setFixedSize(/* width= */ 1, /* height= */ 1));
      return null;
    }
  }

  @Override
  public void queue(HardwareBufferFrame frame) {
    InternalImage internalImage = (InternalImage) checkNotNull(frame.internalFrame);
    Image image = internalImage.image;
    ImageWriter imageWriterFromFrame = internalImage.imageWriter;

    synchronized (lock) {
      if (frame.acquireFence != null && SDK_INT >= 33) {
        try {
          image.setFence(frame.acquireFence.asSyncFence());
        } catch (IOException e) {
          listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
          frame.release(/* releaseFence= */ null);
          return;
        }
      }

      if (this.imageWriter != imageWriterFromFrame) {
        // The ImageWriter has changed since this frame was dequeued. The previous writer was
        // closed and this image can no longer be used.
        frame.release(/* releaseFence= */ null);
        return;
      }

      // Copy the input frame's content to the ImageWriter's HardwareBuffer if an intermediate
      // pooled buffer was used.
      if (internalImage.needsCopy) {
        checkState(hardwareBufferJniWrapper != null);
        // Every call to image.getHardwareBuffer() returns a new reference that needs to be closed.
        try (HardwareBuffer imageWriterHardwareBuffer = checkNotNull(image.getHardwareBuffer())) {
          boolean copySuccess =
              hardwareBufferJniWrapper.nativeCopyHardwareBufferToHardwareBuffer(
                  checkNotNull(frame.hardwareBuffer), imageWriterHardwareBuffer);
          if (!copySuccess) {
            listenerExecutor.execute(
                () ->
                    listener.onError(
                        new VideoFrameProcessingException(
                            "Failed to copy HardwareBuffer via JNI.")));
            frame.release(/* releaseFence= */ null);
            return;
          }
          // Recycle the pooled buffer. The ImageWriter's buffer is now filled.
          hardwareBufferPool.recycle(frame.hardwareBuffer, /* fence= */ null);
        }
        // Only close the input buffer if it was directly created from an ImageWriter buffer, so
        // needs to be closed separately.
      } else if (frame.hardwareBuffer != null) {
        frame.hardwareBuffer.close();
      }

      try {
        image.setTimestamp(frame.releaseTimeNs);
        imageWriterFromFrame.queueInputImage(image);
      } catch (IllegalStateException e) {
        // TODO: b/502865100 - Ensure the error is propagated correctly to avoid pipeline timeouts.
        listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
        // If queueInputImage fails, the Image is not consumed. We must close it.
        image.close();
      }
    }

    listenerExecutor.execute(
        () ->
            listener.onFrameAboutToBeRendered(
                frame.presentationTimeUs, frame.releaseTimeNs, frame.format));
  }

  @Override
  public void signalEndOfStream() {
    listenerExecutor.execute(listener::onEnded);
  }

  /**
   * Releases the queue.
   *
   * <p>This method should be called when the queue is no longer needed to unregister callbacks and
   * release resources.
   */
  @Override
  public void release() {
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
      FrameFormat currentFormat = this.currentFormat;
      if (currentFormat == null) {
        return;
      }
      if (width != currentFormat.width
          || height != currentFormat.height
          || format != currentFormat.pixelFormat) {
        this.surface = null;
        holder.setFixedSize(currentFormat.width, currentFormat.height);
        holder.setFormat(currentFormat.pixelFormat);
        return;
      }
      ImageWriter currentImageWriter = imageWriter;
      if (currentImageWriter != null) {
        // If the format, dimensions and surface are the same, ignore this callback and continue to
        // use the existing ImageWriter.
        if (holder.getSurface().equals(this.surface)) {
          return;
        }
        currentImageWriter.close();
      }

      if (SDK_INT >= 33) {
        imageWriter =
            new ImageWriter.Builder(holder.getSurface())
                .setMaxImages(CAPACITY)
                .setUsage(currentFormat.usageFlags)
                .setHardwareBufferFormat(currentFormat.pixelFormat)
                .setDataSpace(
                    DataSpace.pack(
                        colorSpaceToDataSpaceStandard(currentFormat.colorInfo.colorSpace),
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

  /** Dequeues an {@link android.media.Image} from the {@link android.media.ImageWriter}. */
  @GuardedBy("lock")
  private InternalImage dequeueInternalImage(boolean needsCopy) {
    Image image = checkNotNull(imageWriter).dequeueInputImage();
    return new InternalImage(image, checkNotNull(imageWriter), needsCopy);
  }

  /**
   * Close the {@link #imageWriter} if it is still the active one, to ensure no images are leaked or
   * corrupted images rendered. The ImageWriter will be recreated on the next dequeue.
   */
  private void handleEarlyRelease(InternalImage internalImage) {
    synchronized (lock) {
      if (internalImage.imageWriter == imageWriter) {
        checkNotNull(imageWriter).close();
        imageWriter = null;
      }
    }
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
