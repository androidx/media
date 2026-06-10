/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer;

import static androidx.media3.common.Player.DISCONTINUITY_REASON_AUTO_TRANSITION;
import static androidx.media3.common.Player.STATE_ENDED;
import static androidx.media3.common.Player.STATE_READY;
import static androidx.media3.common.Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED;
import static androidx.media3.common.Player.TIMELINE_CHANGE_REASON_SOURCE_UPDATE;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
import static androidx.media3.test.utils.robolectric.TestPlayerRunHelper.advance;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Player.PositionInfo;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Period;
import androidx.media3.datasource.DataSpec;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.test.utils.FakeAdsLoader;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.FakeMediaSourceFactory;
import androidx.media3.test.utils.FakeTimeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.media3.test.utils.TestExoPlayerBuilder;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link ExoPlayer#setEnforceAdPlaybackOnTimelineRefresh}. */
@RunWith(AndroidJUnit4.class)
public final class ExoPlayerAdPlaybackEnforcementTest {

  @Test
  public void adInsertedAtCurrentPosition_enforced_isPlayed() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setEnforceAdPlaybackOnTimelineRefresh(true).build();
    Timeline contentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(10_000_000).build());
    AdPlaybackState emptyAdPlaybackState = new AdPlaybackState("adsId");
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Integer> capturedReasons = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            capturedReasons.add(reason);
          }
        });
    // Start playback
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    // Set empty ad playback state to prepare with (no preroll)
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(emptyAdPlaybackState);
    advance(player).untilTimelineChanges();
    advance(player).untilPositionAtLeast(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);
    // Set an ad to which we want to snap back (position > adGroup.timeUs).
    AdPlaybackState updatedAdPlaybackState =
        emptyAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 1_900_000)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad"));

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    assertThat(capturedOldPositions).hasSize(2);
    assertThat(capturedOldPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedNewPositions.get(0).adGroupIndex).isEqualTo(0);
    assertThat(capturedOldPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(capturedNewPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedReasons)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION, DISCONTINUITY_REASON_AUTO_TRANSITION);
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_enforcedSetAfterBuild_isPlayed() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setEnforceAdPlaybackOnTimelineRefresh(false).build();
    Timeline contentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(10_000_000).build());
    AdPlaybackState emptyAdPlaybackState = new AdPlaybackState("adsId");
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Integer> capturedReasons = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            capturedReasons.add(reason);
          }
        });
    // Start playback.
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(emptyAdPlaybackState);
    AdPlaybackState updatedAdPlaybackState =
        emptyAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 2_000_000)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad"));
    // Set enforcement to true AFTER the player is built and playing.
    player.setEnforceAdPlaybackOnTimelineRefresh(true);
    advance(player).untilPositionAtLeast(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert that we transitioned to the ad because enforcement was enabled before the refresh.
    assertThat(capturedOldPositions).hasSize(2);
    assertThat(capturedOldPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedNewPositions.get(0).adGroupIndex).isEqualTo(0);
    assertThat(capturedOldPositions.get(1).adGroupIndex).isEqualTo(0);
    assertThat(capturedNewPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedReasons)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION, DISCONTINUITY_REASON_AUTO_TRANSITION);
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_notEnforced_isNotPlayed() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setEnforceAdPlaybackOnTimelineRefresh(false).build();
    Timeline contentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder().setDurationUs(100_000_000L).build());
    AdPlaybackState emptyAdPlaybackState = new AdPlaybackState("adsId");
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000L)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Integer> capturedReasons = new ArrayList<>();
    List<Timeline> capturedTimelines = new ArrayList<>();
    List<Integer> capturedTimelineReasons = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            capturedReasons.add(reason);
          }

          @Override
          public void onTimelineChanged(Timeline timeline, int reason) {
            capturedTimelines.add(timeline);
            capturedTimelineReasons.add(reason);
          }
        });
    // Start playback.
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(emptyAdPlaybackState);
    advance(player).untilTimelineChanges();
    AdPlaybackState updatedAdPlaybackState =
        emptyAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 0)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/midroll"))
            .withNewAdGroup(/* adGroupIndex= */ 1, /* adGroupTimeUs= */ C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/postroll"));
    advance(player).untilPositionAtLeast(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert transitions that did not play the first ad group but only the postroll
    assertThat(capturedReasons)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION, DISCONTINUITY_REASON_AUTO_TRANSITION);
    assertThat(capturedOldPositions.get(0).positionMs).isEqualTo(100_000L); // Post-roll
    assertThat(capturedOldPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedNewPositions.get(0).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(1).positionMs).isEqualTo(99_999L); // End Post-roll
    assertThat(capturedOldPositions.get(1).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedTimelineReasons)
        .containsExactly(
            TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        .inOrder();
    assertThat(
            capturedTimelines.get(0).getPeriod(/* periodIndex= */ 0, new Period()).adPlaybackState)
        .isEqualTo(AdPlaybackState.NONE);
    assertThat(
            capturedTimelines.get(1).getPeriod(/* periodIndex= */ 0, new Period()).adPlaybackState)
        .isEqualTo(AdPlaybackState.NONE);
    assertThat(
            capturedTimelines
                .get(2)
                .getPeriod(/* periodIndex= */ 0, new Period())
                .adPlaybackState
                .adGroupCount)
        .isEqualTo(2);
    assertThat(
            capturedTimelines
                .get(3)
                .getPeriod(/* periodIndex= */ 0, new Period())
                .adPlaybackState
                .adGroupCount)
        .isEqualTo(2);
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_notEnforcedSetAfterBuild_isNotPlayed() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    ExoPlayer player =
        new TestExoPlayerBuilder(context).setEnforceAdPlaybackOnTimelineRefresh(true).build();
    Timeline contentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(10_000_000).build());
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Timeline> capturedTimelines = new ArrayList<>();
    List<Integer> capturedTimelineReasons = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedNewPositions.add(newPosition);
          }

          @Override
          public void onTimelineChanged(Timeline timeline, int reason) {
            capturedTimelines.add(timeline);
            capturedTimelineReasons.add(reason);
          }
        });
    // Start playback.
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    AdPlaybackState emptyAdPlaybackState = new AdPlaybackState("adsId");
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(emptyAdPlaybackState);
    advance(player).untilTimelineChanges();
    AdPlaybackState updatedAdPlaybackState =
        emptyAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 0, /* adGroupTimeUs= */ 2_000_000)
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/ad"));
    // Set enforcement to true AFTER the player is built and playing.
    player.setEnforceAdPlaybackOnTimelineRefresh(false);
    advance(player).untilPositionAtLeast(/* mediaItemIndex= */ 0, /* positionMs= */ 2_000L);

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert that we did NOT transition to the ad.
    assertThat(capturedNewPositions).isEmpty();
    assertThat(capturedTimelineReasons)
        .containsExactly(
            TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        .inOrder();
    assertThat(
            capturedTimelines.get(1).getPeriod(/* periodIndex= */ 0, new Period()).adPlaybackState)
        .isEqualTo(AdPlaybackState.NONE);
    assertThat(
            capturedTimelines
                .get(2)
                .getPeriod(/* periodIndex= */ 0, new Period())
                .adPlaybackState
                .adGroupCount)
        .isEqualTo(1);
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_notEnforcedWithPreRoll_preRollPlaying() throws Exception {
    TestExoPlayerBuilder playerBuilder =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext());
    ExoPlayer player = playerBuilder.setEnforceAdPlaybackOnTimelineRefresh(false).build();
    long firstSampleTimeUs = 0;
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState("adsId", firstSampleTimeUs)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com"))
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Integer> capturedReasons = new ArrayList<>();
    List<Integer> capturedReadyAdGroupIndices = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            capturedReasons.add(reason);
          }

          @Override
          public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
              capturedReadyAdGroupIndices.add(player.getCurrentAdGroupIndex());
            }
          }
        });
    Timeline contentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(10_000_000).build());
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    player.play();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(initialAdPlaybackState);
    advance(player).untilState(STATE_READY);
    AdPlaybackState updatedAdPlaybackState =
        initialAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 1, firstSampleTimeUs + 1_000L)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/midroll"));

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert pre-roll and the following ad was played.
    assertThat(capturedReadyAdGroupIndices.get(0)).isEqualTo(0); // starts with preroll
    assertThat(capturedReasons)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION);
    assertThat(capturedOldPositions.get(0).adGroupIndex).isEqualTo(0);
    assertThat(capturedNewPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedOldPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedNewPositions.get(1).adGroupIndex).isEqualTo(1);
    assertThat(capturedOldPositions.get(2).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(2).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    player.release();
  }

  @Test
  public void
      adInsertedAtCurrentPosition_notEnforcedNonPrerollStartPosition_exactMatchingAdPlaying()
          throws Exception {
    TestExoPlayerBuilder playerBuilder =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext());
    ExoPlayer player = playerBuilder.setEnforceAdPlaybackOnTimelineRefresh(false).build();
    long firstSampleTimeUs = 0;
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState("adsId", firstSampleTimeUs + 1_000_000L)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com"))
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Integer> capturedReasons = new ArrayList<>();
    List<Integer> capturedReadyAdGroupIndices = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            capturedReasons.add(reason);
          }

          @Override
          public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
              capturedReadyAdGroupIndices.add(player.getCurrentAdGroupIndex());
            }
          }
        });
    Timeline contentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(10_000_000).build());
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    player.setMediaSource(adsMediaSource, /* startPositionMs= */ 1_000L);
    player.prepare();
    player.play();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(initialAdPlaybackState);
    advance(player).untilState(STATE_READY);
    AdPlaybackState updatedAdPlaybackState =
        initialAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 1, firstSampleTimeUs + 2_000_000L)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/midroll"));

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert ad at start position and the following ad was played.
    assertThat(capturedReadyAdGroupIndices.get(0)).isEqualTo(0); // starts with ad
    assertThat(capturedReasons)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION);
    assertThat(capturedNewPositions.get(0).positionMs).isEqualTo(1_000L); // ad to content
    assertThat(capturedOldPositions.get(0).adGroupIndex).isEqualTo(0);
    assertThat(capturedNewPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedOldPositions.get(1).positionMs).isEqualTo(2_000L); // content to ad
    assertThat(capturedOldPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedNewPositions.get(1).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(2).positionMs).isEqualTo(2_000L); // ad to content
    assertThat(capturedOldPositions.get(2).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(2).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_notEnforcedNonPrerollStartPosition_preRollNotPlaying()
      throws Exception {
    TestExoPlayerBuilder playerBuilder =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext());
    ExoPlayer player = playerBuilder.setEnforceAdPlaybackOnTimelineRefresh(false).build();
    long firstSampleTimeUs = 0;
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState("adsId", firstSampleTimeUs)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com"))
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<Integer> capturedReasons = new ArrayList<>();
    List<Integer> capturedReadyAdGroupIndices = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            capturedReasons.add(reason);
          }

          @Override
          public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY) {
              capturedReadyAdGroupIndices.add(player.getCurrentAdGroupIndex());
            }
          }
        });
    Timeline contentTimeline =
        new FakeTimeline(
            new TimelineWindowDefinition.Builder()
                .setWindowPositionInFirstPeriodUs(0)
                .setDurationUs(10_000_000)
                .build());
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    player.setMediaSource(adsMediaSource, /* startPositionMs= */ 1_000L);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);
    player.play();
    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(initialAdPlaybackState);
    advance(player).untilState(STATE_READY);
    AdPlaybackState updatedAdPlaybackState =
        initialAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 1, firstSampleTimeUs + 2_000_000L)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://example.com/midroll"));

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert playback started with content and the pre-roll wasn't playing.
    assertThat(capturedReadyAdGroupIndices.get(0)).isEqualTo(-1); // starts with content
    assertThat(capturedReasons)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION, DISCONTINUITY_REASON_AUTO_TRANSITION);
    assertThat(capturedOldPositions.get(0).positionMs).isEqualTo(2_000L); // content to ad
    assertThat(capturedOldPositions.get(0).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    assertThat(capturedNewPositions.get(0).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(1).positionMs).isEqualTo(2_000L); // ad to content
    assertThat(capturedOldPositions.get(1).adGroupIndex).isEqualTo(1);
    assertThat(capturedNewPositions.get(1).adGroupIndex).isEqualTo(C.INDEX_UNSET);
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_notEnforcedNonPlaceholderSameUid_keepsAd()
      throws Exception {
    TestExoPlayerBuilder playerBuilder =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext());
    ExoPlayer player = playerBuilder.setEnforceAdPlaybackOnTimelineRefresh(false).build();
    long firstSampleTimeUs = 0;
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState("adsId", firstSampleTimeUs)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com/preroll"))
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    List<PositionInfo> capturedNewPositions = new ArrayList<>();
    List<PositionInfo> capturedOldPositions = new ArrayList<>();
    List<Integer> captureDiscontinuityReason = new ArrayList<>();
    List<Integer> capturedTimelineReasons = new ArrayList<>();
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPositionDiscontinuity(
              PositionInfo oldPosition, PositionInfo newPosition, int reason) {
            capturedOldPositions.add(oldPosition);
            capturedNewPositions.add(newPosition);
            captureDiscontinuityReason.add(reason);
          }

          @Override
          public void onTimelineChanged(Timeline timeline, int reason) {
            capturedTimelineReasons.add(reason);
          }
        });
    Timeline contentTimeline =
        new FakeTimeline(new TimelineWindowDefinition.Builder().setDurationUs(10_000_000L).build());
    FakeAdsLoader fakeAdsLoader = new FakeAdsLoader();
    AdsMediaSource adsMediaSource =
        new AdsMediaSource(
            new FakeMediaSource(contentTimeline),
            new DataSpec(Uri.EMPTY),
            "adsId",
            new FakeMediaSourceFactory(
                new TimelineWindowDefinition.Builder().setDurationUs(1_000_000L)),
            fakeAdsLoader,
            /* adViewProvider= */ () -> null);
    player.setMediaSource(adsMediaSource);
    player.prepare();
    advance(player)
        .untilBackgroundThreadCondition(
            (Supplier<Boolean>) () -> fakeAdsLoader.eventListeners.get("adsId") != null);

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(initialAdPlaybackState);

    advance(player).untilTimelineChanges();
    player.play();
    AdPlaybackState updatedAdPlaybackState =
        initialAdPlaybackState
            .withNewAdGroup(
                /* adGroupIndex= */ 1, /* adGroupTimeUs= */ firstSampleTimeUs + 4_000_000L)
            .withAdCount(/* adGroupIndex= */ 1, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 1,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com/midroll"));

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(updatedAdPlaybackState);

    AdPlaybackState postRollAdPlaybackState =
        updatedAdPlaybackState
            .withNewAdGroup(/* adGroupIndex= */ 2, /* adGroupTimeUs= */ C.TIME_END_OF_SOURCE)
            .withAdCount(/* adGroupIndex= */ 2, /* adCount= */ 1)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 2,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com/postroll"));
    advance(player).untilPositionAtLeast(1_500L);

    fakeAdsLoader.eventListeners.get("adsId").onAdPlaybackState(postRollAdPlaybackState);

    advance(player).untilState(STATE_ENDED);
    // Assert timeline updates did not skip a loading pre-roll ad on subsequent timeline updates.
    assertThat(capturedTimelineReasons)
        .containsExactly(
            TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE,
            TIMELINE_CHANGE_REASON_SOURCE_UPDATE)
        .inOrder();
    assertThat(captureDiscontinuityReason)
        .containsExactly(
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION,
            DISCONTINUITY_REASON_AUTO_TRANSITION);
    assertThat(capturedOldPositions.stream().map(it -> it.adGroupIndex))
        .containsExactly(0, C.INDEX_UNSET, 1, C.INDEX_UNSET, 2)
        .inOrder();
    assertThat(capturedOldPositions.stream().map(it -> it.positionMs))
        .containsExactly(124_000L, 4_000L, 124_000L, 10_000L, 124_000L)
        .inOrder();
    assertThat(capturedNewPositions.stream().map(it -> it.adGroupIndex))
        .containsExactly(C.INDEX_UNSET, 1, C.INDEX_UNSET, 2, C.INDEX_UNSET)
        .inOrder();
    assertThat(capturedNewPositions.stream().map(it -> it.positionMs))
        .containsExactly(0L, 0L, 4_000L, 0L, 9999L)
        .inOrder();
    player.release();
  }

  @Test
  public void adInsertedAtCurrentPosition_notEnforcedNonPlaceholderDifferentUid_dropsAd()
      throws Exception {
    TestExoPlayerBuilder playerBuilder =
        new TestExoPlayerBuilder(ApplicationProvider.getApplicationContext());
    ExoPlayer player = playerBuilder.setEnforceAdPlaybackOnTimelineRefresh(false).build();
    long firstSampleTimeUs = DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
    AdPlaybackState initialAdPlaybackState =
        new AdPlaybackState("adsId", firstSampleTimeUs)
            .withAvailableAdMediaItem(
                /* adGroupIndex= */ 0,
                /* adIndexInAdGroup= */ 0,
                MediaItem.fromUri("http://www.example.com"))
            .withAdCount(/* adGroupIndex= */ 0, /* adCount= */ 1);
    TimelineWindowDefinition windowA =
        new TimelineWindowDefinition.Builder()
            .setUid("windowA")
            .setAdPlaybackStates(ImmutableList.of(initialAdPlaybackState))
            .setPlaceholder(false)
            .build();
    TimelineWindowDefinition windowB =
        new TimelineWindowDefinition.Builder().setUid("windowB").setPlaceholder(false).build();
    FakeMediaSource mediaSource =
        new FakeMediaSource.Builder().setTimeline(new FakeTimeline(windowA, windowB)).build();
    player.setMediaSource(mediaSource);
    player.prepare();
    player.play();
    advance(player).untilState(STATE_READY);

    // Refresh timeline omitting windowA entirely, transitioning  to windowB
    mediaSource.setNewSourceInfo(new FakeTimeline(windowB));

    // Assert ad is dropped when periodUid is changed by timeline update.
    advance(player).untilTimelineChanges();
    assertThat(player.getCurrentAdGroupIndex()).isEqualTo(-1);
    assertThat(player.getCurrentPeriodIndex()).isEqualTo(0);
    assertThat(player.getMediaItemCount()).isEqualTo(1);
    player.release();
  }
}
