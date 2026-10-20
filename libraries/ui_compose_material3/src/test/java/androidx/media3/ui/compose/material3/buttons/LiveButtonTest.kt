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

package androidx.media3.ui.compose.material3.buttons

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player.COMMAND_SEEK_TO_DEFAULT_POSITION
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [LiveButton]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LiveButtonTest {

  @Test
  fun notLiveStream_buttonNotDisplayed() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("NotLiveItem").build()),
      )

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertDoesNotExist()
  }

  @Test
  fun liveStream_buttonDisplayed() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(1000L)
        .setWindowStartTimeMs(1000L)
        .setElapsedRealtimeEpochOffsetMs(0L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsDisplayed()
  }

  @Test
  fun onClick_invokesSeekToDefaultPosition() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(50_000L - SystemClock.elapsedRealtime())
        .setDefaultPositionUs(50_000_000L)
        .setDurationUs(50_000_000L)
        .setIsSeekable(true)
        .build()
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        bufferingDelayMs = 0,
        playlist = listOf(liveItem),
      )
    player.setPosition(10_000)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsEnabled()
    onNodeWithTag("liveButton").performClick()

    assertThat(player.currentPosition).isEqualTo(50_000)
    onNodeWithTag("liveButton").assertIsNotEnabled()
  }

  @Test
  fun liveStream_commandNotAvailable_buttonNotDisplayed() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(50_000L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(5_000)
    player.removeCommands(COMMAND_SEEK_TO_DEFAULT_POSITION)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertDoesNotExist()
  }

  @Test
  fun liveStream_commandRemovedDynamically_hidesButton() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(50_000L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(5_000)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }
    onNodeWithTag("liveButton").assertIsDisplayed()

    player.removeCommands(COMMAND_SEEK_TO_DEFAULT_POSITION)
    waitForIdle()

    onNodeWithTag("liveButton").assertDoesNotExist()
  }

  @Test
  fun liveStream_withSlidingWindowAndNonZeroEpochOffset_buttonDisplayed() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("SlidingLiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(100_000L)
        .setWindowStartTimeMs(150_000L)
        .setElapsedRealtimeEpochOffsetMs(500_000L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsDisplayed()
  }

  @Test
  fun onClick_withSlidingWindowAndNonZeroEpochOffset_invokesSeekToDefaultPosition() =
    runComposeUiTest {
      val liveItem =
        MediaItemData.Builder("SlidingLiveItem")
          .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
          .setPresentationStartTimeMs(100_000L)
          .setWindowStartTimeMs(150_000L)
          .setElapsedRealtimeEpochOffsetMs(200_000L - SystemClock.elapsedRealtime())
          .setDefaultPositionUs(45_000_000L)
          .setDurationUs(50_000_000L)
          .setIsSeekable(true)
          .build()
      val player =
        FakePlayer(
          playbackState = STATE_READY,
          playWhenReady = false,
          bufferingDelayMs = 0,
          playlist = listOf(liveItem),
        )
      player.setPosition(10_000)

      setContent { LiveButton(player, Modifier.testTag("liveButton")) }

      onNodeWithTag("liveButton").assertIsEnabled()
      onNodeWithTag("liveButton").performClick()

      assertThat(player.currentPosition).isEqualTo(45_000)
      onNodeWithTag("liveButton").assertIsNotEnabled()
    }

  @Test
  fun customContent_trailingLambdaReceivesStateAndRenders() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(1000L)
        .setWindowStartTimeMs(1000L)
        .setElapsedRealtimeEpochOffsetMs(0L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))

    setContent {
      LiveButton(player, Modifier.testTag("liveButton")) { Text("CUSTOM LIVE: isLive=$isLive") }
    }

    onNodeWithTag("liveButton").assertIsDisplayed()
    onNodeWithTag("liveButton").assertTextEquals("CUSTOM LIVE: isLive=true")
  }

  @Test
  fun atLiveEdge_buttonDisabled() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(-SystemClock.elapsedRealtime())
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(0)

    setContent { LiveButton(player, Modifier.testTag("liveButton"), liveEdgeThresholdMs = 10_000L) }

    onNodeWithTag("liveButton").assertIsNotEnabled()
  }

  @Test
  fun notAtLiveEdge_buttonEnabled() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(50_000L - SystemClock.elapsedRealtime())
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(0)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsEnabled()
  }

  @Test
  fun atLiveEdge_withConfiguredTargetLiveOffset_buttonDisabled() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItemWithTarget")
        .setLiveConfiguration(
          MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(20_000L).build()
        )
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(20_000L - SystemClock.elapsedRealtime())
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(0)

    setContent { LiveButton(player, Modifier.testTag("liveButton"), liveEdgeThresholdMs = 1_000L) }

    onNodeWithTag("liveButton").assertIsNotEnabled()
  }

  @Test
  fun liveStream_withoutWindowStartTimeAtLiveEdge_buttonDisabled() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("HlsLiveItemWithoutWindowStartTime")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setDefaultPositionUs(45_000_000L)
        .setDurationUs(50_000_000L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(45_000)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsNotEnabled()
  }

  @Test
  fun liveStream_withoutWindowStartTimeNotAtLiveEdge_buttonEnabled() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("HlsLiveItemWithoutWindowStartTime")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setDefaultPositionUs(45_000_000L)
        .setDurationUs(50_000_000L)
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(10_000)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsEnabled()
  }

  @Test
  fun onClick_withoutWindowStartTime_seeksToDefaultPositionAndDisablesButton() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("HlsLiveItemWithoutWindowStartTime")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setDefaultPositionUs(45_000_000L)
        .setDurationUs(50_000_000L)
        .setIsSeekable(true)
        .build()
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        bufferingDelayMs = 0,
        playlist = listOf(liveItem),
      )
    player.setPosition(10_000)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsEnabled()
    onNodeWithTag("liveButton").performClick()

    assertThat(player.currentPosition).isEqualTo(45_000)
    onNodeWithTag("liveButton").assertIsNotEnabled()
  }

  @Test
  fun liveStream_withinDefaultThresholdBehindTarget_buttonDisabled() = runComposeUiTest {
    // Without playback speed control, players typically settle a segment duration behind the
    // target live offset.
    val liveItem =
      MediaItemData.Builder("LiveItemWithTarget")
        .setLiveConfiguration(
          MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(30_000L).build()
        )
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(32_500L - SystemClock.elapsedRealtime())
        .build()
    val player =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    player.setPosition(0)

    setContent { LiveButton(player, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertIsNotEnabled()
  }

  @Test
  fun liveStream_playingAd_hidesButton() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(50_000L)
        .build()
    val basePlayer =
      FakePlayer(playbackState = STATE_READY, playWhenReady = false, playlist = listOf(liveItem))
    val adPlayingPlayer =
      object : ForwardingPlayer(basePlayer) {
        override fun isPlayingAd(): Boolean = true
      }

    setContent { LiveButton(adPlayingPlayer, Modifier.testTag("liveButton")) }

    onNodeWithTag("liveButton").assertDoesNotExist()
  }

  @Test
  fun liveButton_withCustomParameters_emitsInteractions() = runComposeUiTest {
    val liveItem =
      MediaItemData.Builder("LiveItem")
        .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().build())
        .setPresentationStartTimeMs(0L)
        .setWindowStartTimeMs(0L)
        .setElapsedRealtimeEpochOffsetMs(50_000L - SystemClock.elapsedRealtime())
        .setDefaultPositionUs(50_000_000L)
        .setDurationUs(50_000_000L)
        .setIsSeekable(true)
        .build()
    val player =
      FakePlayer(
        playbackState = STATE_READY,
        playWhenReady = false,
        bufferingDelayMs = 0,
        playlist = listOf(liveItem),
      )
    player.setPosition(10_000)
    val interactions = mutableListOf<Interaction>()
    setContent {
      val interactionSource = remember { MutableInteractionSource() }
      LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interactions.add(it) }
      }
      LiveButton(
        player,
        Modifier.testTag("liveButton"),
        shape = RectangleShape,
        elevation = ButtonDefaults.buttonElevation(),
        border = BorderStroke(1.dp, Color.Red),
        contentPadding = PaddingValues(4.dp),
        interactionSource = interactionSource,
      )
    }

    onNodeWithTag("liveButton").performClick()

    assertThat(player.currentPosition).isEqualTo(50_000)
    assertThat(interactions.filterIsInstance<PressInteraction.Release>()).isNotEmpty()
  }
}
