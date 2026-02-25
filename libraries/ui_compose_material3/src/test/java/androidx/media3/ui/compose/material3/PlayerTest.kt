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

package androidx.media3.ui.compose.material3

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.media3.test.utils.FakePlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [androidx.media3.ui.compose.material3.Player] Composable. */
@RunWith(AndroidJUnit4::class)
class PlayerTest {

  @get:Rule val composeTestRule = createComposeRule()
  private val context: Context = ApplicationProvider.getApplicationContext()
  private val playerTestTag = "player_under_test"

  private fun findTopControls() =
    composeTestRule.onNode(
      hasContentDescription(context.getString(R.string.mute_button_shown_muted)) or
        hasContentDescription(context.getString(R.string.mute_button_shown_unmuted))
    )

  private fun findCenterControls() =
    composeTestRule.onNode(
      hasContentDescription(context.getString(R.string.playpause_button_play)) or
        hasContentDescription(context.getString(R.string.playpause_button_pause))
    )

  private fun findBottomControls() =
    composeTestRule.onNode(
      hasContentDescription(context.getString(R.string.shuffle_button_shuffle_on)) or
        hasContentDescription(context.getString(R.string.shuffle_button_shuffle_off))
    )

  @Test
  fun player_controlsVisibleInitially() {
    composeTestRule.setContent { Player(player = FakePlayer()) }

    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()
  }

  @Test
  fun player_controlsHideAfterTimeout() {
    composeTestRule.setContent { Player(player = FakePlayer(), controlsTimeoutMs = 3000) }

    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()

    composeTestRule.mainClock.advanceTimeBy(500)

    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()

    composeTestRule.mainClock.advanceTimeBy(2600)

    findTopControls().assertDoesNotExist()
    findCenterControls().assertDoesNotExist()
    findBottomControls().assertDoesNotExist()
  }

  @Test
  fun player_tapShowsHiddenControls() {
    composeTestRule.setContent {
      Player(
        player = FakePlayer(),
        modifier = Modifier.testTag(playerTestTag),
        controlsTimeoutMs = 3000,
      )
    }
    composeTestRule.mainClock.advanceTimeBy(3100)
    findTopControls().assertDoesNotExist()
    findCenterControls().assertDoesNotExist()
    findBottomControls().assertDoesNotExist()

    composeTestRule.onNodeWithTag(playerTestTag).performClick()

    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()
  }

  @Test
  fun player_interactionPreventsHiding() {
    val timeoutMs = 1000L
    composeTestRule.setContent {
      Player(
        player = FakePlayer(),
        modifier = Modifier.testTag(playerTestTag),
        controlsTimeoutMs = timeoutMs,
      )
    }

    composeTestRule.onNodeWithTag(playerTestTag).performTouchInput { down(Offset(0f, 0f)) }
    composeTestRule.mainClock.advanceTimeBy(timeoutMs + 500)

    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()

    composeTestRule.onNodeWithTag(playerTestTag).performTouchInput { up() }
    composeTestRule.mainClock.advanceTimeBy(timeoutMs + 500)

    findTopControls().assertDoesNotExist()
    findCenterControls().assertDoesNotExist()
    findBottomControls().assertDoesNotExist()
  }

  @Test
  fun player_controlsTimeoutZero_controlsAlwaysVisible() {
    composeTestRule.setContent { Player(player = FakePlayer(), controlsTimeoutMs = 0L) }
    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()

    composeTestRule.mainClock.advanceTimeBy(5000)

    findTopControls().assertIsDisplayed()
    findCenterControls().assertIsDisplayed()
    findBottomControls().assertIsDisplayed()
  }

