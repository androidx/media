/*
 * Copyright (C) 2022 The Android Open Source Project
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
package androidx.media3.test.utils;

import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_DURATION_US;
import static androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition.DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US;
import static com.google.common.truth.Truth.assertThat;

import androidx.media3.common.AdPlaybackState;
import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.test.utils.FakeTimeline.TimelineWindowDefinition;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link FakeTimeline}. */
@RunWith(AndroidJUnit4.class)
public class FakeTimelineTest {

  @Test
  public void createMultiPeriodAdTimeline_firstPeriodIsAd() {
    Timeline.Window window = new Timeline.Window();
    Timeline.Period period = new Timeline.Period();
    Object windowId = new Object();
    int numberOfPlayedAds = 2;
    FakeTimeline timeline =
        FakeTimeline.createMultiPeriodAdTimeline(
            windowId,
            numberOfPlayedAds,
            /* isAdPeriodFlags...= */ true,
            false,
            true,
            true,
            true,
            false,
            true,
            true);

    assertThat(timeline.getWindowCount()).isEqualTo(1);
    assertThat(timeline.getPeriodCount()).isEqualTo(8);
    // Assert content periods and window duration.
    Timeline.Period contentPeriod1 = timeline.getPeriod(/* periodIndex= */ 1, period);
    Timeline.Period contentPeriod5 = timeline.getPeriod(/* periodIndex= */ 5, period);
    assertThat(contentPeriod1.durationUs).isEqualTo(DEFAULT_WINDOW_DURATION_US / 8);
    assertThat(contentPeriod5.durationUs).isEqualTo(DEFAULT_WINDOW_DURATION_US / 8);
    assertThat(contentPeriod1.getAdGroupCount()).isEqualTo(0);
    assertThat(contentPeriod5.getAdGroupCount()).isEqualTo(0);
    timeline.getWindow(/* windowIndex= */ 0, window);
    assertThat(window.uid).isEqualTo(windowId);
    assertThat(window.durationUs).isEqualTo(DEFAULT_WINDOW_DURATION_US);
    assertThat(window.positionInFirstPeriodUs).isEqualTo(DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US);
    // Assert ad periods.
    int[] adIndices = {0, 2, 3, 4, 6};
    int adCounter = 0;
    for (int periodIndex : adIndices) {
      Timeline.Period adPeriod = timeline.getPeriod(periodIndex, period);
      assertThat(adPeriod.isServerSideInsertedAdGroup(0)).isTrue();
      assertThat(adPeriod.getAdGroupCount()).isEqualTo(1);
      if (adPeriod.getAdGroupCount() > 0) {
        if (adCounter < numberOfPlayedAds) {
          assertThat(adPeriod.getAdState(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
              .isEqualTo(AdPlaybackState.AD_STATE_PLAYED);
        } else {
          assertThat(adPeriod.getAdState(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
              .isEqualTo(AdPlaybackState.AD_STATE_UNAVAILABLE);
        }
        adCounter++;
      }
      long expectedDurationUs =
          (DEFAULT_WINDOW_DURATION_US / 8)
              + (periodIndex == 0 ? DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US : 0);
      assertThat(adPeriod.durationUs).isEqualTo(expectedDurationUs);
      assertThat(adPeriod.getAdDurationUs(/* adGroupIndex= */ 0, /* adIndexInAdGroup= */ 0))
          .isEqualTo(expectedDurationUs);
    }
  }

  @Test
  public void createMultiPeriodAdTimeline_firstPeriodIsContent_correctWindowDurationUs() {
    Timeline.Window window = new Timeline.Window();
    FakeTimeline timeline =
        FakeTimeline.createMultiPeriodAdTimeline(
            /* windowId= */ new Object(),
            /* numberOfPlayedAds= */ 0,
            /* isAdPeriodFlags...= */ false,
            true,
            true,
            false);

    timeline.getWindow(/* windowIndex= */ 0, window);
    // Assert content periods and window duration.
    assertThat(window.durationUs).isEqualTo(DEFAULT_WINDOW_DURATION_US);
    assertThat(window.positionInFirstPeriodUs).isEqualTo(DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US);
  }

  @Test
  public void windowDefinitionBuild_correctDefaultValues() {
    TimelineWindowDefinition timelineWindowDefinition =
        new TimelineWindowDefinition.Builder().build();

    assertThat(timelineWindowDefinition.adPlaybackStates).containsExactly(AdPlaybackState.NONE);
    assertThat(timelineWindowDefinition.defaultPositionUs).isEqualTo(0L);
    assertThat(timelineWindowDefinition.durationUs).isEqualTo(DEFAULT_WINDOW_DURATION_US);
    assertThat(timelineWindowDefinition.isDynamic).isFalse();
    assertThat(timelineWindowDefinition.isLive).isFalse();
    assertThat(timelineWindowDefinition.isPlaceholder).isFalse();
    assertThat(timelineWindowDefinition.isSeekable).isTrue();
    assertThat(timelineWindowDefinition.periodCount).isEqualTo(1);
    assertThat(timelineWindowDefinition.windowStartTimeUs).isEqualTo(C.TIME_UNSET);
    assertThat(timelineWindowDefinition.windowOffsetInFirstPeriodUs)
        .isEqualTo(DEFAULT_WINDOW_OFFSET_IN_FIRST_PERIOD_US);
    assertThat(timelineWindowDefinition.mediaItem)
        .isEqualTo(FakeTimeline.FAKE_MEDIA_ITEM.buildUpon().setTag(0).build());
  }

  @Test
  public void windowDefinitionBuild_allValuesSet_correctPropertiesBuilt() {
    ImmutableList<AdPlaybackState> adPlaybackStates =
        ImmutableList.of(
            AdPlaybackState.NONE, new AdPlaybackState("adsId"), new AdPlaybackState("adsId2"));

    TimelineWindowDefinition timelineWindowDefinition =
        new TimelineWindowDefinition.Builder()
            .setAdPlaybackStates(adPlaybackStates)
            .setDefaultPositionUs(123L)
            .setDurationUs(234L)
            .setDynamic(true)
            .setLive(true)
            .setPlaceholder(true)
            .setPeriodCount(3)
            .setSeekable(false)
            .setUid(457L)
            .setWindowStartTimeUs(567L)
            .setWindowPositionInFirstPeriodUs(678L)
            .build();

    assertThat(timelineWindowDefinition.adPlaybackStates).isEqualTo(adPlaybackStates);
    assertThat(timelineWindowDefinition.defaultPositionUs).isEqualTo(123L);
    assertThat(timelineWindowDefinition.durationUs).isEqualTo(234L);
    assertThat(timelineWindowDefinition.isDynamic).isTrue();
    assertThat(timelineWindowDefinition.isLive).isTrue();
    assertThat(timelineWindowDefinition.isPlaceholder).isTrue();
    assertThat(timelineWindowDefinition.isSeekable).isFalse();
    assertThat(timelineWindowDefinition.periodCount).isEqualTo(3);
    assertThat(timelineWindowDefinition.mediaItem)
        .isEqualTo(FakeTimeline.FAKE_MEDIA_ITEM.buildUpon().setTag(457L).build());
    assertThat(timelineWindowDefinition.windowStartTimeUs).isEqualTo(567L);
    assertThat(timelineWindowDefinition.windowOffsetInFirstPeriodUs).isEqualTo(678L);
  }

  @Test
  public void windowDefinitionBuildUpon_correctPropertiesBuilt() {
    ImmutableList<AdPlaybackState> adPlaybackStates =
        ImmutableList.of(
            AdPlaybackState.NONE, new AdPlaybackState("adsId"), new AdPlaybackState("adsId2"));
    TimelineWindowDefinition timelineWindowDefinition =
        new TimelineWindowDefinition.Builder()
            .setAdPlaybackStates(adPlaybackStates)
            .setDefaultPositionUs(123L)
            .setDurationUs(234L)
            .setDynamic(true)
            .setLive(true)
            .setPlaceholder(true)
            .setPeriodCount(3)
            .setSeekable(false)
            .setUid(457L)
            .setWindowStartTimeUs(567L)
            .setWindowPositionInFirstPeriodUs(678L)
            .build();

    TimelineWindowDefinition fromInstance = timelineWindowDefinition.buildUpon().build();

    assertThat(fromInstance.adPlaybackStates).isEqualTo(timelineWindowDefinition.adPlaybackStates);
    assertThat(fromInstance.defaultPositionUs)
        .isEqualTo(timelineWindowDefinition.defaultPositionUs);
    assertThat(fromInstance.durationUs).isEqualTo(timelineWindowDefinition.durationUs);
    assertThat(fromInstance.isDynamic).isEqualTo(timelineWindowDefinition.isDynamic);
    assertThat(fromInstance.isLive).isEqualTo(timelineWindowDefinition.isLive);
    assertThat(fromInstance.isPlaceholder).isEqualTo(timelineWindowDefinition.isPlaceholder);
    assertThat(fromInstance.isSeekable).isEqualTo(timelineWindowDefinition.isSeekable);
    assertThat(fromInstance.periodCount).isEqualTo(timelineWindowDefinition.periodCount);
    assertThat(fromInstance.mediaItem).isEqualTo(timelineWindowDefinition.mediaItem);
    assertThat(fromInstance.windowStartTimeUs)
        .isEqualTo(timelineWindowDefinition.windowStartTimeUs);
    assertThat(fromInstance.windowOffsetInFirstPeriodUs)
        .isEqualTo(timelineWindowDefinition.windowOffsetInFirstPeriodUs);
  }

  @Test
  public void
      windowDefinitionBuild_multiplePeriodsAdPlaybackStatesUnset_adPlaybackStatesWithCorrectDefault() {
    assertThat(new TimelineWindowDefinition.Builder().setPeriodCount(3).build().adPlaybackStates)
        .containsExactly(AdPlaybackState.NONE, AdPlaybackState.NONE, AdPlaybackState.NONE);
  }

  @Test
  public void windowDefinitionBuild_adPlaybackStatesWithWrongSize_throwsIllegalStateException() {
    Assert.assertThrows(
        IllegalStateException.class,
        () ->
            new TimelineWindowDefinition.Builder()
                .setAdPlaybackStates(
                    ImmutableList.of(
                        AdPlaybackState.NONE, AdPlaybackState.NONE, AdPlaybackState.NONE))
                .setPeriodCount(2)
                .build());
  }
}
