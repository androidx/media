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
package androidx.media3.transformer;

import static android.hardware.HardwareBuffer.USAGE_VIDEO_ENCODE;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.system.ErrnoException;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.SurfaceInfo;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.effect.HardwareBufferFrame;
import androidx.media3.effect.HardwareBufferFrameQueue;
import androidx.media3.effect.PacketConsumerHardwareBufferFrameQueue;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An implementation of {@link HardwareBufferFrameQueue} that uses an {@link ImageWriter} to forward
 * frames to a {@link SurfaceInfo}. Sets the {@link HardwareBuffer#USAGE_VIDEO_ENCODE} flag on the
 * buffers.
 */
@RequiresApi(33)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public class EncoderWriterHardwareBufferQueue implements HardwareBufferFrameQueue {

  private static final int CAPACITY = 4;

  // TODO: b/484925168 - move the Listener to a more appropriate class or interface.
  private final PacketConsumerHardwareBufferFrameQueue.Listener listener;

  private @MonotonicNonNull ImageWriter imageWriter;

  /** Creates an instance. */
  public EncoderWriterHardwareBufferQueue(
      PacketConsumerHardwareBufferFrameQueue.Listener listener) {
    this.listener = listener;
  }

  @Nullable
  @Override
  public HardwareBufferFrame dequeue(FrameFormat format, Runnable wakeupListener) {
    if (imageWriter == null) {
      SurfaceInfo surfaceInfo;
      try {
        // TODO: b/484926720 - call the Listener on an Executor and get the SurfaceInfo
        // asynchronously.
        surfaceInfo =
            listener.getRendererSurfaceInfo(
                new Format.Builder()
                    .setWidth(format.width)
                    .setHeight(format.height)
                    .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
                    .setSampleMimeType(MimeTypes.VIDEO_H264)
                    .build());
      } catch (VideoFrameProcessingException e) {
        listener.onError(e);
        return null;
      }
      imageWriter =
          new ImageWriter.Builder(checkNotNull(surfaceInfo).surface)
              .setImageFormat(format.pixelFormat)
              .setWidthAndHeight(surfaceInfo.width, surfaceInfo.height)
              .setUsage(USAGE_VIDEO_ENCODE | format.usageFlags)
              .setMaxImages(CAPACITY)
              .build();
    }
    @Nullable Image image = checkNotNull(imageWriter).dequeueInputImage();
    if (image != null) {
      HardwareBuffer hardwareBuffer = checkNotNull(image.getHardwareBuffer());
      return new HardwareBufferFrame.Builder(
              hardwareBuffer,
              directExecutor(),
              /* releaseCallback= */ releaseFence -> {
                throw new UnsupportedOperationException();
              })
          .setInternalFrame(image)
          .build();
    }
    return null;
  }

  @Override
  public void queue(HardwareBufferFrame frame) {
    checkNotNull(frame.hardwareBuffer).close();
    Image image = checkNotNull((Image) frame.internalFrame);
    image.setTimestamp(frame.releaseTimeNs);
    if (frame.acquireFence != null) {
      try {
        checkState(frame.acquireFence.await(/* timeoutMs= */ 500));
        frame.acquireFence.close();
      } catch (ErrnoException | IllegalStateException | IOException e) {
        listener.onError(VideoFrameProcessingException.from(e));
      }
    }
    checkNotNull(imageWriter).queueInputImage(image);
  }

  @Override
  public void signalEndOfStream() {
    listener.onEndOfStream();
  }

  @Override
  public void release() {
    if (imageWriter != null) {
      imageWriter.close();
    }
  }
}
