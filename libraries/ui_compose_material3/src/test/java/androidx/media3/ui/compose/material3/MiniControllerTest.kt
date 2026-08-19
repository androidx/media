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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import androidx.media3.test.utils.FakePlayer
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [MiniController]. */
@RunWith(AndroidJUnit4::class)
class MiniControllerTest {
  @get:Rule val composeTestRule = createComposeRule()

  private val miniControllerTag = "mini_controller"

  @Test
  fun miniController_initiallyVisible() {
    composeTestRule.setContent {
      MiniController(player = FakePlayer(), modifier = Modifier.testTag(miniControllerTag))
    }

    composeTestRule.onNodeWithTag(miniControllerTag).assertIsDisplayed()
  }

  @Test
  fun miniController_onClick_invokesOnClick() {
    var clicked = false
    composeTestRule.setContent {
      MiniController(
        player = FakePlayer(),
        modifier = Modifier.testTag(miniControllerTag),
        onClick = { clicked = true },
      )
    }
    composeTestRule.onNodeWithTag(miniControllerTag).performClick()

    assertThat(clicked).isTrue()
  }

  @Test
  fun miniController_customPlayerControls_invokesCustomPlayerControls() {
    composeTestRule.setContent {
      MiniController(
        player = FakePlayer(),
        modifier = Modifier.testTag(miniControllerTag),
        playerControls = { PreviousButton(it, modifier = Modifier.testTag("previous_button")) },
      )
    }
    composeTestRule.onNodeWithTag("previous_button").assertIsDisplayed()
  }

  @Test
  fun miniController_displaysTitleAndArtist() {
    val mediaMetadata =
      MediaMetadata.Builder().setTitle("Sample Title").setArtist("Sample Artist").build()
    val player =
      FakePlayer(
        playlist = listOf(MediaItemData.Builder("First").setMediaMetadata(mediaMetadata).build())
      )

    composeTestRule.setContent { MiniController(player) }
    composeTestRule.onNodeWithText("Sample Title", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sample Artist", substring = true).assertIsDisplayed()
  }

  @Test
  fun miniController_withNullPlayer_isDisplayed() {
    composeTestRule.setContent {
      MiniController(player = null, modifier = Modifier.testTag(miniControllerTag))
    }

    composeTestRule.onNodeWithTag(miniControllerTag).assertIsDisplayed()
  }

  @Test
  fun miniController_customArtwork_reactsToPlayerChange() {
    val textTrack =
      Tracks.Group(
        TrackGroup(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()),
        /* adaptiveSupported= */ true,
        /* trackSupport= */ intArrayOf(C.FORMAT_HANDLED),
        /* trackSelected= */ booleanArrayOf(true),
      )
    val mediaMetadata =
      MediaMetadata.Builder().setTitle("Sample Title").setArtist("Sample Artist").build()
    val player =
      FakePlayer(
        playlist =
          listOf(
            MediaItemData.Builder("First")
              .setMediaMetadata(mediaMetadata)
              .setTracks(Tracks(listOf(textTrack)))
              .build()
          )
      )

    lateinit var isPlayerNull: MutableState<Boolean>
    composeTestRule.setContent {
      isPlayerNull = remember { mutableStateOf(false) }
      MiniController(
        player = if (isPlayerNull.value) null else player,
        modifier = Modifier.testTag(miniControllerTag),
        artwork = { p ->
          val tag = if (p != null) "artworkWithPlayer" else "artworkWithoutPlayer"
          Box(modifier = Modifier.size(100.dp).testTag(tag))
        },
      )
    }

    composeTestRule.onNodeWithTag("artworkWithPlayer", useUnmergedTree = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sample Title", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithText("Sample Artist", substring = true).assertIsDisplayed()

    isPlayerNull.value = true
    composeTestRule.waitForIdle()

    composeTestRule
      .onNodeWithTag("artworkWithoutPlayer", useUnmergedTree = true)
      .assertIsDisplayed()
  }
}
