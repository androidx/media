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
package androidx.media3.exoplayer.analytics;

import static com.google.common.truth.Truth.assertThat;

import android.media.metrics.PlaybackMetrics;
import androidx.media3.common.Flags;
import androidx.media3.test.utils.Media3FlagsRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link MediaMetricsListener}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 31)
public final class MediaMetricsListenerTest {

  @Rule public final Media3FlagsRule flagsRule = new Media3FlagsRule(this);

  private static final @Flags.Flag int TEST_FLAG_1 = 1_000_000;
  private static final @Flags.Flag int TEST_FLAG_2 = 1_000_001;

  @Test
  public void addFlagExperimentIds_defaultCanaryMode_addsNoExperimentIds() {
    PlaybackMetrics.Builder builder = new PlaybackMetrics.Builder();

    MediaMetricsListener.addFlagExperimentIds(builder);

    PlaybackMetrics metrics = builder.build();
    assertThat(metrics.getExperimentIds()).isEmpty();
  }

  @Test
  public void addFlagExperimentIds_canaryModeDisabled_addsCanaryModeDisabledExperimentId() {
    Flags.setCanaryModeEnabled(false);
    PlaybackMetrics.Builder builder = new PlaybackMetrics.Builder();

    MediaMetricsListener.addFlagExperimentIds(builder);

    PlaybackMetrics metrics = builder.build();
    assertThat(metrics.getExperimentIds()).asList().containsExactly(7_000_000_000_000_000_000L);
  }

  @Test
  public void addFlagExperimentIds_withFlagOverrides_addsCorrectExperimentIds() {
    Flags.enableFlag(TEST_FLAG_1);
    Flags.disableFlag(TEST_FLAG_2);
    PlaybackMetrics.Builder builder = new PlaybackMetrics.Builder();

    MediaMetricsListener.addFlagExperimentIds(builder);

    PlaybackMetrics metrics = builder.build();
    assertThat(metrics.getExperimentIds())
        .asList()
        .containsExactly(
            7_001_000_000_000_000_000L + TEST_FLAG_1, 7_002_000_000_000_000_000L + TEST_FLAG_2);
  }
}
