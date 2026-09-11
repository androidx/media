/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.audio;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.truth.Truth.assertThat;
import static java.lang.Integer.MAX_VALUE;
import static java.lang.Math.min;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.media3.common.util.Util;
import com.google.common.collect.Range;
import com.google.testing.junit.testparameterinjector.TestParameter;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestParameterInjector;

/** Unit tests for {@link SilenceSkippingAudioProcessor}. */
@RunWith(RobolectricTestParameterInjector.class)
public final class SilenceSkippingAudioProcessorTest {

  /**
   * Represents the {@link C.PcmEncoding} for the parameterized test.
   *
   * <p>Can be one of:
   *
   * <ul>
   *   <li>{@link C#ENCODING_PCM_8BIT}
   *   <li>{@link C#ENCODING_PCM_16BIT}
   *   <li>{@link C#ENCODING_PCM_16BIT_BIG_ENDIAN}
   *   <li>{@link C#ENCODING_PCM_24BIT}
   *   <li>{@link C#ENCODING_PCM_24BIT_BIG_ENDIAN}
   *   <li>{@link C#ENCODING_PCM_32BIT}
   *   <li>{@link C#ENCODING_PCM_32BIT_BIG_ENDIAN}
   *   <li>{@link C#ENCODING_PCM_FLOAT}
   *   <li>{@link C#ENCODING_PCM_FLOAT_BIG_ENDIAN}
   *   <li>{@link C#ENCODING_PCM_DOUBLE}
   *   <li>{@link C#ENCODING_PCM_DOUBLE_BIG_ENDIAN}
   * </ul>
   */
  @TestParameter({
    "3",
    "2",
    "268435456",
    "21",
    "1342177280",
    "22",
    "1610612736",
    "4",
    "1895825408",
    "1879048192",
    "1912602624"
  })
  private @C.PcmEncoding int pcmEncoding;

  private static final int TEST_SIGNAL_SILENCE_DURATION_MS = 1000;
  private static final int TEST_SIGNAL_NOISE_DURATION_MS = 1000;
  private static final int TEST_SIGNAL_FRAME_COUNT = 100_000;

  private int inputBufferSize;
  private AudioFormat audioFormat;
  private SilenceSkippingAudioProcessor silenceSkippingAudioProcessor;

  @Before
  public void setUp() {
    inputBufferSize = 50 * Util.getByteDepth(pcmEncoding);
    audioFormat =
        new AudioFormat(/* sampleRate= */ 1000, /* channelCount= */ 2, /* encoding= */ pcmEncoding);
    silenceSkippingAudioProcessor = new SilenceSkippingAudioProcessor();
  }

  @Test
  public void enabledProcessor_isActive() throws Exception {
    // Given an enabled processor.
    silenceSkippingAudioProcessor.setEnabled(true);

    // When configuring it.
    silenceSkippingAudioProcessor.configure(audioFormat);

    // It's active.
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
  }

  @Test
  public void disabledProcessor_isNotActive() throws Exception {
    // Given a disabled processor.
    silenceSkippingAudioProcessor.setEnabled(false);

    // When configuring it.
    silenceSkippingAudioProcessor.configure(audioFormat);

    // It's not active.
    assertThat(silenceSkippingAudioProcessor.isActive()).isFalse();
  }

  @Test
  public void defaultProcessor_isNotEnabled() throws Exception {
    // Given a processor in its default state.
    // When reconfigured.
    silenceSkippingAudioProcessor.configure(audioFormat);

    // It's not active.
    assertThat(silenceSkippingAudioProcessor.isActive()).isFalse();
  }

