/*
 * Copyright 2022 The Android Open Source Project
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.Util;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link AudioProcessor} implementation that appends silence to a stream if the stream is shorter
 * than a {@linkplain #setExpectedDurationUs(long) specified duration}.
 *
 * <p>After a call to {@link #queueEndOfStream()}, this processor appends silence to cover any
 * difference between the input stream duration and the expected duration. If the input stream
 * duration is larger than the expected duration, no silence is appended and the stream is not
 * truncated.
 *
 * <p>By default, the expected duration is {@link C#TIME_UNSET}, which signals to not append
 * silence.
 *
 * <p>This processor does not support seeking, so {@link #flush(StreamMetadata)} must not be called
 * with a non-zero {@linkplain StreamMetadata#positionOffsetUs position offset} after {@linkplain
 * #setExpectedDurationUs(long) setting a non-default duration}.
 */
/* package */ final class SilenceAppendingAudioProcessor implements AudioProcessor {
  private static final int DEFAULT_BUFFER_SIZE_FRAMES = 4096;

  private long expectedDurationUs;
  private long pendingExpectedDurationUs;
  private AudioFormat audioFormat;
  private AudioFormat pendingAudioFormat;
  private ByteBuffer passthroughBuffer;
  private ByteBuffer silenceBuffer;
  private ByteBuffer outputBuffer;

  /** Number of input or silence frames copied to the processor's output. */
  private long framesCopiedToOutput;

  private long expectedFrameCount;
  private boolean hasReceivedEndOfStream;

  public SilenceAppendingAudioProcessor() {
    this.audioFormat = AudioFormat.NOT_SET;
    this.pendingAudioFormat = AudioFormat.NOT_SET;
    this.expectedDurationUs = C.TIME_UNSET;
    this.pendingExpectedDurationUs = C.TIME_UNSET;
    passthroughBuffer = AudioProcessor.EMPTY_BUFFER;
    silenceBuffer = AudioProcessor.EMPTY_BUFFER;
    outputBuffer = AudioProcessor.EMPTY_BUFFER;
  }

  @Override
  public AudioFormat configure(AudioFormat inputAudioFormat) throws UnhandledAudioFormatException {
    if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
      throw new UnhandledAudioFormatException(inputAudioFormat);
    }
    pendingAudioFormat = inputAudioFormat;
    return inputAudioFormat;
  }

  /**
   * {@inheritDoc}
   *
   * <p>This processor is active when a valid input format has been {@linkplain #configure
   * configured} and the current expected duration is not {@link C#TIME_UNSET}.
   */
  @Override
  public boolean isActive() {
    return !audioFormat.equals(AudioFormat.NOT_SET) && expectedDurationUs != C.TIME_UNSET;
  }

  @Override
  public void queueInput(ByteBuffer inputBuffer) {
    if (!inputBuffer.hasRemaining()) {
      return;
    }
    framesCopiedToOutput += inputBuffer.remaining() / audioFormat.bytesPerFrame;
    ensureCapacityAvailableInPassthroughBuffer(inputBuffer.remaining());
    passthroughBuffer.put(inputBuffer).flip();
    outputBuffer = passthroughBuffer;
  }

  @Override
  public void queueEndOfStream() {
    hasReceivedEndOfStream = true;
  }

  @Override
  public ByteBuffer getOutput() {
    if (!hasReceivedEndOfStream
        || outputBuffer.hasRemaining()
        || framesCopiedToOutput >= expectedFrameCount) {
      ByteBuffer tempBuffer = outputBuffer;
      outputBuffer = EMPTY_BUFFER;
      return tempBuffer;
    }

    populateSilenceBuffer((expectedFrameCount - framesCopiedToOutput) * audioFormat.bytesPerFrame);
    return silenceBuffer;
  }

  @Override
  public boolean isEnded() {
    return hasReceivedEndOfStream
        && framesCopiedToOutput >= expectedFrameCount
        && !outputBuffer.hasRemaining();
  }

  @Override
  public void flush(StreamMetadata streamMetadata) {
    audioFormat = pendingAudioFormat;
    expectedDurationUs = pendingExpectedDurationUs;
    expectedFrameCount = Util.durationUsToSampleCount(expectedDurationUs, audioFormat.sampleRate);
    framesCopiedToOutput = 0;
    silenceBuffer = EMPTY_BUFFER;
    passthroughBuffer = EMPTY_BUFFER;
    outputBuffer = EMPTY_BUFFER;
    hasReceivedEndOfStream = false;
    // SilenceAppendingAudioProcessor does not support seeking and is not meant to be used with
    // CompositionPlayer.
    checkState(!isActive() || streamMetadata.positionOffsetUs == 0);
  }

  @Override
  public void reset() {
    pendingAudioFormat = AudioFormat.NOT_SET;
    audioFormat = AudioFormat.NOT_SET;
    expectedFrameCount = 0;
    expectedDurationUs = C.TIME_UNSET;
    pendingExpectedDurationUs = C.TIME_UNSET;
    framesCopiedToOutput = 0;
    silenceBuffer = EMPTY_BUFFER;
    passthroughBuffer = EMPTY_BUFFER;
    hasReceivedEndOfStream = false;
  }

  /**
   * Sets the expected duration of the stream in microseconds to be applied after the next {@link
   * #flush()}.
   *
   * <p>The default duration is {@link C#TIME_UNSET}, which does not append any silence to the
   * stream.
   */
  public void setExpectedDurationUs(long expectedDurationUs) {
    checkArgument(expectedDurationUs >= 0 || expectedDurationUs == C.TIME_UNSET);
    this.pendingExpectedDurationUs = expectedDurationUs;
  }

  private void populateSilenceBuffer(long bytes) {
    checkState(bytes >= 0);
    if (silenceBuffer.capacity() == 0) {
      silenceBuffer =
          ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE_FRAMES * audioFormat.bytesPerFrame)
              .order(ByteOrder.nativeOrder());
    }
    silenceBuffer.clear();
    if (bytes < silenceBuffer.capacity()) {
      silenceBuffer.limit((int) bytes);
    }
    framesCopiedToOutput += silenceBuffer.remaining() / audioFormat.bytesPerFrame;
  }

  private void ensureCapacityAvailableInPassthroughBuffer(int size) {
    if (passthroughBuffer.capacity() < size) {
      passthroughBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    } else {
      passthroughBuffer.clear();
    }
  }
}
