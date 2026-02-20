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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.DataSpace;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.system.ErrnoException;
import android.view.SurfaceHolder;
import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import java.io.IOException;
import java.util.concurrent.Executor;

/** A {@link HardwareBufferFrameQueue} that outputs frames to a {@link SurfaceHolder}. */
// TODO: b/483974351 - Reduce required API level by copying HardwareBuffer contents into
// android.media.Image and avoid using ImageWriter.Builder().
@RequiresApi(33)
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
  private boolean isSurfaceChangeRequested;

  @GuardedBy("lock")
  @Nullable
  private Runnable wakeupListener;

  private final Listener listener;

  private final Executor listenerExecutor;

  /**
   * Creates a new instance.
   *
   * @param surfaceHolder The {@link SurfaceHolder} to which frames will be written, or {@code null}
   *     if not yet available.
   * @param surfaceHolderExecutor The {@link Executor} on which the surface holder methods will be
   *     called.
   * @param listener The {@link Listener}.
   * @param listenerExecutor The {@link Executor} on which the listener methods will be called.
   */
  public SurfaceHolderHardwareBufferFrameQueue(
      @Nullable SurfaceHolder surfaceHolder,
      Executor surfaceHolderExecutor,
      Listener listener,
      Executor listenerExecutor) {
    lock = new Object();
    this.surfaceHolder = surfaceHolder;
    this.surfaceHolderExecutor = surfaceHolderExecutor;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
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
          Image image = imageWriter.dequeueInputImage();
          HardwareBuffer hardwareBuffer = checkNotNull(image.getHardwareBuffer());
          return new HardwareBufferFrame.Builder(
                  hardwareBuffer,
                  directExecutor(),
                  /* releaseCallback= */ (releaseFence) -> {
                    throw new UnsupportedOperationException();
                  })
              .setInternalFrame(new ImageWithImageWriter(image, checkNotNull(imageWriter)))
              .build();
        } catch (IllegalStateException e) {
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
    synchronized (lock) {
      if (frame.acquireFence != null) {
        try {
          checkState(frame.acquireFence.await(/* timeoutMs= */ 500));
          frame.acquireFence.close();
        } catch (ErrnoException | IllegalStateException | IOException e) {
          listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
        }
      }
      if (frame.hardwareBuffer != null) {
        frame.hardwareBuffer.close();
      }
      ImageWithImageWriter imageWithImageWriter =
          (ImageWithImageWriter) checkNotNull(frame.internalFrame);
      ImageWriter imageWriter = this.imageWriter;
      if (imageWriter != imageWithImageWriter.imageWriter) {
        // The ImageWriter has changed since this frame was dequeued. The previous writer was
        // closed and this image can no longer be used.
        return;
      }
      Image image = imageWithImageWriter.image;
      try {
        image.setTimestamp(frame.releaseTimeNs);
        imageWriter.queueInputImage(image);
      } catch (IllegalStateException e) {
        listenerExecutor.execute(() -> listener.onError(new VideoFrameProcessingException(e)));
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
      if (currentFormat == null
          || width != currentFormat.width
          || height != currentFormat.height
          || format != currentFormat.pixelFormat) {
        if (currentFormat != null) {
          holder.setFixedSize(currentFormat.width, currentFormat.height);
          holder.setFormat(currentFormat.pixelFormat);
        }
        return;
      }

      if (imageWriter != null) {
        imageWriter.close();
      }

      imageWriter =
          new ImageWriter.Builder(holder.getSurface())
              .setUsage(currentFormat.usageFlags)
              .setHardwareBufferFormat(currentFormat.pixelFormat)
              .setDataSpace(
                  DataSpace.pack(
                      colorSpaceToDataSpaceStandard(currentFormat.colorInfo.colorSpace),
                      colorTransferToDataSpaceTransfer(currentFormat.colorInfo.colorTransfer),
                      colorRangeToDataSpaceRange(currentFormat.colorInfo.colorRange)))
              .build();

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
      if (imageWriter != null) {
        imageWriter.close();
        imageWriter = null;
      }
    }
  }

  private static final class ImageWithImageWriter {
    final Image image;
    final ImageWriter imageWriter;

    ImageWithImageWriter(Image image, ImageWriter imageWriter) {
      this.image = image;
      this.imageWriter = imageWriter;
    }
  }

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
