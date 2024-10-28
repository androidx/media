/*
 * Copyright (C) 2024 The Android Open Source Project
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
package androidx.media3.common.audio;

import static androidx.media3.common.audio.Sonic.calculateAccumulatedTruncationErrorForResampling;
import static androidx.media3.common.audio.Sonic.getExpectedFrameCountAfterProcessorApplied;
import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.math.BigDecimal;
import java.nio.ShortBuffer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;

/** Unit test for {@link Sonic}. */
@RunWith(AndroidJUnit4.class)
public class SonicTest {

  @Rule public final Timeout globalTimeout = Timeout.millis(1000);

  @Test
  public void resample_toDoubleRate_linearlyInterpolatesSamples() {
    ShortBuffer inputBuffer = ShortBuffer.wrap(new short[] {0, 10, 20, 30, 40, 50});
    Sonic sonic =
        new Sonic(
            /* inputSampleRateHz= */ 44100,
            /* channelCount= */ 1,
            /* speed= */ 1,
            /* pitch= */ 1,
            /* outputSampleRateHz= */ 88200);
    sonic.queueInput(inputBuffer);
    sonic.queueEndOfStream();
    ShortBuffer outputBuffer = ShortBuffer.allocate(sonic.getOutputSize() / 2);
    sonic.getOutput(outputBuffer);

    // End of stream is padded with silence, so last sample will be interpolated between (50; 0).
    assertThat(outputBuffer.array())
        .isEqualTo(new short[] {0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 25});
  }

  @Test
  public void resample_toHalfRate_linearlyInterpolatesSamples() {
    ShortBuffer inputBuffer =
        ShortBuffer.wrap(new short[] {-40, -30, -20, -10, 0, 10, 20, 30, 40, 50});
    Sonic sonic =
        new Sonic(
            /* inputSampleRateHz= */ 44100,
            /* channelCount= */ 1,
            /* speed= */ 1,
            /* pitch= */ 1,
            /* outputSampleRateHz= */ 22050);
    sonic.queueInput(inputBuffer);
    sonic.queueEndOfStream();
    ShortBuffer outputBuffer = ShortBuffer.allocate(sonic.getOutputSize() / 2);
    sonic.getOutput(outputBuffer);

    // TODO (b/361768785): Remove this unexpected last sample when Sonic's resampler returns the
    //  right number of samples.
    assertThat(outputBuffer.array()).isEqualTo(new short[] {-40, -20, 0, 20, 40, 0});
  }

  @Test
  public void resample_withOneSample_doesNotHang() {
    ShortBuffer inputBuffer = ShortBuffer.wrap(new short[] {10});
    Sonic sonic =
        new Sonic(
            /* inputSampleRateHz= */ 44100,
            /* channelCount= */ 1,
            /* speed= */ 1,
            /* pitch= */ 1,
            /* outputSampleRateHz= */ 88200);
    sonic.queueInput(inputBuffer);
    sonic.queueEndOfStream();
    ShortBuffer outputBuffer = ShortBuffer.allocate(sonic.getOutputSize() / 2);
    sonic.getOutput(outputBuffer);

    // End of stream is padded with silence, so last sample will be interpolated between (10; 0).
    assertThat(outputBuffer.array()).isEqualTo(new short[] {10, 5});
  }

