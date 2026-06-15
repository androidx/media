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
package androidx.media3.ui.compose.lifecycle

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.MediaItem
import androidx.media3.common.PlayerPool
import androidx.media3.test.utils.StubExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class RememberPooledPlayerTest {

  class TrackingPlayer : StubExoPlayer() {
    var isPlayingState = false
    var isSetup = false
    var stopCount = 0
    var releaseCount = 0

    override fun setPlayWhenReady(playWhenReady: Boolean) {
      isPlayingState = playWhenReady
    }

    override fun getPlayWhenReady(): Boolean = isPlayingState

    override fun stop() {
      stopCount++
    }

    override fun removeMediaItems(fromIndex: Int, toIndex: Int) {}

    override fun release() {
      releaseCount++
    }
  }

  private val mediaItem = MediaItem.fromUri("asset:///test.mp4")

  @Test
  fun acquiresPlayer_callsSetup_andPlaysWhenActive() = runComposeUiTest {
    val pool = PlayerPool(1) { TrackingPlayer() }
    var providedPlayer: TrackingPlayer? = null

    setContent {
      providedPlayer =
        rememberPooledPlayer(mediaItem, pool, playerSetup = { p -> p.isSetup = true })
      LaunchedEffect(providedPlayer?.isSetup) {
        if (providedPlayer?.isSetup == true) providedPlayer?.play()
      }
    }

    runOnIdle {
      assertThat(providedPlayer).isNotNull()
      assertThat(providedPlayer!!.isSetup).isTrue()
      assertThat(providedPlayer!!.isPlayingState).isTrue()
    }
  }

  @Test
  fun returnsPlayerToPool_onDispose() = runComposeUiTest {
    val pool = PlayerPool(1) { TrackingPlayer() }
    var showPlayer by mutableStateOf(true)
    var providedPlayer: TrackingPlayer? = null

    setContent {
      if (showPlayer) {
        providedPlayer =
          rememberPooledPlayer(
            mediaItem = mediaItem,
            playerPool = pool,
            playerSetup = {},
            playerTeardown = { it.stop() },
          )
      }
    }

    runOnIdle { assertThat(providedPlayer).isNotNull() }

    showPlayer = false

    // stop() is called both by the teardown lambda and internally by the pool
    runOnIdle { assertThat(providedPlayer!!.stopCount).isEqualTo(2) }
  }

  @Test
  fun resourceIsolation_preventsLeak_onUIStateMutation() = runComposeUiTest {
    var activePlayersInPool = 0
    val trackingPool =
      PlayerPool(1) {
        activePlayersInPool++
        TrackingPlayer()
      }
    var showPlayer by mutableStateOf(true)
    setContent {
      if (showPlayer) {
        @Suppress("UNUSED_VARIABLE")
        val player = rememberPooledPlayer(mediaItem, trackingPool, playerSetup = {})
      }
    }
    assertThat(activePlayersInPool).isEqualTo(1)

    showPlayer = false
    waitForIdle()

    val recoveredPlayer = trackingPool.acquire()

    assertThat(recoveredPlayer).isNotNull()
    assertThat(activePlayersInPool).isEqualTo(1)
  }
}
