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

import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer.MediaItemData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

/** Unit tests for [FakePlayer]. */
@RunWith(AndroidJUnit4::class)
class FakePlayerTest {

  private val seekablePlaylist = listOf(MediaItemData.Builder("item").setIsSeekable(true).build())

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
