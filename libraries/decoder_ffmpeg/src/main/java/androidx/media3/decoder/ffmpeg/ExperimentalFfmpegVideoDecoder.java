/*
 * Copyright (C) 2019 The Android Open Source Project
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
package androidx.media3.decoder.ffmpeg;

import static androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE;

import android.view.Surface;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * Ffmpeg Video decoder.
 */
@VisibleForTesting(otherwise = PACKAGE_PRIVATE)
@UnstableApi
/* package */ final class ExperimentalFfmpegVideoDecoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

  private static final String TAG = "FfmpegVideoDecoder";

  // LINT.IfChange
  private static final int VIDEO_DECODER_SUCCESS = 0;
  private static final int VIDEO_DECODER_ERROR_INVALID_DATA = -1;
  private static final int VIDEO_DECODER_ERROR_OTHER = -2;
  private static final int VIDEO_DECODER_ERROR_READ_FRAME = -3;
  // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

  private final String codecName;
  private long nativeContext;
  @Nullable
  private final byte[] extraData;
  @C.VideoOutputMode
  private volatile int outputMode;

  private int degree = 0;

  /**
   * Creates a Ffmpeg video Decoder.
   *
   * @param numInputBuffers        Number of input buffers.
   * @param numOutputBuffers       Number of output buffers.
   * @param initialInputBufferSize The initial size of each input buffer, in bytes.
   * @param threads                Number of threads libffmpeg will use to decode.
   * @throws FfmpegDecoderException Thrown if an exception occurs when initializing the decoder.
   */
  public ExperimentalFfmpegVideoDecoder(
      int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads,
      Format format)
      throws FfmpegDecoderException {
    super(
        new DecoderInputBuffer[numInputBuffers],
        new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!FfmpegLibrary.isAvailable()) {
      throw new FfmpegDecoderException("Failed to load decoder native library.");
    }
    codecName = Assertions.checkNotNull(FfmpegLibrary.getCodecName(format.sampleMimeType));
    extraData = getExtraData(format.sampleMimeType, format.initializationData);
    degree = format.rotationDegrees;
    nativeContext = ffmpegInitialize(codecName, extraData, threads, degree);
    if (nativeContext == 0) {
      throw new FfmpegDecoderException("Failed to initialize decoder.");
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  /**
   * Returns FFmpeg-compatible codec-specific initialization data ("extra data"), or {@code null} if
   * not required.
   */
  @Nullable
  private static byte[] getExtraData(String mimeType, List<byte[]> initializationData) {
    int size = 0;
    for (int i = 0; i < initializationData.size(); i++) {
      size += initializationData.get(i).length;
    }
    if (size > 0) {
      byte[] extra = new byte[size];
      ByteBuffer wrapper = ByteBuffer.wrap(extra);
      for (int i = 0; i < initializationData.size(); i++) {
        wrapper.put(initializationData.get(i));
      }
      return extra;
    }
    return null;
  }

  @Override
  public String getName() {
    return "ffmpeg" + FfmpegLibrary.getVersion() + "-" + codecName;
  }

  /**
   * Sets the output mode for frames rendered by the decoder.
   *
   * @param outputMode The output mode.
   */
  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  @Override
  protected DecoderInputBuffer createInputBuffer() {
    return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
  }

  @Override
  protected VideoDecoderOutputBuffer createOutputBuffer() {
    return new VideoDecoderOutputBuffer(this::releaseOutputBuffer);
  }

  @Override
  @Nullable
  protected FfmpegDecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    if (reset) {

      nativeContext = ffmpegReset(nativeContext);
      if (nativeContext == 0) {
        return new FfmpegDecoderException("Error resetting (see logcat).");
      }
    }

    // send packet
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    // enqueue origin data
    int sendPacketResult = ffmpegSendPacket(nativeContext, inputData, inputSize,
        inputBuffer.timeUs);

    if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (sendPacketResult == VIDEO_DECODER_ERROR_READ_FRAME) {
      // need read frame
    } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
    }

    // receive frame
    boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
    // We need to dequeue the decoded frame from the decoder even when the input data is
    // decode-only.
    if (!decodeOnly) {
      outputBuffer.init(inputBuffer.timeUs, outputMode, null);
    }
    int getFrameResult = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer, decodeOnly);
    if (getFrameResult == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
    }

    if (getFrameResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
    }

    if (!decodeOnly) {
      outputBuffer.format = inputBuffer.format;
    }

    return null;
  }

  @Override
  protected FfmpegDecoderException createUnexpectedDecodeException(Throwable error) {
    return new FfmpegDecoderException("Unexpected decode error", error);
  }

  @Override
  public void release() {
    super.release();
    ffmpegRelease(nativeContext);
    nativeContext = 0;
  }

  /**
   * Renders output buffer to the given surface. Must only be called when in {@link
   * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
   *
   * @param outputBuffer Output buffer.
   * @param surface      Output surface.
   * @throws FfmpegDecoderException Thrown if called with invalid output mode or frame rendering
   *                                fails.
   */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws FfmpegDecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new FfmpegDecoderException("Invalid output mode.");
    }
    int rst = ffmpegRenderFrame(nativeContext, surface, outputBuffer, outputBuffer.width,
        outputBuffer.height);
    if (rst == VIDEO_DECODER_ERROR_OTHER) {
      throw new FfmpegDecoderException(
          "Buffer render error: ");
    }
  }

  private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads,
      int degree);

  private native long ffmpegReset(long context);

  private native void ffmpegRelease(long context);

  private native int ffmpegRenderFrame(
      long context, Surface surface, VideoDecoderOutputBuffer outputBuffer,
      int displayedWidth,
      int displayedHeight);

  /**
   * Decodes the encoded data passed.
   *
   * @param context     Decoder context.
   * @param encodedData Encoded data.
   * @param length      Length of the data buffer.
   * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_OTHER} if an
   * error occurred.
   */
  private native int ffmpegSendPacket(long context, ByteBuffer encodedData, int length,
      long inputTime);

  /**
   * Gets the decoded frame.
   *
   * @param context      Decoder context.
   * @param outputBuffer Output buffer for the decoded frame.
   * @return {@link #VIDEO_DECODER_SUCCESS} if successful, {@link #VIDEO_DECODER_ERROR_INVALID_DATA}
   * if successful but the frame is decode-only, {@link #VIDEO_DECODER_ERROR_OTHER} if an error
   * occurred.
   */
  private native int ffmpegReceiveFrame(
      long context, int outputMode, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

}
