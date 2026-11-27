/*
 * Copyright 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.ui.compose.lifecycle

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlayerPool
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch

/**
 * Acquires, configures, and manages the lifecycle of a pooled [Player] instance.
 *
 * This composable handles the asynchronous acquisition of a player from the provided [playerPool],
 * suspending if the pool is currently exhausted. Once acquired, it delegates configuration to the
 * [playerSetup] lambda. The player is returned to the pool when the component leaves the
 * composition or the underlying [mediaItem] changes.
 *
 * @param T The specific [Player] implementation managed by the pool (e.g., `ExoPlayer`).
 * @param mediaItem The [MediaItem] this player will represent. Acts as a key to restart the effect
 *   if the content changes.
 * @param playerPool The [PlayerPool] used to acquire and recycle player instances.
 * @param playerSetup A lambda invoked once the player is successfully acquired. This is where
 *   implementation-specific configuration, such as setting the media source and preparing the
 *   player, should be performed.
 * @param playerTeardown An optional lambda invoked before the player is returned to the pool. This
 *   can be used to perform cleanup or unregister listeners.
 * @return The acquired [Player] instance, or `null` if the player is still being acquired or the
 *   pool is exhausted.
 */
@UnstableApi
@Composable
fun <T : Player> rememberPooledPlayer(
  mediaItem: MediaItem,
  playerPool: PlayerPool<T>,
  playerSetup: (T) -> Unit,
  playerTeardown: ((T) -> Unit)? = null,
): T? {
  // Why we need 3 player variables:
  // -- player (The UI State): The Compose MutableState that is required for the UI to
  // recompose when the player is loaded. Kotlin prevents smart casting it to a non-null type, as
  // the compiler cannot guarantee the getter will consistently return a non-null value. We only use
  // it in the Player composable.
  // -- acquiredPlayer (The Lifecycle Net): A mutable var declared **outside** the coroutine. This
  // reference prevents memory leaks during asynchronous cancellation. It guarantees onDispose has a
  // hard reference to the exact player we acquired.
  // -- p (The Local Execution val): This is a short-lived, immutable local variable inside the
  // launch block that can never change or be null. It is safe for p.setMediaSource(...) calls.
  var player: T? by remember { mutableStateOf(null) }
  val scope = rememberCoroutineScope()
  val currentSetup by rememberUpdatedState(playerSetup)
  val currentTeardown by rememberUpdatedState(playerTeardown)

  DisposableEffect(mediaItem, playerPool) {
    var acquiredPlayer: T? = null
    val job = scope.launch {
      val p = playerPool.acquire()
      acquiredPlayer = p
      player = p
      currentSetup(p)
    }
    onDispose {
      job.cancel()
      acquiredPlayer?.let {
        currentTeardown?.invoke(it)
        playerPool.yield(it)
      }
      player = null
    }
  }

  return player
}
