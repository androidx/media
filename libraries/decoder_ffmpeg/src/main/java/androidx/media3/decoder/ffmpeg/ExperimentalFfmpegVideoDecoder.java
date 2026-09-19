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
import java.util.ArrayDeque;
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
  private static final int VIDEO_DECODER_ERROR_SURFACE = -4;
  // LINT.ThenChange(../../../../../../../jni/ffmpeg_jni.cc)

  private final String codecName;
  private long nativeContext;
  @Nullable
  private final byte[] extraData;
  @C.VideoOutputMode
  private volatile int outputMode;

  private int degree = 0;

  /**
   * Input samples that could not be sent yet because FFmpeg's internal queue was full
   * (avcodec_send_packet returned EAGAIN, which does NOT consume the packet). A queue is
   * used because the queue can stay full for several consecutive inputs (e.g. while
   * FFmpeg's frame threads are still decoding earlier packets); overwriting a single
   * pending slot would drop the earlier sample and corrupt the reference chain.
   */
  private final ArrayDeque<PendingInput> pendingInputs = new ArrayDeque<>();

  /** A copy of an input sample that is waiting to be sent to FFmpeg. */
  private static final class PendingInput {
    public final ByteBuffer data;
    public final long timeUs;

    public PendingInput(ByteBuffer data, long timeUs) {
      this.data = data;
      this.timeUs = timeUs;
    }
  }

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
    nativeContext = ffmpegInitialize(codecName, extraData, threads, degree, format.width, format.height);
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
    // FFmpeg bitstream parsers may read up to AV_INPUT_BUFFER_PADDING_SIZE bytes past the
    // end of the packet data, so the input buffers must be padded like the audio decoder.
    return new DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT,
        FfmpegLibrary.getInputBufferPaddingSize());
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
      pendingInputs.clear();
    }

    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);

    // 1. Send as many pending inputs as FFmpeg currently accepts. A pending sample that
    // returns EAGAIN stays at the head and is retried on the next call.
    while (!pendingInputs.isEmpty()) {
      PendingInput pending = pendingInputs.peek();
      int pendingResult =
          ffmpegSendPacket(nativeContext, pending.data, pending.data.limit(), pending.timeUs);
      if (pendingResult == VIDEO_DECODER_ERROR_READ_FRAME) {
        break;
      }
      pendingInputs.removeFirst();
      if (pendingResult == VIDEO_DECODER_ERROR_OTHER) {
        return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
      }
      // VIDEO_DECODER_ERROR_INVALID_DATA: drop the invalid sample and continue.
    }

    // 2. Send the current input packet. If FFmpeg's queue is still full, cache the input
    // (it must never be dropped: avcodec_send_packet EAGAIN means "not consumed") and
    // drain one decoded frame into this output buffer instead.
    int sendPacketResult =
        ffmpegSendPacket(nativeContext, inputData, inputData.limit(), inputBuffer.timeUs);

    if (sendPacketResult == VIDEO_DECODER_ERROR_READ_FRAME) {
      pendingInputs.addLast(copyPendingInput(inputData, inputBuffer.timeUs));
      if (!decodeOnly) {
        outputBuffer.init(inputBuffer.timeUs, outputMode, null);
      }
      int drainResult = ffmpegReceiveFrame(nativeContext, outputMode, outputBuffer, decodeOnly);
      if (drainResult == VIDEO_DECODER_ERROR_OTHER) {
        return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
      }
      if (drainResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
        outputBuffer.shouldBeSkipped = true;
      } else if (!decodeOnly) {
        // The drained frame belongs to the same stream, so the current format applies.
        outputBuffer.format = inputBuffer.format;
      }
      return null;
    }

    if (sendPacketResult == VIDEO_DECODER_ERROR_INVALID_DATA) {
      outputBuffer.shouldBeSkipped = true;
      return null;
    } else if (sendPacketResult == VIDEO_DECODER_ERROR_OTHER) {
      return new FfmpegDecoderException("ffmpegDecode error: (see logcat)");
    }

    // receive frame
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

  /**
   * Copies the input sample into a decoder-owned buffer so it survives the return from
   * {@link #decode} (the {@link DecoderInputBuffer} is returned to the pool afterwards).
   */
  private static PendingInput copyPendingInput(ByteBuffer inputData, long timeUs) {
    inputData.position(0);
    ByteBuffer copy = ByteBuffer.allocateDirect(inputData.limit());
    copy.put(inputData);
    copy.flip();
    return new PendingInput(copy, timeUs);
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
    if (rst == VIDEO_DECODER_ERROR_OTHER || rst == VIDEO_DECODER_ERROR_SURFACE) {
      throw new FfmpegDecoderException(
          "Buffer render error: " + rst);
    }
  }

  private native long ffmpegInitialize(String codecName, @Nullable byte[] extraData, int threads,
                                       int degree, int width, int height);

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
