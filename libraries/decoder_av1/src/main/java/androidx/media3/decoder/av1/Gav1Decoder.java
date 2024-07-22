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
package androidx.media3.decoder.av1;

import static androidx.annotation.VisibleForTesting.PACKAGE_PRIVATE;
import static java.lang.Runtime.getRuntime;

import android.view.Surface;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;

/** Gav1 decoder. */
@VisibleForTesting(otherwise = PACKAGE_PRIVATE)
@UnstableApi
public final class Gav1Decoder
    extends SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, Gav1DecoderException> {

  private static final int GAV1_ERROR = 0;
  private static final int GAV1_OK = 1;
  private static final int GAV1_DECODE_ONLY = 2;

  private final long gav1DecoderContext;

  private volatile @C.VideoOutputMode int outputMode;

  /**
   * Creates a Gav1Decoder.
   *
   * @param numInputBuffers Number of input buffers.
   * @param numOutputBuffers Number of output buffers.
   * @param initialInputBufferSize The initial size of each input buffer, in bytes.
   * @param threads Number of threads libgav1 will use to decode. If {@link
   *     Libgav1VideoRenderer#THREAD_COUNT_AUTODETECT} is passed, then this class will auto detect
   *     the number of threads to be used.
   * @throws Gav1DecoderException Thrown if an exception occurs when initializing the decoder.
   */
  public Gav1Decoder(
      int numInputBuffers, int numOutputBuffers, int initialInputBufferSize, int threads)
      throws Gav1DecoderException {
    super(new DecoderInputBuffer[numInputBuffers], new VideoDecoderOutputBuffer[numOutputBuffers]);
    if (!Gav1Library.isAvailable()) {
      throw new Gav1DecoderException("Failed to load decoder native library.");
    }

    if (threads == Libgav1VideoRenderer.THREAD_COUNT_AUTODETECT) {
      // Try to get the optimal number of threads from the AV1 heuristic.
      threads = gav1GetThreads();
      if (threads <= 0) {
        // If that is not available, default to the number of available processors.
        threads = getRuntime().availableProcessors();
      }
    }

    gav1DecoderContext = gav1Init(threads);
    if (gav1DecoderContext == GAV1_ERROR || gav1CheckError(gav1DecoderContext) == GAV1_ERROR) {
      throw new Gav1DecoderException(
          "Failed to initialize decoder. Error: " + gav1GetErrorMessage(gav1DecoderContext));
    }
    setInitialInputBufferSize(initialInputBufferSize);
  }

  @Override
  public String getName() {
    return "libgav1";
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
  protected Gav1DecoderException decode(
      DecoderInputBuffer inputBuffer, VideoDecoderOutputBuffer outputBuffer, boolean reset) {
    ByteBuffer inputData = Util.castNonNull(inputBuffer.data);
    int inputSize = inputData.limit();
    if (gav1Decode(gav1DecoderContext, inputData, inputSize) == GAV1_ERROR) {
      return new Gav1DecoderException(
          "gav1Decode error: " + gav1GetErrorMessage(gav1DecoderContext));
    }

    boolean decodeOnly = !isAtLeastOutputStartTimeUs(inputBuffer.timeUs);
    if (!decodeOnly) {
      outputBuffer.init(inputBuffer.timeUs, outputMode, /* supplementalData= */ null);
    }
    // We need to dequeue the decoded frame from the decoder even when the input data is
    // decode-only.
    int getFrameResult = gav1GetFrame(gav1DecoderContext, outputBuffer, decodeOnly);
    if (getFrameResult == GAV1_ERROR) {
      return new Gav1DecoderException(
          "gav1GetFrame error: " + gav1GetErrorMessage(gav1DecoderContext));
    }
    if (getFrameResult == GAV1_DECODE_ONLY) {
      outputBuffer.shouldBeSkipped = true;
    }
    if (!decodeOnly) {
      outputBuffer.format = inputBuffer.format;
    }

    return null;
  }

  @Override
  protected Gav1DecoderException createUnexpectedDecodeException(Throwable error) {
    return new Gav1DecoderException("Unexpected decode error", error);
  }

  @Override
  public void release() {
    super.release();
    gav1Close(gav1DecoderContext);
  }

  @Override
  protected void releaseOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    // Skipped frames do not acquire a reference on the internal decoder buffer and thus do not
    // require a call to gav1ReleaseFrame.
    if (outputBuffer.mode == C.VIDEO_OUTPUT_MODE_SURFACE_YUV && !outputBuffer.shouldBeSkipped) {
      gav1ReleaseFrame(gav1DecoderContext, outputBuffer);
    }
    super.releaseOutputBuffer(outputBuffer);
  }

  /**
   * Sets the output mode for frames rendered by the decoder.
   *
   * @param outputMode The output mode.
   */
  public void setOutputMode(@C.VideoOutputMode int outputMode) {
    this.outputMode = outputMode;
  }

  /**
   * Renders output buffer to the given surface. Must only be called when in {@link
   * C#VIDEO_OUTPUT_MODE_SURFACE_YUV} mode.
   *
   * @param outputBuffer Output buffer.
   * @param surface Output surface.
   * @throws Gav1DecoderException Thrown if called with invalid output mode or frame rendering
   *     fails.
   */
  public void renderToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws Gav1DecoderException {
    if (outputBuffer.mode != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) {
      throw new Gav1DecoderException("Invalid output mode.");
    }
    if (gav1RenderFrame(gav1DecoderContext, surface, outputBuffer) == GAV1_ERROR) {
      throw new Gav1DecoderException(
          "Buffer render error: " + gav1GetErrorMessage(gav1DecoderContext));
    }
  }

  /**
   * Initializes a libgav1 decoder.
   *
   * @param threads Number of threads to be used by a libgav1 decoder.
   * @return The address of the decoder context or {@link #GAV1_ERROR} if there was an error.
   */
  private native long gav1Init(int threads);

  /**
   * Deallocates the decoder context.
   *
   * @param context Decoder context.
   */
  private native void gav1Close(long context);

  /**
   * Decodes the encoded data passed.
   *
   * @param context Decoder context.
   * @param encodedData Encoded data.
   * @param length Length of the data buffer.
   * @return {@link #GAV1_OK} if successful, {@link #GAV1_ERROR} if an error occurred.
   */
  private native int gav1Decode(long context, ByteBuffer encodedData, int length);

  /**
   * Gets the decoded frame.
   *
   * @param context Decoder context.
   * @param outputBuffer Output buffer for the decoded frame.
   * @return {@link #GAV1_OK} if successful, {@link #GAV1_DECODE_ONLY} if successful but the frame
   *     is decode-only, {@link #GAV1_ERROR} if an error occurred.
   */
  private native int gav1GetFrame(
      long context, VideoDecoderOutputBuffer outputBuffer, boolean decodeOnly);

  /**
   * Renders the frame to the surface. Used with {@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV} only.
   *
   * @param context Decoder context.
   * @param surface Output surface.
   * @param outputBuffer Output buffer with the decoded frame.
   * @return {@link #GAV1_OK} if successful, {@link #GAV1_ERROR} if an error occurred.
   */
  private native int gav1RenderFrame(
      long context, Surface surface, VideoDecoderOutputBuffer outputBuffer);

  /**
   * Releases the frame. Used with {@link C#VIDEO_OUTPUT_MODE_SURFACE_YUV} only.
   *
   * @param context Decoder context.
   * @param outputBuffer Output buffer.
   */
  private native void gav1ReleaseFrame(long context, VideoDecoderOutputBuffer outputBuffer);

  /**
   * Returns a human-readable string describing the last error encountered in the given context.
   *
   * @param context Decoder context.
   * @return A string describing the last encountered error.
   */
  private native String gav1GetErrorMessage(long context);

  /**
   * Returns whether an error occurred.
   *
   * @param context Decoder context.
   * @return {@link #GAV1_OK} if there was no error, {@link #GAV1_ERROR} if an error occurred.
   */
  private native int gav1CheckError(long context);

  /**
   * Returns the optimal number of threads to be used for AV1 decoding.
   *
   * @return Optimal number of threads if there was no error, 0 if an error occurred.
   */
  private native int gav1GetThreads();
}
