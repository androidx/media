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

import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.listenTo
import androidx.media3.common.util.UnstableApi

/**
 * Utility to observe [Player] states by listening to events.
 *
 * @param player The [Player]
 * @param firstEvent The first [Player.Event] to listen to
 * @param otherEvents Additional [Player.Event] types to listen to
 * @param stateUpdater The operation to trigger initially and whenever one of the configured events
 *   happen
 */
@UnstableApi
class PlayerStateObserver(
  private val player: Player,
  private val firstEvent: @Player.Event Int,
  vararg otherEvents: @Player.Event Int,
  private val stateUpdater: (Player) -> Unit,
) {

  private val otherEventsArray = otherEvents

  init {
    stateUpdater.invoke(player)
  }

  /** Observes updates from the configured [Player.Events]. */
  suspend fun observe(): Nothing {
    stateUpdater.invoke(player)
    player.listenTo(firstEvent, *otherEventsArray) { stateUpdater.invoke(player) }
  }

  companion object {
    init {
      MediaLibraryInfo.registerModule("media3.ui.compose")
    }
  }
}

/**
 * Utility to observe [Player] states by listening to events.
 *
 * @param firstEvent The first [Player.Event] to listen to
 * @param otherEvents Additional [Player.Event] types to listen to
 * @param stateUpdater The operation to trigger initially and whenever one of the configured events
 *   happen
 */
@UnstableApi
fun Player.observeState(
  firstEvent: @Player.Event Int,
  vararg otherEvents: @Player.Event Int,
  stateUpdater: (Player) -> Unit,
) = PlayerStateObserver(player = this, firstEvent, *otherEvents, stateUpdater = stateUpdater)
