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
package androidx.media3.decoder.av1;

import static com.google.common.base.Preconditions.checkArgument;

import android.os.Process;
import android.view.Surface;
import androidx.annotation.IntRange;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.VideoExtensionCodecAdapter;
import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** A {@link MediaCodecAdapter} that wraps a {@link Dav1dDecoder}. */
@UnstableApi
public final class Dav1dCodecAdapter extends VideoExtensionCodecAdapter {

  /** A factory for {@link Dav1dCodecAdapter} instances. */
  public static final class Factory implements MediaCodecAdapter.Factory {

    private int numInputBuffers;
    private int numOutputBuffers;
    private int initialInputBufferSize;
    private int threads;
    private int maxFrameDelay;
    private boolean useCustomAllocator;
    private int threadPriority;

    // LINT.IfChange(factory_constructor)
    /** Creates a factory for {@link Dav1dCodecAdapter} instances with default settings. */
    public Factory() {
      numInputBuffers = DEFAULT_NUM_INPUT_BUFFERS;
      numOutputBuffers = DEFAULT_NUM_OUTPUT_BUFFERS;
      initialInputBufferSize = DEFAULT_INPUT_BUFFER_SIZE;
      threads = THREAD_COUNT_DECODER_DEFAULT;
      maxFrameDelay = DEFAULT_MAX_FRAME_DELAY;
      useCustomAllocator = false;
      threadPriority = C.THREAD_PRIORITY_NO_OP;
    }

    // LINT.ThenChange(
    //     ../../../../../../../../exoplayer/proguard-rules.txt,
    //
    // ../../../../../../../../exoplayer/src/main/java/androidx/media3/exoplayer/mediacodec/DefaultMediaCodecAdapterFactory.java:dav1d_adapter_factory)

    /**
     * Sets the number of input buffers. The default value is {@link #DEFAULT_NUM_INPUT_BUFFERS}.
     *
     * <p>{@code numInputBuffers} should exceed the effective maximum frame delay (see {@link
     * #setMaxFrameDelay(int)}) so asynchronous frame-threading cannot exhaust the input buffer
     * pool.
     *
     * @param numInputBuffers The number of input buffers. Must be greater than zero.
     * @return This factory, for convenience.
     * @throws IllegalArgumentException If {@code numInputBuffers} is non-positive.
     */
    @CanIgnoreReturnValue
    public Factory setNumInputBuffers(@IntRange(from = 1) int numInputBuffers) {
      checkArgument(numInputBuffers > 0);
      this.numInputBuffers = numInputBuffers;
      return this;
    }

    /**
     * Sets the number of output buffers. The default value is {@link #DEFAULT_NUM_OUTPUT_BUFFERS}.
     *
     * <p>At least 2 output buffers are required when rendering to a {@link
     * VideoDecoderOutputBufferRenderer}; configuring {@code 1} causes decoding to stall after the
     * first frame because the renderer retains the currently displayed buffer until the next frame
     * is queued.
     *
     * @param numOutputBuffers The number of output buffers. Must be greater than zero.
     * @return This factory, for convenience.
     * @throws IllegalArgumentException If {@code numOutputBuffers} is non-positive.
     */
    @CanIgnoreReturnValue
    public Factory setNumOutputBuffers(@IntRange(from = 1) int numOutputBuffers) {
      checkArgument(numOutputBuffers > 0);
      this.numOutputBuffers = numOutputBuffers;
      return this;
    }

    /**
     * Sets the initial size of each input buffer in bytes, used when {@link Format#maxInputSize} is
     * {@link Format#NO_VALUE} or non-positive. The default value is {@link
     * #DEFAULT_INPUT_BUFFER_SIZE}.
     *
     * @param initialInputBufferSize The initial input buffer size in bytes. Must be greater than
     *     zero.
     * @return This factory, for convenience.
     * @throws IllegalArgumentException If {@code initialInputBufferSize} is non-positive.
     */
    @CanIgnoreReturnValue
    public Factory setInitialInputBufferSize(@IntRange(from = 1) int initialInputBufferSize) {
      checkArgument(initialInputBufferSize > 0);
      this.initialInputBufferSize = initialInputBufferSize;
      return this;
    }