  @Test
  public void resample_withFractionalOutputSampleCount_roundsNumberOfOutputSamples() {
    ShortBuffer inputBuffer = ShortBuffer.wrap(new short[] {0, 2, 4, 6, 8});
    Sonic sonic =
        new Sonic(
            /* inputSampleRateHz= */ 44100,
            /* channelCount= */ 1,
            /* speed= */ 1,
            /* pitch= */ 1,
            /* outputSampleRateHz= */ 22050);
    sonic.queueInput(inputBuffer);
    sonic.queueEndOfStream();
    ShortBuffer outputBuffer = ShortBuffer.allocate(sonic.getOutputSize() / 2);
    sonic.getOutput(outputBuffer);

    assertThat(outputBuffer.array()).isEqualTo(new short[] {0, 4, 8});
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_timeStretchingFaster_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 44100,
            /* speed= */ 2,
            /* pitch= */ 1,
            /* inputFrameCount= */ 88200);
    assertThat(samples).isEqualTo(44100);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_timeStretchingSlower_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 44100,
            /* speed= */ 0.5f,
            /* pitch= */ 1,
            /* inputFrameCount= */ 88200);
    assertThat(samples).isEqualTo(176400);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_resamplingHigherSampleRate_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 88200,
            /* speed= */ 1f,
            /* pitch= */ 1,
            /* inputFrameCount= */ 88200);
    assertThat(samples).isEqualTo(176400);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_resamplingLowerSampleRate_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 22050,
            /* speed= */ 1f,
            /* pitch= */ 1,
            /* inputFrameCount= */ 88200);
    assertThat(samples).isEqualTo(44100);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_resamplingLowerPitch_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 44100,
            /* speed= */ 0.5f,
            /* pitch= */ 0.5f,
            /* inputFrameCount= */ 88200);
    assertThat(samples).isEqualTo(176400);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_resamplingHigherPitch_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 44100,
            /* speed= */ 2f,
            /* pitch= */ 2f,
            /* inputFrameCount= */ 88200);
    assertThat(samples).isEqualTo(44100);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_resamplePitchAndSampleRateChange_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 44100,
            /* outputSampleRateHz= */ 88200,
            /* speed= */ 1f,
            /* pitch= */ 2f,
            /* inputFrameCount= */ 88200);
    // First time stretch at speed / pitch = 0.5.
    // Then resample at (inputSampleRateHz / outputSampleRateHz) * pitch = 0.5 * 2.
    // Final sample count is 88200 / 0.5 / (0.5 * 2) = 176400.
    assertThat(samples).isEqualTo(176400);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_pitchSpeedAndSampleRateChange_returnsExpectedSampleCount() {
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 48000,
            /* outputSampleRateHz= */ 192000,
            /* speed= */ 5f,
            /* pitch= */ 0.5f,
            /* inputFrameCount= */ 88200);
    // First time stretch at speed / pitch = 10.
    // Then resample at (inputSampleRateHz / outputSampleRateHz) * pitch = 0.25 * 0.5.
    // Final sample count is 88200 / 10 / (0.25 * 0.5) = 176400.
    assertThat(samples).isEqualTo(70560);
  }

  @Test
  public void
      getExpectedFrameCountAfterProcessorApplied_withPeriodicResamplingRate_adjustsForTruncationError() {
    long length = 26902000;
    float resamplingRate = 0.33f;
    long samples =
        getExpectedFrameCountAfterProcessorApplied(
            /* inputSampleRateHz= */ 48000,
            /* outputSampleRateHz= */ 48000,
            /* speed= */ resamplingRate,
            /* pitch= */ resamplingRate,
            /* inputFrameCount= */ length);

    long truncationError =
        calculateAccumulatedTruncationErrorForResampling(
            BigDecimal.valueOf(length),
            BigDecimal.valueOf(48000),
            new BigDecimal(String.valueOf(resamplingRate)));
    // Sonic incurs on accumulated truncation errors when the input sample rate is not exactly
    // divisible by the resampling rate (pitch * inputSampleRateHz / outputSampleRateHz). This error
    // is more prominent on larger stream lengths and inputSampleRateHz + resamplingRate
    // combinations that result in higher truncated decimal values.
    assertThat(samples).isEqualTo(81521212 - truncationError);
  }

  @Test
  public void calculateAccumulatedTruncationErrorForResampling_returnsExpectedSampleCount() {
    long error =
        calculateAccumulatedTruncationErrorForResampling(
            /* length= */ BigDecimal.valueOf(26902000),
            /* sampleRate= */ BigDecimal.valueOf(48000),
            /* resamplingRate= */ new BigDecimal(String.valueOf(0.33f)));

    // Individual error = fractional part of (sampleRate / resamplingRate) = 0.54 (periodic)
    // Error count = length / sampleRate = 560.4583.
    // Accumulated error = error count * individual error = 560.4583 * 0.54 = 305.
    // (All calculations are done on BigDecimal rounded to 20 decimal places, unless indicated).
    assertThat(error).isEqualTo(305);
  }
}
