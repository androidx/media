/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.test.utils

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.SimpleBasePlayer.PeriodData
import androidx.media3.common.Timeline
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.shadows.ShadowLooper

/** Unit tests for [FakePlayer]. */
@RunWith(AndroidJUnit4::class)
class FakePlayerTest {

  private val seekablePlaylist = listOf(MediaItemData.Builder("item").setIsSeekable(true).build())

  @Test
  fun setMediaItems_withMultipleDefaultMediaIdItems_setsPlaylist() {
    val player = FakePlayer()
    val firstItem = MediaItem.fromUri("https://example.com/1.mp4")
    val secondItem = MediaItem.fromUri("https://example.com/2.mp4")

    player.setMediaItems(listOf(firstItem, secondItem))

    assertThat(player.mediaItemCount).isEqualTo(2)
    assertThat(player.getMediaItemAt(0)).isEqualTo(firstItem)
    assertThat(player.getMediaItemAt(1)).isEqualTo(secondItem)
  }

  @Test
  fun setMediaItem_withEqualMediaItem_notifiesTimelineChangedAndMediaItemTransition() {
    val player = FakePlayer()
    player.setMediaItem(MediaItem.fromUri("https://example.com/video.mp4"))
    val listener = mock(Player.Listener::class.java)
    player.addListener(listener)
    val replacementItem = MediaItem.fromUri("https://example.com/video.mp4")

    player.setMediaItem(replacementItem)

    verify(listener)
      .onTimelineChanged(player.currentTimeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
    verify(listener)
      .onMediaItemTransition(replacementItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
  }

  @Test
  fun setDuration_afterSetMediaItem_updatesDurationByMediaId() {
    val player = FakePlayer()
    player.setMediaItem(MediaItem.Builder().setMediaId("item_id").build())

    player.setDuration("item_id", durationMs = 10_000L)

    assertThat(player.duration).isEqualTo(10_000L)
  }

  @Test
  fun setDuration_afterSetMediaItem_updatesDurationByTimelineUid() {
    val player = FakePlayer()
    player.setMediaItem(MediaItem.fromUri("https://example.com/video.mp4"))
    val uid = player.currentTimeline.getUidOfPeriod(/* periodIndex= */ 0)

    player.setDuration(uid, durationMs = 10_000L)

    assertThat(player.duration).isEqualTo(10_000L)
  }

  @Test
  fun setOriginalDuration_afterSetDuration_isReportedByPeriodAndKeepsDuration() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("item_uid")
              .setMediaItem(MediaItem.Builder().setMediaId("item_id").build())
              .setPositionInFirstPeriodUs(200_000L)
              .setIsPlaceholder(true)
              .build()
          )
      )
    player.setDuration("item_id", durationMs = 800L)

    player.setOriginalDuration("item_id", originalDurationMs = 1_200L)