    /**
     * Sets the number of threads libdav1d will use to decode. The default value is {@link
     * #THREAD_COUNT_DECODER_DEFAULT}.
     *
     * @param threads A positive thread count (at most 256), {@link #THREAD_COUNT_DECODER_DEFAULT},
     *     {@link #THREAD_COUNT_PERFORMANCE_CORES}, or {@link #THREAD_COUNT_EXPERIMENTAL}.
     * @return This factory, for convenience.
     * @throws IllegalArgumentException If {@code threads} is not a supported thread count.
     */
    @CanIgnoreReturnValue
    public Factory setThreads(@IntRange(from = -2, to = 256) int threads) {
      checkArgument(
          (threads > 0
                  || threads == THREAD_COUNT_DECODER_DEFAULT
                  || threads == THREAD_COUNT_PERFORMANCE_CORES
                  || threads == THREAD_COUNT_EXPERIMENTAL)
              && threads <= 256);
      this.threads = threads;
      return this;
    }

    /**
     * Sets the maximum frame delay for libdav1d. The default value is {@link
     * #DEFAULT_MAX_FRAME_DELAY}.
     *
     * <p>The effective maximum frame delay should be less than {@code numInputBuffers} (see {@link
     * #setNumInputBuffers(int)}) so asynchronous frame-threading cannot exhaust the input buffer
     * pool.
     *
     * @param maxFrameDelay The maximum frame delay (between 0 and 256 inclusive, where 0 uses the
     *     libdav1d default).
     * @return This factory, for convenience.
     * @throws IllegalArgumentException If {@code maxFrameDelay} is outside {@code [0, 256]}.
     */
    @SuppressWarnings({"ApiWithNumericTimeUnit", "GoodTime-ApiWithNumericTimeUnit"}) // Frame count.
    @CanIgnoreReturnValue
    public Factory setMaxFrameDelay(@IntRange(from = 0, to = 256) int maxFrameDelay) {
      checkArgument(maxFrameDelay >= 0 && maxFrameDelay <= 256);
      this.maxFrameDelay = maxFrameDelay;
      return this;
    }

    /**
     * Sets whether to use a custom allocator for libdav1d. The default value is {@code false}.
     *
     * @param useCustomAllocator Whether to use a custom allocator.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setUseCustomAllocator(boolean useCustomAllocator) {
      this.useCustomAllocator = useCustomAllocator;
      return this;
    }

    /**
     * Sets the thread priority for the decode thread and native worker threads. The default value
     * is {@link C#THREAD_PRIORITY_NO_OP}.
     *
     * @param threadPriority A Linux priority level for {@link Process#setThreadPriority(int)}
     *     (expected in {@code [-20, 19]}; values outside this range are clamped, and constants are
     *     defined in {@link Process} with prefix {@code THREAD_PRIORITY_*}), or {@link
     *     C#THREAD_PRIORITY_NO_OP} to leave the default thread priority unchanged.
     * @return This factory, for convenience.
     */
    @CanIgnoreReturnValue
    public Factory setThreadPriority(int threadPriority) {
      this.threadPriority = threadPriority;
      return this;
    }

    @Override
    public MediaCodecAdapter createAdapter(MediaCodecAdapter.Configuration configuration)
        throws IOException {
      if (!(configuration.codecInfo instanceof Dav1dCodecInfo)) {
        throw createInitializationException(
            "Dav1dCodecAdapter requires Dav1dCodecInfo, got: " + configuration.codecInfo.name);
      }
      Dav1dCodecInfo dav1dCodecInfo = (Dav1dCodecInfo) configuration.codecInfo;
      if (!Objects.equals(configuration.format.sampleMimeType, MimeTypes.VIDEO_AV1)) {
        throw createInitializationException(
            "Unsupported sample MIME type: " + configuration.format.sampleMimeType);
      }
      if (configuration.crypto != null
          || configuration.format.cryptoType != C.CRYPTO_TYPE_NONE
          || configuration.format.drmInitData != null) {
        throw createInitializationException("Dav1d decoder does not support secure decode");
      }
      if (!dav1dCodecInfo.isFormatSupported(configuration.format)) {
        throw createInitializationException(
            "Unsupported AV1 format: " + Format.toLogString(configuration.format));
      }
      int inputBufferSize = getInputBufferSize(configuration);
      @Nullable Dav1dDecoder decoder = null;
      boolean initialized = false;
      try {
        decoder =
            new Dav1dDecoder(
                numInputBuffers,
                numOutputBuffers,
                inputBufferSize,
                threads,
                maxFrameDelay,
                useCustomAllocator,
                threadPriority);
        Dav1dCodecAdapter adapter =
            new Dav1dCodecAdapter(configuration.format, decoder, configuration.surface);
        initialized = true;
        return adapter;
      } catch (Dav1dDecoderException | OutOfMemoryError e) {
        throw new IOException("Failed to initialize Dav1dDecoder", e);
      } finally {
        if (!initialized && decoder != null) {
          decoder.release();
        }
      }
    }

