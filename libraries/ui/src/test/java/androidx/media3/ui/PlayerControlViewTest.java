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
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.PopupWindow;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.Timeline;
import androidx.media3.common.Tracks;
import androidx.media3.test.utils.FakeMultiPeriodLiveTimeline;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;

/** Tests for {@link PlayerControlView}. */
@RunWith(AndroidJUnit4.class)
public final class PlayerControlViewTest {

  @Test
  public void
      setPlayer_withMultiPeriodLiveTimelineWithAds_ignoresLivePostrollPlaceholdersInTimeBar() {
    PlayerControlView playerControlView =
        new PlayerControlView(ApplicationProvider.getApplicationContext());
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
    PlayerControlView playerControlView =
        new PlayerControlView(ApplicationProvider.getApplicationContext());
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

  @Test
  public void settingsButtonClick_withPadding_positionsSettingsWindowRelativeToAnchor() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    PlayerControlView playerControlView =
        createPlayerControlViewInActivity(activity, /* width= */ 300, /* height= */ 300);
    View settingsButton = playerControlView.findViewById(R.id.exo_settings);

    settingsButton.performClick();
    shadowOf(Looper.getMainLooper()).idle();

    PopupWindow settingsWindow =
        shadowOf((Application) ApplicationProvider.getApplicationContext()).getLatestPopupWindow();
    assertThat(settingsWindow).isNotNull();
    assertThat(settingsWindow.isShowing()).isTrue();
    WindowManager.LayoutParams layoutParams =
        (WindowManager.LayoutParams)
            settingsWindow.getContentView().getRootView().getLayoutParams();
    int[] anchorLocation = new int[2];
    settingsButton.getLocationInWindow(anchorLocation);
    int settingsWindowMargin =
        activity.getResources().getDimensionPixelSize(R.dimen.exo_settings_offset);
    assertThat(layoutParams.x + settingsWindow.getWidth())
        .isEqualTo(anchorLocation[0] + settingsButton.getWidth() - settingsWindowMargin);
    assertThat(layoutParams.y + settingsWindow.getHeight())
        .isEqualTo(anchorLocation[1] + settingsButton.getHeight() - settingsWindowMargin);
  }

  @Test
  public void onLayoutChange_withUpdatedPadding_updatesSettingsWindowPosition() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    PlayerControlView playerControlView =
        createPlayerControlViewInActivity(activity, /* width= */ 300, /* height= */ 300);
    View settingsButton = playerControlView.findViewById(R.id.exo_settings);
    settingsButton.performClick();
    shadowOf(Looper.getMainLooper()).idle();

    playerControlView.setPadding(/* left= */ 20, /* top= */ 20, /* right= */ 80, /* bottom= */ 50);
    playerControlView.measure(
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
    playerControlView.layout(0, 0, 300, 300);
    shadowOf(Looper.getMainLooper()).idle();

    PopupWindow settingsWindow =
        shadowOf((Application) ApplicationProvider.getApplicationContext()).getLatestPopupWindow();
    WindowManager.LayoutParams layoutParams =
        (WindowManager.LayoutParams)
            settingsWindow.getContentView().getRootView().getLayoutParams();
    int[] anchorLocation = new int[2];
    settingsButton.getLocationInWindow(anchorLocation);
    int settingsWindowMargin =
        activity.getResources().getDimensionPixelSize(R.dimen.exo_settings_offset);
    assertThat(layoutParams.x + settingsWindow.getWidth())
        .isEqualTo(anchorLocation[0] + settingsButton.getWidth() - settingsWindowMargin);
    assertThat(layoutParams.y + settingsWindow.getHeight())
        .isEqualTo(anchorLocation[1] + settingsButton.getHeight() - settingsWindowMargin);
  }

  @Test
  public void onLayoutChange_withUpdatedSize_updatesSettingsWindowPosition() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    PlayerControlView playerControlView =
        createPlayerControlViewInActivity(activity, /* width= */ 300, /* height= */ 300);
    View settingsButton = playerControlView.findViewById(R.id.exo_settings);
    settingsButton.performClick();
    shadowOf(Looper.getMainLooper()).idle();

    playerControlView.measure(
        View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY));
    playerControlView.layout(0, 0, 250, 250);
    shadowOf(Looper.getMainLooper()).idle();

    PopupWindow settingsWindow =
        shadowOf((Application) ApplicationProvider.getApplicationContext()).getLatestPopupWindow();
    WindowManager.LayoutParams layoutParams =
        (WindowManager.LayoutParams)
            settingsWindow.getContentView().getRootView().getLayoutParams();
    int[] anchorLocation = new int[2];
    settingsButton.getLocationInWindow(anchorLocation);
    int settingsWindowMargin =
        activity.getResources().getDimensionPixelSize(R.dimen.exo_settings_offset);
    assertThat(layoutParams.x + settingsWindow.getWidth())
        .isEqualTo(anchorLocation[0] + settingsButton.getWidth() - settingsWindowMargin);
    assertThat(layoutParams.y + settingsWindow.getHeight())
        .isEqualTo(anchorLocation[1] + settingsButton.getHeight() - settingsWindowMargin);
  }

  @Test
  public void onLayoutChange_withPaddingExceedingDimensions_clampsSettingsWindowSizeToZero() {
    Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
    PlayerControlView playerControlView =
        createPlayerControlViewInActivity(activity, /* width= */ 250, /* height= */ 250);
    View settingsButton = playerControlView.findViewById(R.id.exo_settings);
    settingsButton.performClick();
    shadowOf(Looper.getMainLooper()).idle();

    playerControlView.setPadding(
        /* left= */ 200, /* top= */ 200, /* right= */ 200, /* bottom= */ 200);
    playerControlView.measure(
        View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY));
    playerControlView.layout(0, 0, 250, 250);
    shadowOf(Looper.getMainLooper()).idle();

    PopupWindow settingsWindow =
        shadowOf((Application) ApplicationProvider.getApplicationContext()).getLatestPopupWindow();
    assertThat(settingsWindow.getWidth()).isEqualTo(0);
    assertThat(settingsWindow.getHeight()).isEqualTo(0);
  }

  private static PlayerControlView createPlayerControlViewInActivity(
      Activity activity, int width, int height) {
    PlayerControlView playerControlView = new PlayerControlView(activity);
    playerControlView.setShowTimeoutMs(0);
    playerControlView.setPadding(/* left= */ 20, /* top= */ 20, /* right= */ 40, /* bottom= */ 30);
    Player player =
        new SimpleBasePlayer(Looper.getMainLooper()) {
          @Override
          protected State getState() {
            return new State.Builder()
                .setAvailableCommands(
                    new Commands.Builder().add(COMMAND_SET_SPEED_AND_PITCH).build())
                .build();
          }
        };
    playerControlView.setPlayer(player);
    activity.setContentView(playerControlView, new ViewGroup.LayoutParams(width, height));
    playerControlView.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    playerControlView.layout(0, 0, width, height);
    shadowOf(Looper.getMainLooper()).idle();
    return playerControlView;
  }
}
