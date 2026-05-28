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

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.media3.common.PlaybackException
import androidx.media3.test.utils.FakePlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ErrorStateTest {

  private val testError1 =
    PlaybackException(
      "Test Network Error 1",
      null,
      PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    )
  private val testError2 =
    PlaybackException(
      "Test Network Error 2",
      /* cause= */ null,
      PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
    )
  private lateinit var playerWithError1: FakePlayer
  private lateinit var playerWithError2: FakePlayer
  private lateinit var playerWithoutError: FakePlayer

  @Before
  fun setUp() {
    playerWithError1 = FakePlayer().apply { setPlayerError(testError1) }
    playerWithError2 = FakePlayer().apply { setPlayerError(testError2) }
    playerWithoutError = FakePlayer().apply { setPlayerError(null) }
  }

  @Test
  fun errorState_observesPlayerError() = runComposeUiTest {
    val player = FakePlayer()
    lateinit var state: ErrorState

    setContent { state = rememberErrorState(player) }

    assertThat(state.error).isNull()

    player.setPlayerError(testError1)
    waitForIdle()

    assertThat(state.error).isEqualTo(testError1)

    player.setPlayerError(null)
    waitForIdle()

    assertThat(state.error).isNull()
  }

  @Test
  fun errorState_playerSwap_observesNewPlayerError() = runComposeUiTest {
    lateinit var state: ErrorState
    lateinit var playerState: MutableState<FakePlayer>

    setContent {
      playerState = remember { mutableStateOf(playerWithoutError) }
      state = rememberErrorState(playerState.value)
    }

    assertThat(state.error).isNull()

    // Swap to a player with an error.
    playerState.value = playerWithError1
    waitForIdle()

    assertThat(state.error).isEqualTo(testError1)

    // Swap to a different player, also with an error.
    playerState.value = playerWithError2
    waitForIdle()

    assertThat(state.error).isEqualTo(testError2)

    // Swap back to a player without an error.
    playerState.value = playerWithoutError
    waitForIdle()

    assertThat(state.error).isNull()
  }
}