  @Test
  fun player_customControls_areDisplayedAndAlignedCorrectly() {
    val player = FakePlayer()
    var tolerance = 1f
    composeTestRule.setContent {
      tolerance = with(LocalDensity.current) { 1.dp.toPx() }
      Player(
        player,
        Modifier.testTag(playerTestTag),
        controlsTimeoutMs = 1000000,
        topControls = { _, _ -> Box(Modifier.testTag("topControls")) { BasicText("Top") } },
        centerControls = { _, _ ->
          Box(Modifier.testTag("centerControls")) { BasicText("Center") }
        },
        bottomControls = { _, _ -> Box(Modifier.testTag("bottomControls")) { BasicText("Bottom") } },
      )
    }

    composeTestRule.onNodeWithTag(playerTestTag).assertExists()
    composeTestRule.onNodeWithTag("topControls", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("centerControls", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("bottomControls", useUnmergedTree = true).assertIsDisplayed()

    val playerBounds =
      composeTestRule.onNodeWithTag(playerTestTag).fetchSemanticsNode().boundsInRoot
    val topControlsBounds =
      composeTestRule
        .onNodeWithTag("topControls", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    val centerControlsBounds =
      composeTestRule
        .onNodeWithTag("centerControls", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    val bottomControlsBounds =
      composeTestRule
        .onNodeWithTag("bottomControls", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot

    Truth.assertThat(topControlsBounds.top).isWithin(tolerance).of(playerBounds.top)
    Truth.assertThat(centerControlsBounds.center.x).isWithin(tolerance).of(playerBounds.center.x)
    Truth.assertThat(centerControlsBounds.center.y).isWithin(tolerance).of(playerBounds.center.y)
    Truth.assertThat(bottomControlsBounds.bottom).isWithin(tolerance).of(playerBounds.bottom)
  }

  @Test
  fun player_customControls_reactToPlayerChange() {
    val player = FakePlayer()
    lateinit var isPlayerNull: MutableState<Boolean>
    composeTestRule.setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      Player(
        player = if (isPlayerNull.value) null else player,
        Modifier.testTag(playerTestTag),
        topControls = { player, _ ->
          val tag = if (player != null) "topControlsWithPlayer" else "topControlsWithoutPlayer"
          Box(Modifier.testTag(tag)) { BasicText("Top") }
        },
        centerControls = { player, _ ->
          val tag =
            if (player != null) "centerControlsWithPlayer" else "centerControlsWithoutPlayer"
          Box(Modifier.testTag(tag)) { BasicText("Center") }
        },
        bottomControls = { player, _ ->
          val tag =
            if (player != null) "bottomControlsWithPlayer" else "bottomControlsWithoutPlayer"
          Box(Modifier.testTag(tag)) { BasicText("Bottom") }
        },
      )
    }

    composeTestRule.onNodeWithTag(playerTestTag).assertExists()
    composeTestRule
      .onNodeWithTag("topControlsWithPlayer", useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("centerControlsWithPlayer", useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("bottomControlsWithPlayer", useUnmergedTree = true)
      .assertIsDisplayed()

    isPlayerNull.value = true
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("topControlsWithoutPlayer", useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("centerControlsWithoutPlayer", useUnmergedTree = true)
      .assertIsDisplayed()
    composeTestRule
      .onNodeWithTag("bottomControlsWithoutPlayer", useUnmergedTree = true)
      .assertIsDisplayed()
  }

  @Test
  fun player_customShutter_reactToPlayerChange() {
    val player = FakePlayer()
    lateinit var isPlayerNull: MutableState<Boolean>
    composeTestRule.setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      Player(
        player = if (isPlayerNull.value) null else player,
        Modifier.testTag(playerTestTag),
        shutter = { Box(Modifier.testTag("customShutter").fillMaxSize()) },
      )
    }
    player.renderFirstFrame(true)
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customShutter", useUnmergedTree = true).assertIsNotDisplayed()

    isPlayerNull.value = true
    composeTestRule.waitForIdle()

    composeTestRule.onNodeWithTag("customShutter", useUnmergedTree = true).assertIsDisplayed()
  }
}
