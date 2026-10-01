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
package androidx.media3.ui;

import static androidx.media3.test.utils.FakeMultiPeriodLiveTimeline.AD_PERIOD_DURATION_MS;
import static androidx.media3.test.utils.FakeMultiPeriodLiveTimeline.PERIOD_DURATION_MS;
import static com.google.common.truth.Truth.assertThat;

import android.os.Looper;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.test.utils.FakeMultiPeriodLiveTimeline;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link LegacyPlayerControlView}. */
@RunWith(AndroidJUnit4.class)
public final class LegacyPlayerControlViewTest {

  @Test
  public void
      setPlayer_withMultiPeriodLiveTimelineWithAds_ignoresLivePostrollPlaceholdersInTimeBar() {
    LegacyPlayerControlView playerControlView =
        new LegacyPlayerControlView(ApplicationProvider.getApplicationContext());
    DefaultTimeBar timeBar = playerControlView.findViewById(R.id.exo_progress);
    Timeline timeline =
        new FakeMultiPeriodLiveTimeline(
            /* availabilityStartTimeMs= */ 0L,
            /* liveWindowDurationUs= */ 60_000_000L,
            /* nowUs= */ 60_000_000L,
            /* adSequencePattern= */ new boolean[] {false, true, true},
            /* periodDurationMsPattern= */ new long[] {
              PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS
            },
            /* isContentTimeline= */ false,
            /* populateAds= */ true,
            /* playedAds= */ false);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(new Commands.Builder().add(COMMAND_GET_TIMELINE).build())
                .setPlaylist(timeline, Tracks.EMPTY, /* currentMetadata= */ null)
                .build();
          }
        };

    playerControlView.setPlayer(player);

    assertThat(timeBar.getAdGroupTimesMs()).asList().containsExactly(30_000L, 40_000L).inOrder();
    assertThat(timeBar.getPlayedAdGroups()).asList().containsExactly(false, false).inOrder();
  }

  @Test
  public void setPlayer_withMultiPeriodLiveTimelineWithoutAds_setsEmptyAdGroupTimesInTimeBar() {
    LegacyPlayerControlView playerControlView =
        new LegacyPlayerControlView(ApplicationProvider.getApplicationContext());
    DefaultTimeBar timeBar = playerControlView.findViewById(R.id.exo_progress);
    Timeline timeline =
        new FakeMultiPeriodLiveTimeline(
            /* availabilityStartTimeMs= */ 0L,
            /* liveWindowDurationUs= */ 60_000_000L,
            /* nowUs= */ 60_000_000L,
            /* adSequencePattern= */ new boolean[] {false, true, true},
            /* periodDurationMsPattern= */ new long[] {
              PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS, AD_PERIOD_DURATION_MS
            },
            /* isContentTimeline= */ false,
            /* populateAds= */ false,
            /* playedAds= */ false);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(new Commands.Builder().add(COMMAND_GET_TIMELINE).build())
                .setPlaylist(timeline, Tracks.EMPTY, /* currentMetadata= */ null)
                .build();
          }
        };

    playerControlView.setPlayer(player);

    assertThat(timeBar.getAdGroupTimesMs()).isEmpty();
    assertThat(timeBar.getPlayedAdGroups()).isEmpty();
  }
}
