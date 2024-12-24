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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;
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
}