  @Test
  public void skipInSilentSignal_skipsEverything() throws Exception {
    // Given a signal with only silence.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS, /* noiseDurationMs= */ 0, TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal.
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(silenceSkippingAudioProcessor, inputBufferProvider, inputBufferSize);

    // The entire signal is skipped except for the DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US.
    assertThat(totalOutputFrames).isEqualTo(2000);
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames())
        .isEqualTo(TEST_SIGNAL_FRAME_COUNT - 2000);
  }

  @Test
  public void skipInNoisySignal_skipsNothing() throws Exception {
    // Given a signal with only noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            /* silenceDurationMs= */ 0, TEST_SIGNAL_NOISE_DURATION_MS, TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(silenceSkippingAudioProcessor, inputBufferProvider, inputBufferSize);

    // None of the signal is skipped.
    assertThat(totalOutputFrames).isEqualTo(TEST_SIGNAL_FRAME_COUNT);
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames()).isEqualTo(0);
  }

  @Test
  public void skipInNoisySignalWithShortSilences_skipsNothing() throws Exception {
    // Given a signal with only noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            /* silenceDurationMs= */ 30,
            TEST_SIGNAL_NOISE_DURATION_MS - 30,
            TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(silenceSkippingAudioProcessor, inputBufferProvider, inputBufferSize);

    // None of the signal is skipped.
    assertThat(totalOutputFrames).isEqualTo(TEST_SIGNAL_FRAME_COUNT);
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames()).isEqualTo(0);
  }

  @Test
  public void skipInAlternatingTestSignal_hasCorrectOutputAndSkippedFrameCounts() throws Exception {
    // Given a signal that alternates between silence and noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS,
            TEST_SIGNAL_NOISE_DURATION_MS,
            TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(silenceSkippingAudioProcessor, inputBufferProvider, inputBufferSize);

    // The output has 50000 frames of noise, plus 50 * (100 + 0.2 * 900) frames of silence (plus
    // rounding errors).
    assertThat(totalOutputFrames).isIn(Range.closed(64000L - 1500L, 64000L + 1500L));
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames())
        .isEqualTo(TEST_SIGNAL_FRAME_COUNT - totalOutputFrames);
  }

  @Test
  public void
      skipInAlternatingTestSignal_withEarlyConfigureForNextFormat_hasCorrectOutputAndSkippedFrameCounts()
          throws Exception {
    // Given a signal that alternates between silence and noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS,
            TEST_SIGNAL_NOISE_DURATION_MS,
            TEST_SIGNAL_FRAME_COUNT);
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);

    // Early configure the next format without flushing yet (this format should be ignored).
    silenceSkippingAudioProcessor.configure(
        new AudioFormat(
            /* sampleRate= */ 1000, /* channelCount= */ 1, /* encoding= */ pcmEncoding));
    long totalOutputFrames =
        process(silenceSkippingAudioProcessor, inputBufferProvider, inputBufferSize);

    // The output has 50000 frames of noise, plus 50 * (100 + 0.2 * 900) frames of silence (plus
    // rounding errors).
    assertThat(totalOutputFrames).isIn(Range.closed(64000L - 1500L, 64000L + 1500L));
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames())
        .isEqualTo(TEST_SIGNAL_FRAME_COUNT - totalOutputFrames);
  }

  @Test
  public void skipWithSmallerInputBufferSize_hasCorrectOutputAndSkippedFrameCounts()
      throws Exception {
    // Given a signal that alternates between silence and noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS,
            TEST_SIGNAL_NOISE_DURATION_MS,
            TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal with a smaller input buffer size.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(
            silenceSkippingAudioProcessor,
            inputBufferProvider,
            /* inputBufferSize= */ 40 * Util.getByteDepth(pcmEncoding));

    // The output has 50000 frames of noise, plus 50 * (100 + 0.2 * 900) frames of silence (plus
    // rounding errors).
    assertThat(totalOutputFrames).isIn(Range.closed(64000L - 1500L, 64000L + 1500L));
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames())
        .isEqualTo(TEST_SIGNAL_FRAME_COUNT - totalOutputFrames);
  }

  @Test
  public void skipWithLargerInputBufferSize_hasCorrectOutputAndSkippedFrameCounts()
      throws Exception {
    // Given a signal that alternates between silence and noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS,
            TEST_SIGNAL_NOISE_DURATION_MS,
            TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal with a larger input buffer size.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(
            silenceSkippingAudioProcessor,
            inputBufferProvider,
            /* inputBufferSize= */ 60 * Util.getByteDepth(pcmEncoding));

    // The output has 50000 frames of noise, plus 50 * (100 + 0.2 * 900) frames of silence (plus
    // rounding errors).
    assertThat(totalOutputFrames).isIn(Range.closed(64000L - 1500L, 64000L + 1500L));
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames())
        .isEqualTo(TEST_SIGNAL_FRAME_COUNT - totalOutputFrames);
  }

  @Test
  public void customSilenceRetentionValue_hasCorrectOutputAndSkippedFrameCounts() throws Exception {
    // Given a signal that alternates between silence and noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS,
            TEST_SIGNAL_NOISE_DURATION_MS,
            TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal with a smaller than normal retention ratio.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor(
            SilenceSkippingAudioProcessor.DEFAULT_MINIMUM_SILENCE_DURATION_US,
            /* silenceRetentionRatio= */ 0.05f,
            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL);
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    long totalOutputFrames =
        process(
            silenceSkippingAudioProcessor,
            inputBufferProvider,
            /* inputBufferSize= */ 60 * Util.getByteDepth(pcmEncoding));

    // The output has 50000 frames of noise, plus 50 * (100 + 0.05 * 900) frames of silence (plus
    // rounding errors).
    assertThat(totalOutputFrames).isIn(Range.closed(56800L - 1500L, 56800L + 1500L));
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames())
        .isEqualTo(TEST_SIGNAL_FRAME_COUNT - totalOutputFrames);
  }

  @Test
  public void skipThenFlush_resetsSkippedFrameCount() throws Exception {
    // Given a signal that alternates between silence and noise.
    InputBufferProvider inputBufferProvider =
        getInputBufferProviderForAlternatingSilenceAndNoise(
            TEST_SIGNAL_SILENCE_DURATION_MS,
            TEST_SIGNAL_NOISE_DURATION_MS,
            TEST_SIGNAL_FRAME_COUNT);

    // When processing the entire signal then flushing.
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);
    assertThat(silenceSkippingAudioProcessor.isActive()).isTrue();
    process(silenceSkippingAudioProcessor, inputBufferProvider, inputBufferSize);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);

    // The skipped frame count is zero.
    assertThat(silenceSkippingAudioProcessor.getSkippedFrames()).isEqualTo(0);
  }

  @Test
  public void process_withSingleFrameFadeBuffer_processesWithoutError() throws Exception {
    // 2ms at 1000Hz results in a 2-frame buffer (1-frame fade out/in, max == 0).
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor(
            /* minimumSilenceDurationUs= */ 2_000,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_RETENTION_RATIO,
            SilenceSkippingAudioProcessor.DEFAULT_MAX_SILENCE_TO_KEEP_DURATION_US,
            SilenceSkippingAudioProcessor.DEFAULT_MIN_VOLUME_TO_KEEP_PERCENTAGE,
            SilenceSkippingAudioProcessor.DEFAULT_SILENCE_THRESHOLD_LEVEL);
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);

    // All samples are in 8-bit signed PCM to ensure rounding errors while converting to 8-bit and
    // back don't cause test failures, but still testing exact values.
    // Provide 1 frame of noise (78), 2 frames of silence (3), and 1 frame of noise (78).
    ByteBuffer inputBuffer =
        ByteBuffer.allocate(4 * audioFormat.bytesPerFrame).order(ByteOrder.nativeOrder());
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 78 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 78 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 3 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 3 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 3 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 3 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 78 << 24, pcmEncoding);
    PcmAudioUtil.write32BitIntPcm(inputBuffer, 78 << 24, pcmEncoding);
    inputBuffer.flip();

    ByteArrayOutputStream outStream = new ByteArrayOutputStream();
    while (inputBuffer.hasRemaining()) {
      silenceSkippingAudioProcessor.queueInput(inputBuffer);
      ByteBuffer outputBuffer = silenceSkippingAudioProcessor.getOutput();
      byte[] bytes = new byte[outputBuffer.remaining()];
      outputBuffer.get(bytes);
      outStream.write(bytes);
    }
    silenceSkippingAudioProcessor.queueEndOfStream();
    while (!silenceSkippingAudioProcessor.isEnded()) {
      ByteBuffer outputBuffer = silenceSkippingAudioProcessor.getOutput();
      byte[] bytes = new byte[outputBuffer.remaining()];
      outputBuffer.get(bytes);
      outStream.write(bytes);
    }

    ByteBuffer outputBuffer =
        ByteBuffer.wrap(outStream.toByteArray()).order(ByteOrder.nativeOrder());

    // 1st frame: Noise (78)
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding)).isEqualTo(78 << 24);
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding)).isEqualTo(78 << 24);

    // 2nd frame: 1-frame Fade-Out with max == 0 (scaled to 10% of 3 = 0.3)
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding))
        .isIn(Range.closed(0, 1 << 24));
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding))
        .isIn(Range.closed(0, 1 << 24));

    // 3rd frame: 1-frame Fade-In with max == 0 (scaled to 100% of 3 = 3)
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding)).isEqualTo(3 << 24);
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding)).isEqualTo(3 << 24);

    // 4th frame: Noise (78)
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding)).isEqualTo(78 << 24);
    assertThat(PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding)).isEqualTo(78 << 24);
  }

  @Test
  public void queueInput_stereoFadedSilence_scalesBothChannelsEqually() throws Exception {
    SilenceSkippingAudioProcessor silenceSkippingAudioProcessor =
        new SilenceSkippingAudioProcessor();
    silenceSkippingAudioProcessor.setEnabled(true);
    silenceSkippingAudioProcessor.configure(audioFormat);
    silenceSkippingAudioProcessor.flush(StreamMetadata.DEFAULT);

    // Create a buffer with noise followed by silence (with non-zero sub-threshold level 2 on both
    // L and R).
    int frameCount = 2000;
    ByteBuffer inputBuffer =
        ByteBuffer.allocate(frameCount * audioFormat.bytesPerFrame).order(ByteOrder.nativeOrder());
    for (int i = 0; i < 500; i++) {
      PcmAudioUtil.write32BitIntPcm(inputBuffer, MAX_VALUE, pcmEncoding);
      PcmAudioUtil.write32BitIntPcm(inputBuffer, MAX_VALUE, pcmEncoding);
    }
    for (int i = 0; i < 1500; i++) {
      PcmAudioUtil.write32BitIntPcm(inputBuffer, 2 << 24, pcmEncoding);
      PcmAudioUtil.write32BitIntPcm(inputBuffer, 2 << 24, pcmEncoding);
    }
    inputBuffer.flip();

    silenceSkippingAudioProcessor.queueInput(inputBuffer);
    silenceSkippingAudioProcessor.queueEndOfStream();

    ByteBuffer outputBuffer = silenceSkippingAudioProcessor.getOutput();
    while (outputBuffer.hasRemaining()) {
      int leftChannel = PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding);
      int rightChannel = PcmAudioUtil.readAs32BitIntPcm(outputBuffer, pcmEncoding);
      assertThat(leftChannel).isEqualTo(rightChannel);
    }
  }

  /**
   * Processes the entire stream provided by {@code inputBufferProvider} in chunks of {@code
   * inputBufferSize} and returns the total number of output frames.
   */
  private long process(
      SilenceSkippingAudioProcessor processor,
      InputBufferProvider inputBufferProvider,
      int inputBufferSize) {
    int bytesPerFrame = audioFormat.bytesPerFrame;
    long totalOutputFrames = 0;
    while (inputBufferProvider.hasRemaining()) {
      ByteBuffer inputBuffer = inputBufferProvider.getNextInputBuffer(inputBufferSize);
      while (inputBuffer.hasRemaining()) {
        processor.queueInput(inputBuffer);
        ByteBuffer outputBuffer = processor.getOutput();
        totalOutputFrames += outputBuffer.remaining() / bytesPerFrame;
        outputBuffer.clear();
      }
    }
    processor.queueEndOfStream();
    while (!processor.isEnded()) {
      ByteBuffer outputBuffer = processor.getOutput();
      totalOutputFrames += outputBuffer.remaining() / bytesPerFrame;
      outputBuffer.clear();
    }
    return totalOutputFrames;
  }

  /**
   * Returns an {@link InputBufferProvider} that provides input buffers for a stream that alternates
   * between silence/noise of the specified durations to fill {@code totalFrameCount}.
   */
  private InputBufferProvider getInputBufferProviderForAlternatingSilenceAndNoise(
      int silenceDurationMs, int noiseDurationMs, int totalFrameCount) {
    int sampleRate = audioFormat.sampleRate;
    int channelCount = audioFormat.channelCount;
    PcmAudioBuilder audioBuilder = new PcmAudioBuilder(channelCount, totalFrameCount, pcmEncoding);
    while (!audioBuilder.isFull()) {
      int silenceDurationFrames = (silenceDurationMs * sampleRate) / 1000;
      // Append stereo silence.
      audioBuilder.appendFrames(/* count= */ silenceDurationFrames, /* channelLevels...= */ 0, 0);
      int noiseDurationFrames = (noiseDurationMs * sampleRate) / 1000;
      // Append stereo noise.
      audioBuilder.appendFrames(
          /* count= */ noiseDurationFrames, /* channelLevels...= */ MAX_VALUE, MAX_VALUE);
    }
    ByteBuffer buffer = audioBuilder.build();
    assertThat(buffer.remaining())
        .isEqualTo(totalFrameCount * channelCount * Util.getByteDepth(pcmEncoding));
    return new InputBufferProvider(buffer);
  }

  /**
   * Wraps a {@link ByteBuffer} and provides a sequence of {@link ByteBuffer}s of specified sizes
   * that contain copies of its data.
   */
  private static final class InputBufferProvider {

    private final ByteBuffer buffer;

    public InputBufferProvider(ByteBuffer buffer) {
      this.buffer = buffer;
    }

    /** Returns the next buffer with size up to {@code sizeBytes}. */
    public ByteBuffer getNextInputBuffer(int sizeBytes) {
      ByteBuffer inputBuffer = ByteBuffer.allocate(sizeBytes).order(ByteOrder.nativeOrder());
      int limit = buffer.limit();
      buffer.limit(min(buffer.position() + sizeBytes, limit));
      inputBuffer.put(buffer);
      buffer.limit(limit);
      inputBuffer.flip();
      return inputBuffer;
    }

    /** Returns whether any more input can be provided via {@link #getNextInputBuffer(int)}. */
    public boolean hasRemaining() {
      return buffer.hasRemaining();
    }
  }

  /** Builder for {@link ByteBuffer}s that contain linear PCM audio samples. */
  private static final class PcmAudioBuilder {

    private final int channelCount;
    private final @C.PcmEncoding int pcmEncoding;
    private final ByteBuffer buffer;

    private boolean built;

    public PcmAudioBuilder(int channelCount, int frameCount, @C.PcmEncoding int pcmEncoding) {
      this.channelCount = channelCount;
      this.pcmEncoding = pcmEncoding;
      buffer =
          ByteBuffer.allocate(frameCount * channelCount * Util.getByteDepth(pcmEncoding))
              .order(ByteOrder.nativeOrder());
    }

    /**
     * Appends {@code count} audio frames, using the specified {@code channelLevels} in each frame.
     */
    public void appendFrames(int count, int... channelLevels) {
      checkState(!built);
      checkState(channelLevels.length == channelCount);
      for (int i = 0; i < count; i++) {
        for (int channelLevel : channelLevels) {
          PcmAudioUtil.write32BitIntPcm(buffer, channelLevel, pcmEncoding);
        }
      }
    }

    /** Returns whether the buffer is full. */
    public boolean isFull() {
      checkState(!built);
      return !buffer.hasRemaining();
    }

    /** Returns the built buffer. After calling this method the builder should not be reused. */
    public ByteBuffer build() {
      checkState(!built);
      built = true;
      buffer.flip();
      return buffer;
    }
  }
}
