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

package androidx.media3.demo.compose.text

import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.DeviceInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.PlayerStateObserver
import androidx.media3.ui.compose.state.observeState

/**
 * Remembers the value of [CastState] created based on the passed [Player] and launches a coroutine
 * to listen to [Player's][Player] changes. If the [Player] instance changes between compositions,
 * produce and remember a new value.
 */
@OptIn(UnstableApi::class)
@Composable
internal fun rememberCastState(player: Player?): CastState {
  val castState = remember(player) { CastState(player) }
  LaunchedEffect(player) { castState.observe() }
  return castState
}

/**
 * State that converts the necessary information from the [Player] to correctly deal with a UI
 * component representing casting state.
 */
@OptIn(UnstableApi::class)
internal class CastState(private val player: Player?) {
  var isRemotePlayback by
    mutableStateOf(player?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE)
    private set

  // TODO(b/539586465): Add a second field called "deviceName"

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_DEVICE_INFO_CHANGED) {
      isRemotePlayback = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
    }

  /**
   * Subscribes to updates from [Player.Events] and listens to [Player.EVENT_DEVICE_INFO_CHANGED] in
   * order to determine whether playback is happening on a remote device.
   */
  suspend fun observe() = playerStateObserver?.observe()
}
