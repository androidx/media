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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.annotation.SuppressLint;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageWriter;
import android.os.Handler;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.DefaultHardwareBufferFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.Frame.Usage;
import androidx.media3.common.video.FrameWriter;
import androidx.media3.common.video.SyncFenceWrapper;
import java.io.IOException;
import java.util.concurrent.Executor;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * An implementation of {@link FrameWriter} that uses an {@link ImageWriter} to forward frames to a
 * {@linkplain DefaultCodec encoder} via {@link Surface}. Sets the {@link
 * HardwareBuffer#USAGE_VIDEO_ENCODE} flag on the buffers.
 */
@RequiresApi(33)
@ExperimentalApi // TODO: b/498176910 - Remove once FrameWriter is production ready.
public class EncoderFrameWriter implements FrameWriter {

  /** Listener for {@link EncoderFrameWriter} events. */
  public interface Listener {

    /** Called when the encoder is created so downstream components can receive its output. */
    void onEncoderCreated(Codec encoder);

    /** Called when the end of stream has been reached and is about to be signaled downstream. */
    void onEndOfStream();

    /** Called when an asynchronous error occurs. */
    void onError(VideoFrameProcessingException e);
  }

  private static final int CAPACITY = 4;

  private final Codec.EncoderFactory encoderFactory;
  private final Listener listener;
  private final Executor listenerExecutor;
  private final Handler imageReleaseHandler;

  private int inUseCount;
  private @MonotonicNonNull Format configurationFormat;
  @Nullable private ImageWriter imageWriter;
  @Nullable private Codec encoder;
  @Nullable private Executor wakeupExecutor;
  @Nullable private Runnable wakeupListener;

  public EncoderFrameWriter(
      Codec.EncoderFactory encoderFactory,
      Listener listener,
      Executor listenerExecutor,
      Handler imageReleaseHandler) {
    this.encoderFactory = encoderFactory;
    this.listener = listener;
    this.listenerExecutor = listenerExecutor;
    this.imageReleaseHandler = imageReleaseHandler;
  }

  @Override
  public Info getInfo() {
    return (format, usage) -> {
      // TODO: b/505290710 - Expose the actual encoder format checks.
      return true;
    };
  }

  @SuppressLint("WrongConstant") // Using usage as @Usage constant.
  @Override
  public void configure(Format format, @Usage long usage) {
    checkState(imageWriter == null);
    checkState(encoder == null);

    try {
      // TODO: b/505290710 - Propagate LogSessionId to the encoder factory.
      encoder = encoderFactory.createForVideoEncoding(format, /* logSessionId= */ null);
    } catch (ExportException e) {
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
      return;
    }

    Surface encoderInputSurface = encoder.getInputSurface();
    Codec nonNullEncoder = encoder;
    listenerExecutor.execute(() -> listener.onEncoderCreated(nonNullEncoder));

    this.configurationFormat = nonNullEncoder.getConfigurationFormat();

    // TODO: b/498547782 - Add pixel format to media3 Format.
    int pixelFormat =
        ColorInfo.isTransferHdr(configurationFormat.colorInfo)
            ? HardwareBuffer.RGBA_1010102
            : HardwareBuffer.RGBA_8888;

    imageWriter =
        new ImageWriter.Builder(encoderInputSurface)
            .setImageFormat(pixelFormat)
            .setWidthAndHeight(configurationFormat.width, configurationFormat.height)
            .setUsage(Frame.USAGE_VIDEO_ENCODE | usage)
            .setMaxImages(CAPACITY)
            .build();
    imageWriter.setOnImageReleasedListener((writer) -> onImageReleased(), imageReleaseHandler);
  }

  @Nullable
  @Override
  public synchronized AsyncFrame dequeueInputFrame(
      Executor wakeupExecutor, Runnable wakeupListener) {
    checkState(imageWriter != null);
    if (inUseCount == CAPACITY) {
      this.wakeupExecutor = wakeupExecutor;
      this.wakeupListener = wakeupListener;
      return null;
    }
    @Nullable Image image = imageWriter.dequeueInputImage();
    if (image != null) {
      inUseCount++;
      HardwareBuffer hardwareBuffer = checkNotNull(image.getHardwareBuffer());
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer)
              .setFormat(checkNotNull(configurationFormat))
              .setInternalImage(image)
              .build();
      return new AsyncFrame(frame, /* acquireFence= */ null);
    }
    this.wakeupExecutor = wakeupExecutor;
    this.wakeupListener = wakeupListener;
    return null;
  }

  @Override
  public void queueInputFrame(Frame frame, @Nullable SyncFenceWrapper writeCompleteFence) {
    checkState(imageWriter != null);
    checkArgument(frame instanceof DefaultHardwareBufferFrame);
    DefaultHardwareBufferFrame hardwareBufferFrame = (DefaultHardwareBufferFrame) frame;
    @Nullable Image image = (Image) hardwareBufferFrame.getInternalImage();
    checkArgument(image != null);
    // The downstream encoder uses the image timestamp as the presentation timestamp.
    image.setTimestamp(frame.getContentTimeUs() * 1000L);

    if (writeCompleteFence != null) {
      try {
        image.setFence(writeCompleteFence.asSyncFence());
      } catch (IOException e) {
        listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
        return;
      }
    }
    checkNotNull(imageWriter).queueInputImage(image);
  }

  @Override
  public void signalEndOfStream() {
    checkState(encoder != null);
    try {
      encoder.signalEndOfInputStream();
      listenerExecutor.execute(listener::onEndOfStream);
    } catch (ExportException e) {
      listenerExecutor.execute(() -> listener.onError(VideoFrameProcessingException.from(e)));
    }
  }

  @Override
  public void close() {
    if (imageWriter != null) {
      imageWriter.close();
      imageWriter = null;
    }
    if (encoder != null) {
      encoder.release();
      encoder = null;
    }
    wakeupExecutor = null;
    wakeupListener = null;
  }

  private synchronized void onImageReleased() {
    inUseCount--;
    if (wakeupExecutor != null && wakeupListener != null) {
      wakeupExecutor.execute(checkNotNull(wakeupListener));
      wakeupExecutor = null;
      wakeupListener = null;
    }
  }
}