    /**
     * Returns the initial input buffer size in bytes for {@code configuration}, falling back to the
     * factory's configured initial input buffer size when {@link Format#maxInputSize} is unset or
     * non-positive.
     */
    @VisibleForTesting
    /* package */ int getInputBufferSize(MediaCodecAdapter.Configuration configuration) {
      // Intentionally ignore MediaFormat.KEY_MAX_INPUT_SIZE on configuration.mediaFormat:
      // MediaCodecVideoRenderer populates a worst-case uncompressed heuristic for platform
      // MediaCodec buffers, whereas Dav1dDecoder uses
      // DecoderInputBuffer(BUFFER_REPLACEMENT_MODE_DIRECT)
      // and dynamically reallocates if a sample exceeds the initial buffer size.
      return configuration.format.maxInputSize > 0
          ? configuration.format.maxInputSize
          : initialInputBufferSize;
    }

    private static IOException createInitializationException(String message) {
      return new IOException(
          "Failed to initialize Dav1dDecoder", new Dav1dDecoderException(message));
    }
  }

  // LINT.IfChange(thread_count_constants)
  /** Uses the default number of threads determined by libdav1d. */
  public static final int THREAD_COUNT_DECODER_DEFAULT = 0;

  /** Uses one thread per performance core on the device. */
  public static final int THREAD_COUNT_PERFORMANCE_CORES = -1;

  /** Experimental setting that uses half the number of online processors on the device. */
  @ExperimentalApi // TODO(b/562381449): Remove or make non-experimental.
  public static final int THREAD_COUNT_EXPERIMENTAL = -2;

  // LINT.ThenChange(
  //     ../../../../../jni/dav1d_jni.cc,
  //     Libdav1dVideoRenderer.java:thread_count_constants
  // )

  /**
   * Default input buffer size in bytes (720 KB), based on 720p resolution video compressed by a
   * factor of two.
   */
  public static final int DEFAULT_INPUT_BUFFER_SIZE =
      Util.ceilDivide(1280, 64) * Util.ceilDivide(720, 64) * (64 * 64 * 3 / 2) / 2;

  /** Default number of input buffers. */
  public static final int DEFAULT_NUM_INPUT_BUFFERS = 4;

  /** Default number of output buffers. */
  public static final int DEFAULT_NUM_OUTPUT_BUFFERS = 4;

  /** Default maximum frame delay. */
  @SuppressWarnings({"ApiWithNumericTimeUnit", "GoodTime-ApiWithNumericTimeUnit"}) // Frame count.
  public static final int DEFAULT_MAX_FRAME_DELAY = 2;

  private Dav1dCodecAdapter(Format format, Dav1dDecoder decoder, @Nullable Surface surface) {
    super(format, decoder, surface);
    // Default to decoding into Dav1dPicture handles (VIDEO_OUTPUT_MODE_SURFACE_YUV) so frames
    // decoded before an output is attached retain their picture at zero copy cost and can be
    // rendered once a Surface is attached. While no output is attached, up to numOutputBuffers
    // decoded pictures remain retained until released, and 10-bit non-I420/I400 frames that bypass
    // Dav1dCodecInfo (for example, when Format.codecs is unset) fail in dav1dGetFrame just as when
    // a Surface is attached.
    // TODO: b/562381449 - Defer 10-bit pixel-layout validation in dav1dGetFrame to render time or
    // skip unsupported layouts when no output is attached.
    decoder.setOutputMode(C.VIDEO_OUTPUT_MODE_SURFACE_YUV);
  }

  @Override
  protected boolean supportsBufferAvailableCallback() {
    return true;
  }

  @Override
  protected void onOutputModeChanged(@C.VideoOutputMode int outputMode) {
    @Nullable Dav1dDecoder decoder = getDav1dDecoder();
    if (decoder != null) {
      decoder.setOutputMode(outputMode);
    }
  }

  @Override
  protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
      throws DecoderException {
    try {
      @Nullable Dav1dDecoder decoder = getDav1dDecoder();
      if (decoder == null) {
        throw new Dav1dDecoderException(
            "Failed to render output buffer to surface: decoder is not initialized.");
      }
      decoder.renderToSurface(outputBuffer, surface);
    } finally {
      outputBuffer.release();
    }
  }

  private @Nullable Dav1dDecoder getDav1dDecoder() {
    return (Dav1dDecoder) getDecoder();
  }
}
