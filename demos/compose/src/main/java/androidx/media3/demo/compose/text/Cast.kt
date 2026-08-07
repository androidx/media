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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.cast.R as CastR
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

  var deviceName by mutableStateOf(player?.deviceInfo?.routingControllerName ?: "")
    private set

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(Player.EVENT_DEVICE_INFO_CHANGED) {
      isRemotePlayback = player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
      deviceName = player.deviceInfo.routingControllerName ?: ""
    }

  /**
   * Subscribes to updates from [Player.Events] and listens to [Player.EVENT_DEVICE_INFO_CHANGED] in
   * order to determine whether playback is happening on a remote device.
   */
  suspend fun observe() = playerStateObserver?.observe()
}

@OptIn(UnstableApi::class)
@Composable
internal fun CastingOverlay(state: CastState, modifier: Modifier = Modifier) {
  if (state.isRemotePlayback) {
    Box(
      modifier = modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        Icon(
          painter = painterResource(CastR.drawable.media_route_button_connected),
          contentDescription = null,
          modifier = Modifier.size(64.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
          text =
            if (state.deviceName.isNotEmpty()) "Casting to ${state.deviceName}"
            else "Casting to remote device",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }
}
