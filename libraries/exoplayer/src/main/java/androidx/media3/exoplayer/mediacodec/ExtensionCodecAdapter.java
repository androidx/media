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
package androidx.media3.exoplayer.mediacodec;

import static androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.PersistableBundle;
import android.view.Surface;
import androidx.annotation.CallSuper;
import androidx.annotation.GuardedBy;
import androidx.annotation.RequiresApi;
import androidx.annotation.RestrictTo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.TimedValueQueue;
import androidx.media3.decoder.CryptoInfo;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.SimpleDecoderOutputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Base {@link MediaCodecAdapter} implementation that wraps a software/extension {@link Decoder}.
 *
 * <p>Provides shared buffer and format-queue management for extension decoders. Audio subclasses
 * producing {@link SimpleDecoderOutputBuffer} instances should either pass an initial {@link
 * Format} with the decoded PCM parameters ({@link Format#sampleRate}, {@link Format#channelCount},
 * and {@link Format#pcmEncoding}) to {@link #ExtensionCodecAdapter(Format, Decoder)} or override
 * {@link #getOutputFormat()} and {@link #maybeUpdateOutputFormat(DecoderOutputBuffer)} when decoded
 * output parameters differ from the input {@link Format}. Video subclasses should extend {@link
 * VideoExtensionCodecAdapter}.
 */
@RestrictTo(LIBRARY_GROUP)
public abstract class ExtensionCodecAdapter implements MediaCodecAdapter {

  /** Synchronization lock protecting adapter state. */
  /* package */ final Object lock;

  @GuardedBy("lock")
  private final TimedValueQueue<Format> formatQueue;

  @GuardedBy("lock")
  private Format outputFormat;

  @GuardedBy("lock")
  private @Nullable Format lastQueuedInputFormat;

  @GuardedBy("lock")
  private @Nullable
      Decoder<DecoderInputBuffer, ? extends DecoderOutputBuffer, ? extends DecoderException>
      decoder;

  @GuardedBy("lock")
  private @Nullable DecoderInputBuffer dequeuedInputBuffer;

  @GuardedBy("lock")
  private @Nullable DecoderOutputBuffer dequeuedOutputBuffer;

  @GuardedBy("lock")
  private boolean hasReturnedDequeuedOutputBufferIndex;

  @GuardedBy("lock")
  private boolean hasReturnedEndOfStreamOutputBufferIndex;

  @GuardedBy("lock")
  private boolean hasSentOutputFormatChanged;

  @GuardedBy("lock")
  private int skippedOutputBufferCount;

  @GuardedBy("lock")
  private long outputStartTimeUs;

  private volatile @Nullable OnBufferAvailableListener onBufferAvailableListener;

  /**
   * Creates a new instance.
   *
   * @param format The initial {@link Format} for decoding (including decoded PCM parameters for
   *     audio adapters that rely on the default {@link #getOutputFormat()} implementation).
   * @param decoder The underlying {@link Decoder} wrapped by this adapter.
   */
  protected ExtensionCodecAdapter(
      Format format,
      Decoder<DecoderInputBuffer, ? extends DecoderOutputBuffer, ? extends DecoderException>
          decoder) {
    this.lock = new Object();
    this.formatQueue = new TimedValueQueue<>();
    this.outputFormat = format;
    this.lastQueuedInputFormat = format;
    this.decoder = decoder;
    this.outputStartTimeUs = C.TIME_UNSET;
  }

  /**
   * Returns the underlying {@link ExtensionCodecAdapter} for {@code adapter} (unwrapping any {@link
   * ForwardingMediaCodecAdapter} decorators), or {@code null} if {@code adapter} is not an {@link
   * ExtensionCodecAdapter}.
   */
  public static @Nullable ExtensionCodecAdapter maybeUnwrap(@Nullable MediaCodecAdapter adapter) {
    while (adapter instanceof ForwardingMediaCodecAdapter) {
      adapter = ((ForwardingMediaCodecAdapter) adapter).getDelegate();
    }
    return adapter instanceof ExtensionCodecAdapter ? (ExtensionCodecAdapter) adapter : null;
  }

  @Override
  public final int dequeueInputBufferIndex() {
    synchronized (lock) {
      if (decoder == null) {
        return MediaCodec.INFO_TRY_AGAIN_LATER;
      }
      if (dequeuedInputBuffer == null) {
        try {
          dequeuedInputBuffer = decoder.dequeueInputBuffer();
        } catch (DecoderException e) {
          handleDecoderException(e);
        }
      }
      if (dequeuedInputBuffer != null) {
        dequeuedInputBuffer.clear();
        dequeuedInputBuffer.format = null;
        return 0;
      }
      return MediaCodec.INFO_TRY_AGAIN_LATER;
    }
  }

  @Override
  public final int dequeueOutputBufferIndex(MediaCodec.BufferInfo bufferInfo) {
    synchronized (lock) {
      if (decoder == null
          || (dequeuedOutputBuffer != null && hasReturnedDequeuedOutputBufferIndex)) {
        return MediaCodec.INFO_TRY_AGAIN_LATER;
      }
      hasReturnedEndOfStreamOutputBufferIndex = false;
      if (dequeuedOutputBuffer == null) {
        dequeuedOutputBuffer = dequeueNextOutputBuffer();
        if (dequeuedOutputBuffer == null) {
          return MediaCodec.INFO_TRY_AGAIN_LATER;
        }
        if (!dequeuedOutputBuffer.isEndOfStream()) {
          boolean formatChanged = maybeUpdateOutputFormat(dequeuedOutputBuffer);
          if (!hasSentOutputFormatChanged || formatChanged) {
            hasSentOutputFormatChanged = true;
            return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
          }
        }
      }
      DecoderOutputBuffer outputBuffer = dequeuedOutputBuffer;
      populateBufferInfo(outputBuffer, bufferInfo);
      if (outputBuffer.isEndOfStream()) {
        outputBuffer.release();
        dequeuedOutputBuffer = null;
        hasReturnedEndOfStreamOutputBufferIndex = true;
      } else {
        hasReturnedDequeuedOutputBufferIndex = true;
      }
      return 0;
    }
  }

  @Override
  public MediaFormat getOutputFormat() {
    synchronized (lock) {
      MediaFormat mediaFormat = new MediaFormat();
      if (outputFormat.sampleMimeType != null) {
        mediaFormat.setString(MediaFormat.KEY_MIME, outputFormat.sampleMimeType);
      }
      if (MimeTypes.isAudio(outputFormat.sampleMimeType)) {
        if (outputFormat.channelCount != Format.NO_VALUE) {
          mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, outputFormat.channelCount);
        }
        if (outputFormat.sampleRate != Format.NO_VALUE) {
          mediaFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, outputFormat.sampleRate);
        }
        if (outputFormat.pcmEncoding != Format.NO_VALUE) {
          mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, outputFormat.pcmEncoding);
        }
      }
      return mediaFormat;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p>Returns the {@link DecoderInputBuffer#data} of the currently dequeued input buffer, which
   * may be {@code null} or fixed-capacity if the underlying {@link Decoder} did not pre-allocate
   * it. {@link MediaCodecRenderer} populates input data and {@link DecoderInputBuffer#format}
   * directly via {@link #getDequeuedInputBuffer()} instead.
   */
  @Override
  public final @Nullable ByteBuffer getInputBuffer(int index) {
    synchronized (lock) {
      checkState(index == 0 && dequeuedInputBuffer != null);
      return checkNotNull(dequeuedInputBuffer).data;
    }
  }

  @Override
  public final @Nullable ByteBuffer getOutputBuffer(int index) {
    synchronized (lock) {
      checkState(
          index == 0
              && ((dequeuedOutputBuffer != null && hasReturnedDequeuedOutputBufferIndex)
                  || hasReturnedEndOfStreamOutputBufferIndex));
      if (dequeuedOutputBuffer instanceof SimpleDecoderOutputBuffer) {
        return ((SimpleDecoderOutputBuffer) dequeuedOutputBuffer).data;
      }
      return null;
    }
  }

  @Override
  public final void queueInputBuffer(
      int index, int offset, int size, long presentationTimeUs, int flags) {
    synchronized (lock) {
      checkState(index == 0 && dequeuedInputBuffer != null && decoder != null);
      DecoderInputBuffer dequeuedInputBuffer = checkNotNull(this.dequeuedInputBuffer);
      dequeuedInputBuffer.timeUs = presentationTimeUs;
      if (dequeuedInputBuffer.data != null) {
        dequeuedInputBuffer.data.limit(offset + size);
        dequeuedInputBuffer.data.position(offset);
      }
      if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
        dequeuedInputBuffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM);
      }
      if ((flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0) {
        dequeuedInputBuffer.addFlag(C.BUFFER_FLAG_KEY_FRAME);
      }
      if ((flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == 0
          && dequeuedInputBuffer.format != null
          && !dequeuedInputBuffer.format.equals(lastQueuedInputFormat)) {
        formatQueue.add(presentationTimeUs, dequeuedInputBuffer.format);
        lastQueuedInputFormat = dequeuedInputBuffer.format;
      }
      this.dequeuedInputBuffer = null;
      try {
        checkNotNull(decoder).queueInputBuffer(dequeuedInputBuffer);
      } catch (DecoderException e) {
        handleDecoderException(e);
      }
    }
  }

  @Override
  public final void queueSecureInputBuffer(
      int index, int offset, CryptoInfo info, long presentationTimeUs, int flags) {
    throw new UnsupportedOperationException(
        "Secure input buffers not supported by extension adapter");
  }

  @Override
  public final void releaseOutputBuffer(int index, boolean render) {
    releaseOutputBufferInternal(index, render, /* renderTimeStampNs= */ C.TIME_UNSET);
  }

  @Override
  public final void releaseOutputBuffer(int index, long renderTimeStampNs) {
    releaseOutputBufferInternal(index, /* render= */ true, renderTimeStampNs);
  }

  @Override
  public void setOutputSurface(Surface surface) {
    // No-op. Software extension codecs do not support output surfaces by default.
  }

  @Override
  public void detachOutputSurface() {
    // No-op. Software extension codecs do not support output surfaces by default.
  }

  @Override
  @CallSuper
  public void flush() {
    synchronized (lock) {
      formatQueue.clear();
      lastQueuedInputFormat = null;
      dequeuedInputBuffer = null;
      hasReturnedDequeuedOutputBufferIndex = false;
      hasReturnedEndOfStreamOutputBufferIndex = false;
      skippedOutputBufferCount = 0;
      outputStartTimeUs = C.TIME_UNSET;
      try {
        if (decoder != null) {
          // Flush the decoder before releasing dequeuedOutputBuffer so a background decode thread
          // is not woken by outputBuffer.release() while pre-flush input buffers are still queued.
          decoder.setOutputStartTimeUs(C.TIME_UNSET);
          decoder.flush();
        }
      } finally {
        if (dequeuedOutputBuffer != null) {
          DecoderOutputBuffer bufferToRelease = dequeuedOutputBuffer;
          dequeuedOutputBuffer = null;
          bufferToRelease.release();
        }
      }
    }
  }

  @Override
  @CallSuper
  public void release() {
    @Nullable Decoder<DecoderInputBuffer, ? extends DecoderOutputBuffer, ? extends DecoderException>
        decoderToRelease = null;
    @Nullable DecoderOutputBuffer bufferToRelease = null;
    boolean supportsCallback = false;
    try {
      synchronized (lock) {
        bufferToRelease = dequeuedOutputBuffer;
        dequeuedOutputBuffer = null;
        formatQueue.clear();
        lastQueuedInputFormat = null;
        dequeuedInputBuffer = null;
        hasReturnedDequeuedOutputBufferIndex = false;
        hasReturnedEndOfStreamOutputBufferIndex = false;
        hasSentOutputFormatChanged = false;
        skippedOutputBufferCount = 0;
        outputStartTimeUs = C.TIME_UNSET;
        onBufferAvailableListener = null;
        decoderToRelease = decoder;
        try {
          supportsCallback = decoderToRelease != null && supportsBufferAvailableCallback();
        } finally {
          decoder = null;
        }
      }
    } finally {
      try {
        try {
          if (decoderToRelease != null && supportsCallback) {
            decoderToRelease.setCallback(/* callback= */ null, /* executor= */ null);
          }
        } finally {
          if (bufferToRelease != null) {
            bufferToRelease.release();
          }
        }
      } finally {
        if (decoderToRelease != null) {
          decoderToRelease.release();
        }
      }
    }
  }

  @Override
  public final void setOnFrameRenderedListener(OnFrameRenderedListener listener, Handler handler) {
    // No-op for software extension codecs.
  }

  @Override
  public final void setParameters(Bundle params) {
    // No-op.
  }

  @Override
  public final void setVideoScalingMode(@C.VideoScalingMode int scalingMode) {
    // No-op.
  }

  @Override
  public final boolean needsReconfiguration() {
    return false;
  }

  @RequiresApi(26)
  @Override
  public final PersistableBundle getMetrics() {
    return new PersistableBundle();
  }

  @Override
  public final void subscribeToVendorParameters(List<String> names) {
    // No-op.
  }

  @Override
  public final void unsubscribeFromVendorParameters(List<String> names) {
    // No-op.
  }

  @Override
  @CallSuper
  public boolean registerOnBufferAvailableListener(OnBufferAvailableListener listener) {
    synchronized (lock) {
      Decoder<DecoderInputBuffer, ? extends DecoderOutputBuffer, ? extends DecoderException>
          decoder = this.decoder;
      if (decoder == null || !supportsBufferAvailableCallback()) {
        return false;
      }
      this.onBufferAvailableListener = listener;
      decoder.setCallback(
          new Decoder.Callback() {
            @Override
            public void onInputBufferAvailable() {
              notifyInputBufferAvailable();
            }

            @Override
            public void onOutputBufferAvailable() {
              notifyOutputBufferAvailable();
            }
          },
          directExecutor());
      return true;
    }
  }

  /**
   * Updates the output format based on a newly dequeued output buffer, if applicable.
   *
   * <p>The default implementation polls any queued input {@link Format} at or before {@code
   * outputBuffer.timeUs} and updates the current output {@link Format}, returning {@code true} if
   * the output {@link MediaFormat} properties changed.
   *
   * @param outputBuffer The newly dequeued output buffer.
   * @return Whether the output format changed and {@link MediaCodec#INFO_OUTPUT_FORMAT_CHANGED}
   *     should be returned before this buffer.
   */
  @CallSuper
  protected boolean maybeUpdateOutputFormat(DecoderOutputBuffer outputBuffer) {
    synchronized (lock) {
      @Nullable Format queuedFormat = formatQueue.pollFloor(outputBuffer.timeUs);
      if (queuedFormat == null) {
        return false;
      }
      Format previousFormat = outputFormat;
      outputFormat =
          queuedFormat.pcmEncoding == Format.NO_VALUE
                  && previousFormat.pcmEncoding != Format.NO_VALUE
              ? queuedFormat.buildUpon().setPcmEncoding(previousFormat.pcmEncoding).build()
              : queuedFormat;
      if (MimeTypes.isAudio(outputFormat.sampleMimeType)) {
        return outputFormat.channelCount != previousFormat.channelCount
            || outputFormat.sampleRate != previousFormat.sampleRate
            || outputFormat.pcmEncoding != previousFormat.pcmEncoding
            || !Objects.equals(outputFormat.sampleMimeType, previousFormat.sampleMimeType);
      }
      return !Objects.equals(outputFormat.sampleMimeType, previousFormat.sampleMimeType);
    }
  }

  /** Returns the current output {@link Format}. */
  protected final Format getCurrentOutputFormat() {
    synchronized (lock) {
      return outputFormat;
    }
  }

  /**
   * Returns whether the underlying {@link Decoder} supports {@link Decoder#setCallback}.
   *
   * <p>By default, returns {@code true} if the decoder is an instance of {@link SimpleDecoder}.
   * Subclasses wrapping non-{@link SimpleDecoder} implementations that support callbacks should
   * override this method to return {@code true}.
   */
  protected boolean supportsBufferAvailableCallback() {
    synchronized (lock) {
      return decoder instanceof SimpleDecoder;
    }
  }

  /** Returns the underlying {@link Decoder}, or {@code null} if the adapter has been released. */
  protected final @Nullable
      Decoder<DecoderInputBuffer, ? extends DecoderOutputBuffer, ? extends DecoderException>
      getDecoder() {
    synchronized (lock) {
      return decoder;
    }
  }

  /** Returns the currently dequeued {@link DecoderOutputBuffer}, or {@code null} if none. */
  protected final @Nullable DecoderOutputBuffer getDequeuedOutputBuffer() {
    synchronized (lock) {
      return dequeuedOutputBuffer;
    }
  }

  /**
   * Returns the number of output buffers skipped since the last call to this method, and resets the
   * count to zero.
   */
  /* package */ final int getAndResetSkippedOutputBufferCount() {
    synchronized (lock) {
      int count = skippedOutputBufferCount;
      skippedOutputBufferCount = 0;
      if (decoder != null) {
        count += decoder.getAndResetSkippedOutputBufferCount();
      }
      return count;
    }
  }

  /**
   * Updates the current output {@link Format}.
   *
   * @param outputFormat The updated output {@link Format}.
   */
  /* package */ final void setCurrentOutputFormat(Format outputFormat) {
    synchronized (lock) {
      this.outputFormat = outputFormat;
    }
  }

  /**
   * Notifies the registered {@link OnBufferAvailableListener} that an output buffer is ready to be
   * processed.
   */
  /* package */ final void notifyOutputBufferAvailable() {
    @Nullable OnBufferAvailableListener listener = this.onBufferAvailableListener;
    if (listener != null) {
      listener.onOutputBufferAvailable();
    }
  }

  /**
   * Renders the specified output buffer.
   *
   * <p>The default implementation delegates to {@link #renderOutputBuffer(DecoderOutputBuffer)}.
   * Implementations take ownership of {@code outputBuffer} and are responsible for calling {@link
   * DecoderOutputBuffer#release()} either immediately or in the future.
   *
   * @param outputBuffer The buffer to render.
   * @param renderTimeStampNs The wallclock time at which the buffer should be rendered, in
   *     nanoseconds, or {@link C#TIME_UNSET} if unset.
   */
  @SuppressWarnings("ApiWithNumericTimeUnit")
  /* package */ void renderOutputBuffer(DecoderOutputBuffer outputBuffer, long renderTimeStampNs) {
    renderOutputBuffer(outputBuffer);
  }

  /**
   * Renders the specified output buffer.
   *
   * <p>The default implementation releases the output buffer without rendering. Video subclasses
   * override this method to render the buffer to their output. Implementations take ownership of
   * {@code outputBuffer} and are responsible for calling {@link DecoderOutputBuffer#release()}
   * either immediately or in the future.
   *
   * @param outputBuffer The buffer to render.
   */
  /* package */ void renderOutputBuffer(DecoderOutputBuffer outputBuffer) {
    outputBuffer.release();
  }

  /**
   * Throws an {@link IllegalStateException} with {@code exception} as its {@linkplain
   * Throwable#getCause() cause}.
   *
   * <p>{@link MediaCodecRenderer} inspects the {@link DecoderException} cause on the thrown {@link
   * IllegalStateException} to classify it as a codec decoding error.
   *
   * @param exception The decoder exception.
   * @throws IllegalStateException Always thrown with {@code exception} as its cause.
   */
  /* package */ final void handleDecoderException(DecoderException exception) {
    throw new IllegalStateException("Internal extension decoder error", exception);
  }

  /** Returns whether an output buffer is currently dequeued and awaiting processing or release. */
  /* package */ final boolean hasDequeuedOutputBuffer() {
    synchronized (lock) {
      return dequeuedOutputBuffer != null;
    }
  }

  /**
   * Returns the currently dequeued {@link DecoderInputBuffer}.
   *
   * <p>{@link MediaCodecRenderer} reads sample data directly into this buffer (which dynamically
   * allocates or resizes {@link DecoderInputBuffer#data} via {@link
   * DecoderInputBuffer#ensureSpaceForWrite(int)}) and sets {@link DecoderInputBuffer#format} so
   * that {@link #queueInputBuffer(int, int, int, long, int)} can track input format changes.
   *
   * @return The currently dequeued {@link DecoderInputBuffer}.
   * @throws IllegalStateException If no input buffer is currently dequeued.
   */
  /* package */ final DecoderInputBuffer getDequeuedInputBuffer() {
    synchronized (lock) {
      checkState(dequeuedInputBuffer != null);
      return dequeuedInputBuffer;
    }
  }

  /**
   * Sets the start time of the output, in microseconds, so that the underlying {@link Decoder} can
   * mark or skip samples prior to this time as decode-only.
   *
   * @param outputStartTimeUs The output start time, in microseconds, or {@link C#TIME_UNSET} if
   *     unset.
   */
  /* package */ final void setOutputStartTimeUs(long outputStartTimeUs) {
    synchronized (lock) {
      this.outputStartTimeUs = outputStartTimeUs;
      if (dequeuedOutputBuffer != null
          && !hasReturnedDequeuedOutputBufferIndex
          && shouldSkipOutputBuffer(dequeuedOutputBuffer)) {
        skippedOutputBufferCount += 1 + dequeuedOutputBuffer.skippedOutputBufferCount;
        dequeuedOutputBuffer.release();
        dequeuedOutputBuffer = null;
      }
      if (decoder != null) {
        decoder.setOutputStartTimeUs(outputStartTimeUs);
      }
    }
  }

  private static void populateBufferInfo(
      DecoderOutputBuffer outputBuffer, MediaCodec.BufferInfo bufferInfo) {
    int flags = 0;
    if (outputBuffer.isEndOfStream()) {
      flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
    }
    if (outputBuffer.isKeyFrame()) {
      flags |= MediaCodec.BUFFER_FLAG_KEY_FRAME;
    }
    int offset = 0;
    int size = 0;
    if (!outputBuffer.isEndOfStream() && outputBuffer instanceof SimpleDecoderOutputBuffer) {
      @Nullable ByteBuffer data = ((SimpleDecoderOutputBuffer) outputBuffer).data;
      if (data != null) {
        offset = data.position();
        size = data.remaining();
      }
    }
    bufferInfo.set(
        /* newOffset= */ offset,
        /* newSize= */ size,
        /* newTimeUs= */ outputBuffer.timeUs,
        /* newFlags= */ flags);
  }

  @GuardedBy("lock")
  private @Nullable DecoderOutputBuffer dequeueNextOutputBuffer() {
    try {
      @Nullable DecoderOutputBuffer outputBuffer;
      while ((outputBuffer = checkNotNull(decoder).dequeueOutputBuffer()) != null) {
        // Output buffers skipped internally by the underlying Decoder (e.g. SimpleDecoder or
        // Dav1dDecoder) are accumulated on the decoder thread and attached to the next
        // non-skipped (or end-of-stream) output buffer.
        skippedOutputBufferCount += outputBuffer.skippedOutputBufferCount;
        outputBuffer.skippedOutputBufferCount = 0;
        if (!shouldSkipOutputBuffer(outputBuffer)) {
          return outputBuffer;
        }
        skippedOutputBufferCount++;
        outputBuffer.release();
      }
    } catch (DecoderException e) {
      handleDecoderException(e);
    }
    return null;
  }

  @GuardedBy("lock")
  private boolean shouldSkipOutputBuffer(DecoderOutputBuffer outputBuffer) {
    return !outputBuffer.isEndOfStream()
        && (outputBuffer.shouldBeSkipped
            || (outputStartTimeUs != C.TIME_UNSET
                && outputBuffer.timeUs < outputStartTimeUs
                && !(outputBuffer instanceof VideoDecoderOutputBuffer
                    && outputBuffer.isLastSample())));
  }

  private void notifyInputBufferAvailable() {
    @Nullable OnBufferAvailableListener listener = this.onBufferAvailableListener;
    if (listener != null) {
      listener.onInputBufferAvailable();
    }
  }

  private void releaseOutputBufferInternal(int index, boolean render, long renderTimeStampNs) {
    DecoderOutputBuffer outputBufferToRelease;
    synchronized (lock) {
      if (index == 0 && dequeuedOutputBuffer == null && hasReturnedEndOfStreamOutputBufferIndex) {
        hasReturnedEndOfStreamOutputBufferIndex = false;
        return;
      }
      checkState(
          index == 0 && dequeuedOutputBuffer != null && hasReturnedDequeuedOutputBufferIndex);
      outputBufferToRelease = dequeuedOutputBuffer;
      dequeuedOutputBuffer = null;
      hasReturnedDequeuedOutputBufferIndex = false;
    }
    if (render) {
      renderOutputBuffer(outputBufferToRelease, renderTimeStampNs);
    } else {
      outputBufferToRelease.release();
    }
  }
}
