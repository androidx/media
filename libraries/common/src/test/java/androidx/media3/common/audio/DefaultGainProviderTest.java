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

import static androidx.media3.common.audio.DefaultGainProvider.FADE_IN_EQUAL_POWER;
import static androidx.media3.common.audio.DefaultGainProvider.FADE_IN_LINEAR;
import static androidx.media3.common.audio.DefaultGainProvider.FADE_OUT_EQUAL_POWER;
import static androidx.media3.common.audio.DefaultGainProvider.FADE_OUT_LINEAR;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.C;
import androidx.media3.common.audio.DefaultGainProvider.FadeProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link DefaultGainProvider}. */
@RunWith(AndroidJUnit4.class)
public class DefaultGainProviderTest {

  private static final int SAMPLE_RATE = 50000;

  private static final FadeProvider CONSTANT_VALUE_FADE = (index, duration) -> 0.5f;

  @Test
  public void getGainFactorAtSamplePosition_withoutFades_returnsDefaultValue() {
    DefaultGainProvider provider = new DefaultGainProvider.Builder(/* defaultGain= */ 1f).build();
    assertThat(provider.getGainFactorAtSamplePosition(0, SAMPLE_RATE)).isEqualTo(1f);
  }

  @Test
  public void getGainFactorAtSamplePosition_withConstantFade_returnsFadeValue() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                /* positionUs= */ 0L, /* durationUs= */ C.MICROS_PER_SECOND, CONSTANT_VALUE_FADE)
            .build();
    assertThat(provider.getGainFactorAtSamplePosition(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(0.5f);
  }

  @Test
  public void getGainFactorAtSamplePosition_withFadeIn_returnsFadeValue() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(/* positionUs= */ 0L, /* durationUs= */ C.MICROS_PER_SECOND, FADE_IN_LINEAR)
            .build();
    assertThat(provider.getGainFactorAtSamplePosition(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(0f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ SAMPLE_RATE / 4, SAMPLE_RATE))
        .isEqualTo(0.25f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ SAMPLE_RATE / 2, SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 3 * SAMPLE_RATE / 4, SAMPLE_RATE))
        .isEqualTo(0.75f);
    assertThat(
            provider.getGainFactorAtSamplePosition(/* samplePosition= */ SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
  }

  @Test
  public void getGainFactorAtSamplePosition_withNonTrivialFadeDuration_scalesFade() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                /* positionUs= */ 0L, /* durationUs= */ 4 * C.MICROS_PER_SECOND, FADE_IN_LINEAR)
            .build();
    assertThat(provider.getGainFactorAtSamplePosition(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(0f);
    assertThat(
            provider.getGainFactorAtSamplePosition(/* samplePosition= */ SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.25f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 2 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 3 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.75f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 4 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
  }

  @Test
  public void getGainFactorAtSamplePosition_withSubsequentSampleRateChange_rescalesFades() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(/* positionUs= */ 0L, /* durationUs= */ C.MICROS_PER_SECOND, FADE_IN_LINEAR)
            .build();

    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 0, /* sampleRate= */ SAMPLE_RATE))
        .isEqualTo(0f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ SAMPLE_RATE / 2, /* sampleRate= */ SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ SAMPLE_RATE, /* sampleRate= */ SAMPLE_RATE))
        .isEqualTo(1f);

    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 0, /* sampleRate= */ 2 * SAMPLE_RATE))
        .isEqualTo(0f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ SAMPLE_RATE, /* sampleRate= */ 2 * SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 2 * SAMPLE_RATE, /* sampleRate= */ 2 * SAMPLE_RATE))
        .isEqualTo(1f);
  }

  @Test
  public void getGainFactorAtSamplePosition_afterAddFadeAt_appliesFadeInCorrectly() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                5 * C.MICROS_PER_SECOND, /* durationUs= */ 2 * C.MICROS_PER_SECOND, FADE_IN_LINEAR)
            .build();

    assertThat(provider.getGainFactorAtSamplePosition(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(1f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 3 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 5 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 6 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 7 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
  }

  @Test
  public void getGainFactorAtSamplePosition_afterAddFadeAt_appliesFadeOutCorrectly() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                /* positionUs= */ 5 * C.MICROS_PER_SECOND,
                /* durationUs= */ 4 * C.MICROS_PER_SECOND,
                FADE_OUT_LINEAR)
            .build();

    assertThat(provider.getGainFactorAtSamplePosition(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(1f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 3 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 5 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 6 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.75f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 7 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 8 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.25f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 9 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(1f);
  }

  @Test
  public void getGainFactorAtSamplePosition_superposedFades_keepsLastAddedFadeOnTop() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                /* positionUs= */ 5 * C.MICROS_PER_SECOND,
                /* durationUs= */ 5 * C.MICROS_PER_SECOND,
                FADE_IN_LINEAR)
            .addFadeAt(
                /* positionUs= */ 7 * C.MICROS_PER_SECOND,
                /* durationUs= */ C.MICROS_PER_SECOND,
                CONSTANT_VALUE_FADE)
            .build();

    assertThat(provider.getGainFactorAtSamplePosition(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(1f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 5 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 6 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.2f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 7 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ (long) (7.5 * SAMPLE_RATE), SAMPLE_RATE))
        .isEqualTo(0.5f);
    assertThat(
            provider.getGainFactorAtSamplePosition(
                /* samplePosition= */ 8 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(0.6f);
  }

  @Test
  public void linearFades_maintainEqualGain() {
    int duration = 100;
    for (int i = 0; i <= duration; i++) {
      float inGain = FADE_IN_LINEAR.getGainFactorAt(/* index= */ i, /* duration= */ duration);
      float outGain = FADE_OUT_LINEAR.getGainFactorAt(/* index= */ i, /* duration= */ duration);
      assertThat(inGain + outGain).isWithin(Math.ulp(1.0f)).of(1f);
    }
  }

  @Test
  public void constantPowerFades_maintainEqualPower() {
    int duration = 100;
    for (int i = 0; i <= duration; i++) {
      float inGain = FADE_IN_EQUAL_POWER.getGainFactorAt(/* index= */ i, /* duration= */ 10);
      float outGain = FADE_OUT_EQUAL_POWER.getGainFactorAt(/* index= */ i, /* duration= */ 10);
      assertThat(inGain * inGain + outGain * outGain).isWithin(Math.ulp(1.0f)).of(1.0f);
    }
  }

  @Test
  public void isUnityUntil_withDefaultValueSetToUnity_returnsTimeEndOfStream() {
    DefaultGainProvider provider = new DefaultGainProvider.Builder(/* defaultGain= */ 1f).build();
    assertThat(provider.isUnityUntil(/* samplePosition= */ 0, SAMPLE_RATE))
        .isEqualTo(C.TIME_END_OF_SOURCE);
  }

  @Test
  public void isUnityUntil_withDefaultValueSetToZero_returnsTimeUnset() {
    DefaultGainProvider provider = new DefaultGainProvider.Builder(/* defaultGain= */ 0f).build();
    assertThat(provider.isUnityUntil(/* samplePosition= */ 0, SAMPLE_RATE)).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void isUnityUntil_withMultipleNonUnityRegions_resolvesResultingUnityRegions() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                /* positionUs= */ C.MICROS_PER_SECOND,
                /* durationUs= */ C.MICROS_PER_SECOND,
                CONSTANT_VALUE_FADE)
            .addFadeAt(
                /* positionUs= */ 3 * C.MICROS_PER_SECOND,
                /* durationUs= */ C.MICROS_PER_SECOND,
                CONSTANT_VALUE_FADE)
            .build();
    assertThat(provider.isUnityUntil(/* samplePosition= */ 0, SAMPLE_RATE)).isEqualTo(SAMPLE_RATE);
    assertThat(provider.isUnityUntil(/* samplePosition= */ SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(C.TIME_UNSET);
    assertThat(provider.isUnityUntil(/* samplePosition= */ 2 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(3 * SAMPLE_RATE);
    assertThat(provider.isUnityUntil(/* samplePosition= */ 3 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(C.TIME_UNSET);
    assertThat(provider.isUnityUntil(/* samplePosition= */ 4 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(C.TIME_END_OF_SOURCE);
  }

  @Test
  public void isUnityUntil_withNonUnityRegionStartingAtUnity_doesNotSkipNonUnityRegion() {
    DefaultGainProvider provider =
        new DefaultGainProvider.Builder(/* defaultGain= */ 1f)
            .addFadeAt(
                /* positionUs= */ C.MICROS_PER_SECOND,
                /* durationUs= */ 2 * C.MICROS_PER_SECOND,
                FADE_OUT_LINEAR)
            .build();
    // Fade does not start until second 1.
    assertThat(provider.isUnityUntil(/* samplePosition= */ 0, SAMPLE_RATE)).isEqualTo(SAMPLE_RATE);
    // Fade out starts with gain factor of 1f on first processed sample.
    assertThat(provider.isUnityUntil(/* samplePosition= */ SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(SAMPLE_RATE + 1);
    // After end of fade, default gain of 1f is set until the end.
    assertThat(provider.isUnityUntil(/* samplePosition= */ 3 * SAMPLE_RATE, SAMPLE_RATE))
        .isEqualTo(C.TIME_END_OF_SOURCE);
  }
}
