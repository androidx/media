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

import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.ui.compose.testutils.createReadyPlayerWithTwoItems
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Unit test for [PlayerStateObserver]. */
@RunWith(AndroidJUnit4::class)
class PlayerStateObserverTest {

  @Test
  fun createPlayerStateObserver_triggersInitialStateUpdate() = runTest {
    val player = createReadyPlayerWithTwoItems()
    var stateUpdateCount = 0
    val unused =
      player.observeState(
        Player.EVENT_PLAYBACK_STATE_CHANGED,
        Player.EVENT_PLAY_WHEN_READY_CHANGED,
      ) {
        stateUpdateCount++
      }

    assertThat(stateUpdateCount).isEqualTo(1)
  }

  @Test
  fun observe_triggersInitialStateUpdate() = runTest {
    val player = createReadyPlayerWithTwoItems()
    var stateUpdateCount = 0
    val stateObserver =
      player.observeState(
        Player.EVENT_PLAYBACK_STATE_CHANGED,
        Player.EVENT_PLAY_WHEN_READY_CHANGED,
      ) {
        stateUpdateCount++
      }
    val initialCount = stateUpdateCount

    launch(backgroundScope.coroutineContext) { stateObserver.observe() }
    testScheduler.runCurrent()

    assertThat(stateUpdateCount).isEqualTo(initialCount + 1)
  }

  @Test
  fun observe_registeredPlayerEvent_triggersStateUpdate() = runTest {
    val player = createReadyPlayerWithTwoItems()
    var stateUpdateCount = 0
    val stateObserver =
      player.observeState(
        Player.EVENT_PLAYBACK_STATE_CHANGED,
        Player.EVENT_PLAY_WHEN_READY_CHANGED,
      ) {
        stateUpdateCount++
      }
    launch(backgroundScope.coroutineContext) { stateObserver.observe() }
    testScheduler.runCurrent()
    val initialCount = stateUpdateCount

    player.playWhenReady = false
    shadowOf(Looper.getMainLooper()).idle()
    testScheduler.runCurrent()

    assertThat(stateUpdateCount).isEqualTo(initialCount + 1)
  }

  @Test
  fun observe_unregisteredPlayerEvent_doesNotTriggerStateUpdate() = runTest {
    val player = createReadyPlayerWithTwoItems()
    var stateUpdateCount = 0
    val stateObserver =
      player.observeState(
        Player.EVENT_PLAYBACK_STATE_CHANGED,
        Player.EVENT_PLAY_WHEN_READY_CHANGED,
      ) {
        stateUpdateCount++
      }
    launch(backgroundScope.coroutineContext) { stateObserver.observe() }
    testScheduler.runCurrent()
    val initialCount = stateUpdateCount

    player.shuffleModeEnabled = true
    shadowOf(Looper.getMainLooper()).idle()
    testScheduler.runCurrent()

    assertThat(stateUpdateCount).isEqualTo(initialCount)
  }
}
