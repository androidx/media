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

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.state.SHOW_BUFFERING_ALWAYS
import androidx.media3.ui.compose.state.SHOW_BUFFERING_WHEN_PLAYING
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_TAG = "buffering_indicator"

/** Unit test for [BufferingIndicator]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class BufferingIndicatorTest {

  @Test
  fun bufferingIndicator_whenNotBuffering_isNotVisible() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = Player.STATE_READY,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    setContent {
      BufferingIndicator(
        player = player,
        delay = Duration.ZERO,
        content = { Box(Modifier.testTag(TEST_TAG).size(24.dp)) },
      )
    }

    onNodeWithTag(TEST_TAG).assertDoesNotExist()
  }

  @Test
  fun bufferingIndicator_whenBufferingAlways_showsAfterDelay() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = Player.STATE_BUFFERING,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    setContent {
      BufferingIndicator(
        player = player,
        displayMode = SHOW_BUFFERING_ALWAYS,
        delay = 500.milliseconds,
        content = { Box(Modifier.testTag(TEST_TAG).size(24.dp)) },
      )
    }

    onNodeWithTag(TEST_TAG).assertDoesNotExist()

    mainClock.advanceTimeBy(600)
    waitForIdle()

    onNodeWithTag(TEST_TAG).assertIsDisplayed()
  }

  @Test
  fun bufferingIndicator_whenBufferingWhenPlaying_andPaused_doesNotShow() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = Player.STATE_BUFFERING,
        playWhenReady = false,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    setContent {
      BufferingIndicator(
        player = player,
        displayMode = SHOW_BUFFERING_WHEN_PLAYING,
        delay = Duration.ZERO,
        content = { Box(Modifier.testTag(TEST_TAG).size(24.dp)) },
      )
    }

    onNodeWithTag(TEST_TAG).assertDoesNotExist()
  }

  @Test
  fun bufferingIndicator_whenBufferingWhenPlaying_andPlaying_showsAfterDelay() = runComposeUiTest {
    val player =
      FakePlayer(
        playbackState = Player.STATE_BUFFERING,
        playWhenReady = true,
        playlist = listOf(MediaItemData.Builder("item").build()),
      )

    setContent {
      BufferingIndicator(
        player = player,
        displayMode = SHOW_BUFFERING_WHEN_PLAYING,
        delay = 500.milliseconds,
        content = { Box(Modifier.testTag(TEST_TAG).size(24.dp)) },
      )
    }

    onNodeWithTag(TEST_TAG).assertDoesNotExist()

    mainClock.advanceTimeBy(600)
    waitForIdle()

    onNodeWithTag(TEST_TAG).assertIsDisplayed()
  }

  @Test
  fun bufferingIndicator_whenBufferingTransitionsToReadyBeforeDelay_doesNotShow() =
    runComposeUiTest {
      val player =
        FakePlayer(
          playbackState = Player.STATE_BUFFERING,
          playWhenReady = true,
          playlist = listOf(MediaItemData.Builder("item").build()),
        )

      setContent {
        BufferingIndicator(
          player = player,
          delay = 500.milliseconds,
          content = { Box(Modifier.testTag(TEST_TAG).size(24.dp)) },
        )
      }

      onNodeWithTag(TEST_TAG).assertDoesNotExist()

      mainClock.advanceTimeBy(200)
      player.setPlaybackState(Player.STATE_READY)
      waitForIdle()

      mainClock.advanceTimeBy(500)
      waitForIdle()

      onNodeWithTag(TEST_TAG).assertDoesNotExist()
    }
}
