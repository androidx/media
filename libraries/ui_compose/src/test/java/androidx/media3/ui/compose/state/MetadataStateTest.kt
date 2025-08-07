/*
 * Copyright 2025 The Android Open Source Project
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

package androidx.media3.ui.compose.state

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.ui.compose.utils.TestPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [MetadataState]. */
@RunWith(AndroidJUnit4::class)
class MetadataStateTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun uri_emptyPlaylist_returnsNull() {
    val player = TestPlayer(
      playbackState = Player.STATE_IDLE,
      playlist = emptyList(),
    )

    lateinit var state: MetadataState
    composeTestRule.setContent { state = rememberMetadataState(player) }

    assertThat(state.uri).isNull()
  }

  @Test
  fun uri_singleItemWithoutUri_returnsNull() {
    val player = TestPlayer(
      playlist = listOf(
        MediaItemData.Builder("uid_1").build(),
      ),
    )

    lateinit var state: MetadataState
    composeTestRule.setContent { state = rememberMetadataState(player) }

    assertThat(state.uri).isNull()
  }

  @Test
  fun uri_singleItemWithUri_returnsTheUri() {
    val uri = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd".toUri()
    val player = TestPlayer(
      playlist = listOf(
        MediaItemData.Builder("uid_1").setMediaItem(MediaItem.fromUri(uri)).build(),
      ),
    )

    lateinit var state: MetadataState
    composeTestRule.setContent { state = rememberMetadataState(player) }

    assertThat(state.uri).isEqualTo(uri)
  }

  @Test
  fun uri_transitionBetweenItems_returnsUpdatedUri() {
    val uri1 = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd".toUri()
    val uri2 =
      "https://storage.googleapis.com/exoplayer-test-media-1/mp4/dizzy-with-tx3g.mp4".toUri()
    val player = TestPlayer(
      playlist = listOf(
        MediaItemData.Builder("uid_1").setMediaItem(MediaItem.fromUri(uri1)).build(),
        MediaItemData.Builder("uid_2").build(),
        MediaItemData.Builder("uid_3").setMediaItem(MediaItem.fromUri(uri2)).build(),
      ),
    )

    lateinit var state: MetadataState
    composeTestRule.setContent { state = rememberMetadataState(player) }

    assertThat(state.uri).isEqualTo(uri1)

    player.seekToNext()
    composeTestRule.waitForIdle()

    assertThat(state.uri).isNull()

    player.seekToNext()
    composeTestRule.waitForIdle()

    assertThat(state.uri).isEqualTo(uri2)
  }

  @Test
  fun uri_getCurrentMediaItemCommandBecomesAvailable_returnsUpdatedUri() {
    val uri = "https://storage.googleapis.com/wvmedia/clear/h264/tears/tears.mpd".toUri()
    val player = TestPlayer(
      playlist = listOf(
        MediaItemData.Builder("uid_1").setMediaItem(MediaItem.fromUri(uri)).build(),
      ),
    )
    player.removeCommands(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)

    lateinit var state: MetadataState
    composeTestRule.setContent { state = rememberMetadataState(player) }

    assertThat(state.uri).isNull()

    player.addCommands(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    composeTestRule.waitForIdle()

    assertThat(state.uri).isEqualTo(uri)
  }
}