    val period = player.currentTimeline.getPeriod(/* periodIndex= */ 0, Timeline.Period())
    assertThat(period.originalDurationUs).isEqualTo(1_200_000L)
    assertThat(period.durationUs).isEqualTo(1_000_000L)
    assertThat(period.positionInWindowUs).isEqualTo(-200_000L)
    assertThat(period.isPlaceholder).isTrue()
    assertThat(player.duration).isEqualTo(800L)
  }

  @Test
  fun setDuration_afterSetOriginalDuration_updatesPeriodDurationAndKeepsOriginalDuration() {
    val player =
      FakePlayer(
        playlist =
          listOf(MediaItemData.Builder("item_uid").setPositionInFirstPeriodUs(200_000L).build())
      )
    player.setOriginalDuration("item_uid", originalDurationMs = 1_500L)
    val periodUid = player.currentTimeline.getUidOfPeriod(/* periodIndex= */ 0)

    player.setOriginalDuration(periodUid, originalDurationMs = 1_200L)
    player.setDuration(periodUid, durationMs = 800L)

    val period = player.currentTimeline.getPeriod(/* periodIndex= */ 0, Timeline.Period())
    assertThat(period.durationUs).isEqualTo(1_000_000L)
    assertThat(period.originalDurationUs).isEqualTo(1_200_000L)
    assertThat(player.duration).isEqualTo(800L)
  }

  @Test
  fun setDuration_withMultiplePeriods_throwsIllegalArgumentException() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("item_uid")
              .setPeriods(
                listOf(
                  PeriodData.Builder("period_1").build(),
                  PeriodData.Builder("period_2").build(),
                )
              )
              .build()
          )
      )

    assertThrows(IllegalArgumentException::class.java) {
      player.setDuration("item_uid", durationMs = 800L)
    }
  }

  @Test
  fun setOriginalDuration_withMultiplePeriods_throwsIllegalArgumentException() {
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("item_uid")
              .setPeriods(
                listOf(
                  PeriodData.Builder("period_1").build(),
                  PeriodData.Builder("period_2").build(),
                )
              )
              .build()
          )
      )

    assertThrows(IllegalArgumentException::class.java) {
      player.setOriginalDuration("item_uid", originalDurationMs = 1_000L)
    }
  }

  @Test
  fun play_withoutCommandPlayPauseAndThrowWhenCommandUnavailable_throwsIllegalStateException() {
    val player = FakePlayer(throwWhenCommandUnavailable = true)
    player.removeCommands(Player.COMMAND_PLAY_PAUSE)

    assertThrows(IllegalStateException::class.java) { player.play() }
  }

  @Test
  fun setMediaItem_withoutCommandSetMediaItemAndChangeMediaItemsAndThrowWhenCommandUnavailable_throwsIllegalStateException() {
    val player = FakePlayer(throwWhenCommandUnavailable = true)
    player.removeCommands(Player.COMMAND_SET_MEDIA_ITEM, Player.COMMAND_CHANGE_MEDIA_ITEMS)
    val mediaItem = MediaItem.fromUri("https://example.com/video.mp4")

    assertThrows(IllegalStateException::class.java) { player.setMediaItem(mediaItem) }
  }

  @Test
  fun setMediaItem_withoutCommandChangeMediaItemsAndThrowWhenCommandUnavailable_setsMediaItem() {
    val player = FakePlayer(throwWhenCommandUnavailable = true)
    player.removeCommands(Player.COMMAND_CHANGE_MEDIA_ITEMS)
    val mediaItem = MediaItem.fromUri("https://example.com/video.mp4")

    player.setMediaItem(mediaItem)

    assertThat(player.currentMediaItem).isEqualTo(mediaItem)
  }

  @Test
  fun setMediaItems_multiItemWithoutCommandChangeMediaItemsAndThrowWhenCommandUnavailable_throwsIllegalStateException() {
    val player = FakePlayer(throwWhenCommandUnavailable = true)
    player.removeCommands(Player.COMMAND_CHANGE_MEDIA_ITEMS)
    val mediaItems =
      listOf(
        MediaItem.Builder().setMediaId("1").setUri("https://example.com/video1.mp4").build(),
        MediaItem.Builder().setMediaId("2").setUri("https://example.com/video2.mp4").build(),
      )

    assertThrows(IllegalStateException::class.java) { player.setMediaItems(mediaItems) }
  }

  @Test
  fun seekTo_whenIdle_keepsIdleStateAndUpdatesPosition() {
    val player = FakePlayer(playlist = seekablePlaylist, bufferingDelayMs = 100)

    player.seekTo(/* positionMs= */ 500L)
    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

    assertThat(player.playbackState).isEqualTo(Player.STATE_IDLE)
    assertThat(player.currentPosition).isEqualTo(500L)
  }

  @Test
  fun seekTo_whenReadyWithZeroBufferingDelay_staysReadyAndUpdatesPosition() {
    val player = FakePlayer(playlist = seekablePlaylist, bufferingDelayMs = 0)
    player.prepare()

    player.seekTo(/* positionMs= */ 500L)

    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
    assertThat(player.currentPosition).isEqualTo(500L)
  }

  @Test
  fun seekTo_whenReadyWithBufferingDelay_buffersThenTransitionsToReady() {
    val player =
      FakePlayer(
        playbackState = Player.STATE_READY,
        playlist = seekablePlaylist,
        bufferingDelayMs = 100,
      )

    player.seekTo(/* positionMs= */ 500L)

    assertThat(player.playbackState).isEqualTo(Player.STATE_BUFFERING)
    assertThat(player.currentPosition).isEqualTo(500L)

    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

    assertThat(player.playbackState).isEqualTo(Player.STATE_READY)
  }

  @Test
  fun seekTo_whenEndedWithEmptyTimeline_staysEnded() {
    val player = FakePlayer(playbackState = Player.STATE_ENDED, bufferingDelayMs = 0)

    player.seekTo(/* mediaItemIndex= */ 0, /* positionMs= */ 500L)

    assertThat(player.playbackState).isEqualTo(Player.STATE_ENDED)
  }
}
