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

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Unit test for [CurrentMediaItemState]. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CurrentMediaItemStateTest {

  @Test
  fun initialState_withNullPlayer_hasDefaultValues() = runComposeUiTest {
    lateinit var state: CurrentMediaItemState
    setContent { state = rememberCurrentMediaItemState(player = null) }

    assertThat(state.mediaItem).isNull()
    assertThat(state.mediaMetadata).isEqualTo(MediaMetadata.EMPTY)
    assertThat(state.isLive).isFalse()
    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
  }

  @Test
  fun playerWithItem_updatesStateCorrectly() = runComposeUiTest {
    val testMediaMetadata = MediaMetadata.Builder().setTitle("Test Title").build()
    val player =
      FakePlayer(
        playlist =
          ImmutableList.of(
            MediaItemData.Builder("id")
              .setDurationUs(1_000_000L)
              .setMediaMetadata(testMediaMetadata)
              .build()
          )
      )
    lateinit var state: CurrentMediaItemState
    setContent { state = rememberCurrentMediaItemState(player = player) }

    assertThat(state.mediaMetadata).isEqualTo(testMediaMetadata)
    assertThat(state.isLive).isFalse()
    assertThat(state.durationMs).isEqualTo(1000)
  }

  @Test
  fun playerMetadataChange_updatesMetadata() = runComposeUiTest {
    val initialMetadata = MediaMetadata.Builder().setTitle("Initial").build()
    val updatedMetadata = MediaMetadata.Builder().setTitle("Updated").build()
    val player =
      FakePlayer(
        playlist =
          ImmutableList.of(
            MediaItemData.Builder("id1").setMediaMetadata(initialMetadata).build(),
            MediaItemData.Builder("id2").setMediaMetadata(updatedMetadata).build(),
          )
      )
    lateinit var state: CurrentMediaItemState
    setContent { state = rememberCurrentMediaItemState(player = player) }

    assertThat(state.mediaMetadata).isEqualTo(initialMetadata)

    player.seekToNext()
    waitForIdle()

    assertThat(state.mediaMetadata).isEqualTo(updatedMetadata)
  }

  @Test
  fun playerLiveState_updatesStateCorrectly() = runComposeUiTest {
    val player =
      FakePlayer(
        playlist =
          ImmutableList.of(
            MediaItemData.Builder("live")
              .setMediaItem(
                MediaItem.Builder().setMediaId("live").setUri("http://example.com/live").build()
              )
              .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(C.TIME_UNSET).build()
              )
              .setIsDynamic(true)
              .setDurationUs(C.TIME_UNSET)
              .build()
          )
      )
    lateinit var state: CurrentMediaItemState
    setContent { state = rememberCurrentMediaItemState(player = player) }

    assertThat(state.isLive).isTrue()
    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
  }

  @Test
  fun commandChanges_updateProperties() = runComposeUiTest {
    val testMediaMetadata = MediaMetadata.Builder().setTitle("Test Title").build()
    val player =
      FakePlayer(
        playlist =
          ImmutableList.of(
            MediaItemData.Builder("id")
              .setDurationUs(1_000_000L)
              .setMediaMetadata(testMediaMetadata)
              .build()
          )
      )
    lateinit var state: CurrentMediaItemState
    setContent { state = rememberCurrentMediaItemState(player = player) }

    assertThat(state.durationMs).isEqualTo(1000)
    assertThat(state.mediaMetadata).isEqualTo(testMediaMetadata)

    // Remove metadata access, but not media item
    player.removeCommands(Player.COMMAND_GET_METADATA)
    waitForIdle()

    assertThat(state.durationMs).isEqualTo(1000)
    assertThat(state.mediaMetadata).isEqualTo(MediaMetadata.EMPTY)

    // Both are removed
    player.removeCommands(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    waitForIdle()

    assertThat(state.durationMs).isEqualTo(C.TIME_UNSET)
    assertThat(state.mediaMetadata).isEqualTo(MediaMetadata.EMPTY)

    // Both are added back
    player.addCommands(Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Player.COMMAND_GET_METADATA)
    waitForIdle()

    assertThat(state.durationMs).isEqualTo(1000)
    assertThat(state.mediaMetadata).isEqualTo(testMediaMetadata)
  }
}
