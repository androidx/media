/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.common.audio;

import static androidx.media3.common.audio.AudioProcessor.EMPTY_BUFFER;
import static androidx.media3.test.utils.TestUtil.createByteBuffer;
import static androidx.media3.test.utils.TestUtil.createFloatArray;
import static androidx.media3.test.utils.TestUtil.createShortArray;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import androidx.media3.common.audio.DefaultGainProvider.FadeProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link GainProcessor}. */
@RunWith(AndroidJUnit4.class)
public class GainProcessorTest {

  private static final FadeProvider CONSTANT_VALUE_FADE = (index, duration) -> 0.5f;

  private static final DefaultGainProvider HUNDRED_US_FADE_IN_PROVIDER =
      new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
          .addFadeAt(
              /* positionUs= */ 0L, /* durationUs= */ 100, DefaultGainProvider.FADE_IN_LINEAR)
          .build();

  private static final DefaultGainProvider HUNDRED_US_FADE_OUT_PROVIDER =
      new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
          .addFadeAt(
              /* positionUs= */ 0L, /* durationUs= */ 100, DefaultGainProvider.FADE_OUT_LINEAR)
          .build();

  private static final AudioFormat MONO_50KHZ_16BIT_FORMAT =
      new AudioFormat(/* sampleRate= */ 50000, /* channelCount= */ 1, C.ENCODING_PCM_16BIT);
  private static final AudioFormat MONO_100KHZ_16BIT_FORMAT =
      new AudioFormat(/* sampleRate= */ 100000, /* channelCount= */ 1, C.ENCODING_PCM_16BIT);

  private static final AudioFormat MONO_50KHZ_FLOAT_FORMAT =
      new AudioFormat(/* sampleRate= */ 50000, /* channelCount= */ 1, C.ENCODING_PCM_FLOAT);

  @Test
  public void applyGain_withMutingGainProvider_returnsAllZeroes()
      throws UnhandledAudioFormatException {
    GainProcessor processor =
        new GainProcessor(new DefaultGainProvider.Builder(/* defaultGain= */ 0f).build());
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new short[] {1, 1, 1, 1, 1, 1, 1, 1, 1, 1});
    processor.queueInput(input);

