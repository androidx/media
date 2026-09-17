/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.transformer;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromRgba8888Image;
import static com.google.common.base.Preconditions.checkArgument;

import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec.BufferInfo;
import android.media.metrics.LogSessionId;
import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A {@link Codec} that captures a single output video frame as a {@linkplain Bitmap}.
 *
 * <p>This encoder is intended for testing single-frame exports (such as static image inputs
 * configured with a single-frame duration). It does not drain frames continuously; providing an
 * input with multiple frames may cause the GL pipeline to block waiting for free buffers.
 */
/* package */ final class SingleFrameCapturingEncoder implements Codec {

  /** A {@link Codec.EncoderFactory} that creates {@link SingleFrameCapturingEncoder} instances. */
  /* package */ static final class Factory implements Codec.EncoderFactory {

    private final AtomicReference<SingleFrameCapturingEncoder> lastVideoEncoder;

    /* package */ Factory() {
      lastVideoEncoder = new AtomicReference<>();
    }

    @Override
    public Codec createForAudioEncoding(Format format, @Nullable LogSessionId logSessionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Codec createForVideoEncoding(Format format, @Nullable LogSessionId logSessionId) {
      SingleFrameCapturingEncoder encoder = new SingleFrameCapturingEncoder(format);
      lastVideoEncoder.set(encoder);
      return encoder;
    }

    @Override
    public boolean isVideoFormatSupported(Format format) {
      return SingleFrameCapturingEncoder.isVideoFormatSupported(format);
    }

    @Override
    public boolean audioNeedsEncoding() {
      return false;
    }

    @Override
    public boolean videoNeedsEncoding() {
      return true;
    }

    /**
     * Returns the last captured {@linkplain Bitmap bitmap}, or {@code null} if no frame was
     * captured.
     */
    @Nullable
    /* package */ Bitmap getLastBitmap() {
      @Nullable SingleFrameCapturingEncoder encoder = lastVideoEncoder.get();
      return encoder != null ? encoder.getLastBitmap() : null;
    }
  }

  private static final int MAX_PENDING_FRAME_COUNT = 1;

  private final Format configurationFormat;
  private final ImageReader imageReader;

  @Nullable private volatile Bitmap lastBitmap;
  private volatile boolean inputStreamEnded;

  /* package */ SingleFrameCapturingEncoder(Format configurationFormat) {
    checkArgument(
        isVideoFormatSupported(configurationFormat),
        "Unsupported configuration format: %s",
        configurationFormat);
    this.configurationFormat = configurationFormat;
    this.imageReader =
        SDK_INT >= 29
            ? ImageReader.newInstance(
                configurationFormat.width,
                configurationFormat.height,
                PixelFormat.RGBA_8888,
                /* maxImages= */ MAX_PENDING_FRAME_COUNT,
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT | HardwareBuffer.USAGE_CPU_READ_OFTEN)
            : ImageReader.newInstance(
                configurationFormat.width,
                configurationFormat.height,
                PixelFormat.RGBA_8888,
                /* maxImages= */ MAX_PENDING_FRAME_COUNT);
  }

  @Override
  public String getName() {
    return "SingleFrameCapturingEncoder";
  }

  @Override
  public Format getConfigurationFormat() {
    return configurationFormat;
  }

  @Override
  public Format getInputFormat() {
    return configurationFormat;
  }

  @Override
  public Surface getInputSurface() {
    return imageReader.getSurface();
  }

  @Override
  public int getMaxPendingFrameCount() {
    return MAX_PENDING_FRAME_COUNT;
  }

  @Override
  public boolean maybeDequeueInputBuffer(DecoderInputBuffer inputBuffer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void queueInputBuffer(DecoderInputBuffer inputBuffer) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void signalEndOfInputStream() {
    try (@Nullable Image image = imageReader.acquireLatestImage()) {
      if (image != null) {
        lastBitmap = createArgb8888BitmapFromRgba8888Image(image);
      }
    } finally {
      try {
        imageReader.close();
      } finally {
        inputStreamEnded = true;
      }
    }
  }

  @Override
  @Nullable
  public Format getOutputFormat() {
    return configurationFormat;
  }

  @Override
  @Nullable
  public ByteBuffer getOutputBuffer() {
    return null;
  }

  @Override
  @Nullable
  public BufferInfo getOutputBufferInfo() {
    return null;
  }

  @Override
  public void releaseOutputBuffer(boolean render) {}

  @Override
  public void releaseOutputBuffer(long renderPresentationTimeUs) {}

  @Override
  public boolean isEnded() {
    return inputStreamEnded;
  }

  @Override
  public void release() {
    imageReader.close();
  }

  /**
   * Returns the last captured {@linkplain Bitmap bitmap}, or {@code null} if no frame was captured.
   */
  @Nullable
  /* package */ Bitmap getLastBitmap() {
    return lastBitmap;
  }

  private static boolean isVideoFormatSupported(Format format) {
    return format.sampleMimeType != null
        && MimeTypes.isVideo(format.sampleMimeType)
        && format.width > 0
        && format.height > 0;
  }
}
