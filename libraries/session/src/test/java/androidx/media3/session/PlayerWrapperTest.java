/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.session;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.test.utils.FakeTimeline;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/** Tests for {@link PlayerWrapper}. */
@RunWith(AndroidJUnit4.class)
public class PlayerWrapperTest {

  @Rule public final MockitoRule mockito = MockitoJUnit.rule();

  @Mock private Player player;

  private PlayerWrapper playerWrapper;

  @Before
  public void setUp() {
    playerWrapper = new PlayerWrapper(player);
    when(player.isCommandAvailable(anyInt())).thenReturn(true);
    when(player.getApplicationLooper()).thenReturn(Looper.myLooper());
  }

  @Test
  public void
      getCurrentTimelineWithCommandCheck_withoutCommandGetTimelineAndGetCurrentMediaItem_isEmpty() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(false);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(false);
    when(player.getCurrentTimeline()).thenReturn(new FakeTimeline(/* windowCount= */ 3));

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.isEmpty()).isTrue();
  }

  @Test
  public void getCurrentTimelineWithCommandCheck_withoutCommandGetTimelineWhenEmpty_isEmpty() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(false);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(true);
    when(player.getCurrentTimeline()).thenReturn(Timeline.EMPTY);

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.isEmpty()).isTrue();
  }

  @Test
  public void
      getCurrentTimelineWithCommandCheck_withoutCommandGetTimelineWhenMultipleItems_hasSingleItemTimeline() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(false);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(true);
    when(player.getCurrentMediaItem()).thenReturn(MediaItem.fromUri("http://www.example.com"));

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.getWindowCount()).isEqualTo(1);
  }

  @Test
  public void getCurrentTimelineWithCommandCheck_withCommandGetTimeline_returnOriginalTimeline() {
    when(player.isCommandAvailable(Player.COMMAND_GET_TIMELINE)).thenReturn(true);
    when(player.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)).thenReturn(false);
    when(player.getCurrentTimeline()).thenReturn(new FakeTimeline(/* windowCount= */ 3));

    Timeline currentTimeline = playerWrapper.getCurrentTimelineWithCommandCheck();

    assertThat(currentTimeline.getWindowCount()).isEqualTo(3);
  }

  @Test
  public void createSessionPositionInfo() {
    int testAdGroupIndex = 12;
    int testAdIndexInAdGroup = 99;
    boolean testIsPlayingAd = true;
    long testDurationMs = 5000;
    long testCurrentPositionMs = 223;
    long testBufferedPositionMs = 500;
    int testBufferedPercentage = 10;
    long testTotalBufferedDurationMs = 30;
    long testCurrentLiveOffsetMs = 212;
    long testContentDurationMs = 6000;
    long testContentPositionMs = 333;
    long testContentBufferedPositionMs = 2223;
    int testMediaItemIndex = 7;
    int testPeriodIndex = 7;
    when(player.getCurrentTimeline()).thenReturn(new FakeTimeline(/* windowCount= */ 8));
    when(player.getCurrentAdGroupIndex()).thenReturn(testAdGroupIndex);
    when(player.getCurrentAdIndexInAdGroup()).thenReturn(testAdIndexInAdGroup);
    when(player.isPlayingAd()).thenReturn(testIsPlayingAd);
    when(player.getDuration()).thenReturn(testDurationMs);
    when(player.getCurrentPosition()).thenReturn(testCurrentPositionMs);
    when(player.getBufferedPosition()).thenReturn(testBufferedPositionMs);
    when(player.getBufferedPercentage()).thenReturn(testBufferedPercentage);
    when(player.getTotalBufferedDuration()).thenReturn(testTotalBufferedDurationMs);
    when(player.getCurrentLiveOffset()).thenReturn(testCurrentLiveOffsetMs);
    when(player.getContentDuration()).thenReturn(testContentDurationMs);
    when(player.getContentPosition()).thenReturn(testContentPositionMs);
    when(player.getContentBufferedPosition()).thenReturn(testContentBufferedPositionMs);
    when(player.getCurrentMediaItemIndex()).thenReturn(testMediaItemIndex);
    when(player.getCurrentPeriodIndex()).thenReturn(testPeriodIndex);

    SessionPositionInfo sessionPositionInfo = playerWrapper.createSessionPositionInfo();

    assertThat(sessionPositionInfo.positionInfo.positionMs).isEqualTo(testCurrentPositionMs);
    assertThat(sessionPositionInfo.positionInfo.contentPositionMs).isEqualTo(testContentPositionMs);
    assertThat(sessionPositionInfo.positionInfo.adGroupIndex).isEqualTo(testAdGroupIndex);
    assertThat(sessionPositionInfo.positionInfo.adIndexInAdGroup).isEqualTo(testAdIndexInAdGroup);
    assertThat(sessionPositionInfo.positionInfo.mediaItemIndex).isEqualTo(testMediaItemIndex);
    assertThat(sessionPositionInfo.positionInfo.periodIndex).isEqualTo(testPeriodIndex);
    assertThat(sessionPositionInfo.isPlayingAd).isEqualTo(testIsPlayingAd);
    assertThat(sessionPositionInfo.durationMs).isEqualTo(testDurationMs);
    assertThat(sessionPositionInfo.bufferedPositionMs).isEqualTo(testBufferedPositionMs);
    assertThat(sessionPositionInfo.bufferedPercentage).isEqualTo(testBufferedPercentage);
    assertThat(sessionPositionInfo.totalBufferedDurationMs).isEqualTo(testTotalBufferedDurationMs);
    assertThat(sessionPositionInfo.currentLiveOffsetMs).isEqualTo(testCurrentLiveOffsetMs);
    assertThat(sessionPositionInfo.contentDurationMs).isEqualTo(testContentDurationMs);
    assertThat(sessionPositionInfo.contentBufferedPositionMs)
        .isEqualTo(testContentBufferedPositionMs);
  }

  @Test
  public void
      createPositionInfo_periodIndexNotMatchingWindowPeriodIndices_throwsIllegalStateException() {
    when(player.getCurrentTimeline()).thenReturn(new FakeTimeline(/* windowCount= */ 8));
    when(player.getCurrentMediaItemIndex()).thenReturn(0);
    when(player.getCurrentPeriodIndex()).thenReturn(1);

    assertThrows(IllegalStateException.class, playerWrapper::createPositionInfo);
  }

  @Test
  public void
      createPositionInfo_currentMediaItemIndexNotZeroWithEmptyTimelineInEnded_correctPositionInfo() {
    when(player.getCurrentTimeline()).thenReturn(Timeline.EMPTY);
    when(player.getPlaybackState()).thenReturn(Player.STATE_ENDED);
    when(player.getCurrentMediaItemIndex()).thenReturn(1);
    when(player.getCurrentPeriodIndex()).thenReturn(1);

    Player.PositionInfo positionInfo = playerWrapper.createPositionInfo();

    assertThat(positionInfo.mediaItemIndex).isEqualTo(1);
    assertThat(positionInfo.periodIndex).isEqualTo(1);
  }

  @Test
  public void createPositionInfo_withEmptyTimelineWhenIdle_everythingGoes() {
    when(player.getCurrentTimeline()).thenReturn(Timeline.EMPTY);
    when(player.getPlaybackState()).thenReturn(Player.STATE_IDLE);
    when(player.getCurrentMediaItemIndex()).thenReturn(11);
    when(player.getCurrentPeriodIndex()).thenReturn(111);

    Player.PositionInfo positionInfo = playerWrapper.createPositionInfo();

    assertThat(positionInfo).isNotNull();
    assertThat(positionInfo.mediaItemIndex).isEqualTo(11);
    assertThat(positionInfo.periodIndex).isEqualTo(111);
  }
}
