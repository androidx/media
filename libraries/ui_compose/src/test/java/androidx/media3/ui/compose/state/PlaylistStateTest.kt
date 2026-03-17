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

package androidx.media3.ui.compose.state

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [PlaylistState]. */
@RunWith(AndroidJUnit4::class)
class PlaylistStateTest {

  @get:Rule val composeTestRule = createComposeRule()

  @Test
  fun initialState_withNullPlayer_hasDefaultValues() {
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = null) }

    assertThat(state.mediaItemCount).isEqualTo(0)
    assertThat(state.currentMediaItemIndex).isEqualTo(C.INDEX_UNSET)
    assertThat(state.playlistMetadata).isEqualTo(MediaMetadata.EMPTY)
  }

  @Test
  fun playerWithItems_updatesStateCorrectly() {
    val mediaItem1 = MediaItem.Builder().setMediaId("id1").build()
    val mediaItem2 = MediaItem.Builder().setMediaId("id2").build()
    val player = FakePlayer()
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = player) }

    player.setMediaItems(ImmutableList.of(mediaItem1, mediaItem2))
    composeTestRule.waitForIdle()

    assertThat(state.mediaItemCount).isEqualTo(2)
    assertThat(state.currentMediaItemIndex).isEqualTo(0)
    assertThat(state.getMediaItemAt(0)).isEqualTo(mediaItem1)
    assertThat(state.getMediaItemAt(1)).isEqualTo(mediaItem2)
  }

  @Test
  fun get_withWrongIndex_throwsIndexOutOfBoundsException() {
    val mediaItem1 = MediaItem.Builder().setMediaId("id1").build()
    val mediaItem2 = MediaItem.Builder().setMediaId("id2").build()
    val player = FakePlayer()
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = player) }

    player.setMediaItems(ImmutableList.of(mediaItem1, mediaItem2))
    composeTestRule.waitForIdle()

    assertThrows(IndexOutOfBoundsException::class.java) {
      val unused = state.getMediaItemAt(-1)
    }

    assertThrows(IndexOutOfBoundsException::class.java) {
      val unused = state.getMediaItemAt(2)
    }
  }

  @Test
  fun playerMetadata_updatesStateCorrectly() {
    val testMediaMetadata = MediaMetadata.Builder().setTitle("Test Title").build()
    val testPlaylistMetadata = MediaMetadata.Builder().setAlbumTitle("Playlist Album").build()
    val mediaItemWithMetadata =
      MediaItem.Builder().setMediaId("id1").setMediaMetadata(testMediaMetadata).build()
    val player = FakePlayer()
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = player) }

    player.playlistMetadata = testPlaylistMetadata
    player.setMediaItems(ImmutableList.of(mediaItemWithMetadata))
    player.prepare()
    composeTestRule.waitForIdle()

    assertThat(state.playlistMetadata).isEqualTo(testPlaylistMetadata)
  }

  @Test
  fun seekToMediaItem_callsPlayerSeekTo() {
    val mediaItem1 = MediaItem.Builder().setMediaId("id1").build()
    val mediaItem2 = MediaItem.Builder().setMediaId("id2").build()
    val player = FakePlayer()
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = player) }

    player.setMediaItems(ImmutableList.of(mediaItem1, mediaItem2))
    player.prepare()
    composeTestRule.waitForIdle()

    state.seekToMediaItem(1)
    composeTestRule.waitForIdle()
    assertThat(player.currentMediaItemIndex).isEqualTo(1)
  }

  @Test
  fun seekToMediaItem_withoutCommand_doesNothing() {
    val mediaItem1 = MediaItem.Builder().setMediaId("id1").build()
    val player = FakePlayer()
    player.removeCommands(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
    player.addCommands(Player.COMMAND_GET_TIMELINE)
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = player) }

    player.setMediaItems(ImmutableList.of(mediaItem1))
    player.prepare()
    composeTestRule.waitForIdle()

    val initialIndex = player.currentMediaItemIndex
    state.seekToMediaItem(0)
    composeTestRule.waitForIdle()
    assertThat(player.currentMediaItemIndex).isEqualTo(initialIndex)
  }

  @Test
  fun commandChanges_updateProperties() {
    val mediaItem1 = MediaItem.Builder().setMediaId("id1").build()
    val player = FakePlayer()
    player.setMediaItems(ImmutableList.of(mediaItem1))
    player.prepare()
    player.removeCommands(Player.COMMAND_GET_TIMELINE)
    lateinit var state: PlaylistState
    composeTestRule.setContent { state = rememberPlaylistState(player = player) }

    assertThat(state.mediaItemCount).isEqualTo(0)

    player.addCommands(Player.COMMAND_GET_TIMELINE)
    composeTestRule.waitForIdle()
    assertThat(state.mediaItemCount).isEqualTo(1)

    player.removeCommands(Player.COMMAND_GET_TIMELINE)
    composeTestRule.waitForIdle()
    assertThat(state.mediaItemCount).isEqualTo(0)
  }
}