    ByteBuffer output = processor.getOutput();
    assertThat(output.remaining()).isEqualTo(20);
    while (output.hasRemaining()) {
      assertThat(output.getShort()).isEqualTo(0);
    }
  }

  @Test
  public void applyGain_withFadeIn_returnsScaledSamples() throws UnhandledAudioFormatException {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_IN_PROVIDER);
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new short[] {100, 100, 100, 100, 100, 100, 100});
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    short[] outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {0, 20, 40, 60, 80, 100, 100});
  }

  @Test
  public void applyGain_withFadeOut_returnsScaledSamples() throws UnhandledAudioFormatException {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_OUT_PROVIDER);
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new short[] {100, 100, 100, 100, 100, 100});
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    short[] outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {100, 80, 60, 40, 20, 100});
  }

  @Test
  public void applyGain_withFloatSamples_returnsScaledSamples()
      throws UnhandledAudioFormatException {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_IN_PROVIDER);
    processor.configure(MONO_50KHZ_FLOAT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new float[] {1, 1, 1, 1, 1, 1, 1});
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    float[] outputSamples = createFloatArray(output);
    assertThat(outputSamples).isEqualTo(new float[] {0f, 0.2f, 0.4f, 0.6f, 0.8f, 1f, 1f});
  }

  @Test
  public void applyGain_afterSampleRateChange_stretchesFade() throws UnhandledAudioFormatException {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_IN_PROVIDER);
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new short[] {100, 100, 100, 100, 100, 100, 100});
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    short[] outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {0, 20, 40, 60, 80, 100, 100});

    processor.configure(MONO_100KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);
    input.rewind();
    processor.queueInput(input);
    output.clear();
    output = processor.getOutput();

    outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {0, 10, 20, 30, 40, 50, 60});
  }

  @Test
  public void applyGain_withMultipleQueueInputCalls_appliesGainAtCorrectPosition()
      throws UnhandledAudioFormatException {
    GainProcessor processor =
        new GainProcessor(
            new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
                .addFadeAt(/* positionUs= */ 100, /* durationUs= */ 100, CONSTANT_VALUE_FADE)
                .build());
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new short[] {100, 100, 100, 100, 100});
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    short[] outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {100, 100, 100, 100, 100});

    input.rewind();
    processor.queueInput(input);
    output.clear();
    output = processor.getOutput();

    outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {50, 50, 50, 50, 50});

    input.rewind();
    processor.queueInput(input);
    output.clear();
    output = processor.getOutput();

    outputSamples = createShortArray(output);
    assertThat(outputSamples).isEqualTo(new short[] {100, 100, 100, 100, 100});
  }

  @Test
  public void applyGain_withSingleQueueInputCall_appliesGainAtCorrectPosition()
      throws UnhandledAudioFormatException {
    GainProcessor processor =
        new GainProcessor(
            new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
                .addFadeAt(/* positionUs= */ 100, /* durationUs= */ 100, CONSTANT_VALUE_FADE)
                .build());
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    // 15 mono frames set to 100.
    ByteBuffer input =
        createByteBuffer(
            new short[] {
              100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100
            });
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    short[] outputSamples = createShortArray(output);
    // 5 frames at unity + 5 frames with gain 0.5 (100 * 0.5 = 50) + 5 frames with at unity.
    assertThat(outputSamples)
        .isEqualTo(
            new short[] {100, 100, 100, 100, 100, 50, 50, 50, 50, 50, 100, 100, 100, 100, 100});
  }

  @Test
  public void isEnded_afterQueueEndOfStreamWithNoPendingOutput_returnsTrue()
      throws UnhandledAudioFormatException {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_IN_PROVIDER);
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    ByteBuffer input = createByteBuffer(new short[] {100, 100, 100, 100, 100, 100, 100});
    processor.queueInput(input);
    processor.queueEndOfStream();

    assertThat(processor.isEnded()).isFalse();
    processor.getOutput();
    assertThat(processor.isEnded()).isTrue();
  }

  @Test
  public void queueInput_beforeConfigureAndFlush_throwsIllegalStateException()
      throws UnhandledAudioFormatException {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_IN_PROVIDER);

    assertThrows(IllegalStateException.class, () -> processor.queueInput(EMPTY_BUFFER));
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    assertThrows(IllegalStateException.class, () -> processor.queueInput(EMPTY_BUFFER));
  }

  @Test
  public void configure_withUnsupportedEncoding_throwsUnhandledAudioFormatException() {
    GainProcessor processor = new GainProcessor(HUNDRED_US_FADE_IN_PROVIDER);
    assertThrows(
        UnhandledAudioFormatException.class,
        () ->
            processor.configure(
                new AudioFormat(
                    /* sampleRate= */ 50000,
                    /* channelCount= */ 1,
                    C.ENCODING_PCM_16BIT_BIG_ENDIAN)));
    assertThrows(
        UnhandledAudioFormatException.class,
        () ->
            processor.configure(
                new AudioFormat(
                    /* sampleRate= */ 50000,
                    /* channelCount= */ 1,
                    C.ENCODING_PCM_24BIT_BIG_ENDIAN)));
    assertThrows(
        UnhandledAudioFormatException.class,
        () ->
            processor.configure(
                new AudioFormat(
                    /* sampleRate= */ 50000,
                    /* channelCount= */ 1,
                    C.ENCODING_PCM_32BIT_BIG_ENDIAN)));
    assertThrows(
        UnhandledAudioFormatException.class,
        () ->
            processor.configure(
                new AudioFormat(
                    /* sampleRate= */ 50000, /* channelCount= */ 1, C.ENCODING_INVALID)));
  }

  @Test
  public void isActive_withConstantGainProviderAtUnity_returnsFalse()
      throws UnhandledAudioFormatException {
    GainProcessor processor =
        new GainProcessor(new DefaultGainProvider.Builder(/* defaultGain= */ 1).build());
    processor.configure(MONO_50KHZ_FLOAT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);
    assertThat(processor.isActive()).isFalse();
  }

  @Test
  public void applyGain_afterFlushWithOffset_adjustsFadePosition()
      throws UnhandledAudioFormatException {
    GainProcessor processor =
        new GainProcessor(
            new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
                .addFadeAt(
                    /* positionUs= */ 0, /* durationUs= */ 100, DefaultGainProvider.FADE_IN_LINEAR)
                .addFadeAt(
                    /* positionUs= */ 200,
                    /* durationUs= */ 100,
                    DefaultGainProvider.FADE_OUT_LINEAR)
                .build());
    processor.configure(MONO_50KHZ_16BIT_FORMAT);
    processor.flush(StreamMetadata.DEFAULT);

    // 15 mono frames set to 100.
    ByteBuffer input =
        createByteBuffer(
            new short[] {
              100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100
            });
    processor.queueInput(input);
    ByteBuffer output = processor.getOutput();

    assertThat(createShortArray(output))
        .isEqualTo(new short[] {0, 20, 40, 60, 80, 100, 100, 100, 100, 100, 100, 80, 60, 40, 20});

    input.rewind();
    // Flush to frame position 5 (1 sample = 20us).
    processor.flush(new StreamMetadata(/* positionOffsetUs= */ 100L));
    processor.queueInput(input);

    // Check that we skip first 5 frames.
    assertThat(createShortArray(output))
        .isEqualTo(
            new short[] {100, 100, 100, 100, 100, 100, 80, 60, 40, 20, 100, 100, 100, 100, 100});
  }
}
