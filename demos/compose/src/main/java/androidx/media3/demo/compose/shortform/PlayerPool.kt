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
package androidx.media3.demo.compose.shortform

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * A [Channel]-based pool of [Player] instances.
 *
 * Why use channels:
 * - Built-in Suspension: A standard Queue doesn't know how to wait. If it is empty, we would either
 *   get null or an exception, plus we would need to poll() at a regular interval. Channel's
 *   `receive()` suspending function solves that.
 * - Native Cancellation Support: When a user is swiping quickly through a VerticalPager, they might
 *   leave a page before the player even finishes loading. Because `Channel.receive()` is a
 *   suspending function, it fully supports Coroutine cancellation.
 *
 * @param poolCapacity The maximum number of players to hold in the pool.
 * @param playerFactory A lambda responsible for instantiating and configuring new players.
 */
@MainThread
@OptIn(UnstableApi::class)
internal class PlayerPool<T : Player>(
  private val poolCapacity: Int,
  private val playerFactory: () -> T,
) {
  private val availablePlayers = Channel<T>(Channel.UNLIMITED)
  private val allPlayers = mutableListOf<T>()
  private var isReleased = false

  /** Suspends until a player is available in the pool. */
  suspend fun acquirePlayer(): T {
    check(!isReleased) { "PlayerPool is already released" }
    // Use an idle player if one is immediately available in the channel
    availablePlayers.tryReceive().getOrNull()?.let {
      return it
    }

    // If all the players are busy, but the pool isn't full, create a new one and return it directly
    if (allPlayers.size < poolCapacity) {
      val player = playerFactory()
      allPlayers.add(player)
      return player
    }
    return availablePlayers.receive()
  }

  /** Calls [Player.play()] for the given player and pauses all other players. */
  fun play(player: T) {
    pauseAllPlayers(player)
    player.play()
  }

  private fun pauseAllPlayers(keepOngoingPlayer: T? = null) {
    allPlayers.forEach { if (it != keepOngoingPlayer) it.pause() }
  }

  fun returnToPool(player: T?) {
    player?.apply {
      stop()
      clearMediaItems()
      val unused = availablePlayers.trySend(this)
    }
  }

  fun destroyPlayers() {
    isReleased = true
    allPlayers.forEach(Player::release)
    allPlayers.clear()
    availablePlayers.cancel()
  }
}

/**
 * Acquires, configures, and manages the lifecycle of a pooled [Player] instance.
 *
 * This composable handles the asynchronous acquisition of a player from the provided [playerPool],
 * suspending if the pool is currently exhausted. Once acquired, it delegates configuration to the
 * [playerSetup] lambda. It guarantees the player is safely paused and returned to the pool when the
 * component leaves the composition or the underlying [mediaItem] changes.
 *
 * @param T The specific [Player] implementation managed by the pool (e.g., `ExoPlayer`).
 * @param mediaItem The [MediaItem] this player will represent. Acts as a key to restart the effect
 *   if the content changes.
 * @param playerPool The [PlayerPool] used to acquire and recycle player instances.
 * @param isActive Indicates whether the player is currently focused on the screen to decide whether
 *   the pooled player will play while other players in the pool are paused.
 * @param playerSetup A lambda invoked once the player is successfully acquired, allowing the caller
 *   to perform implementation-specific configuration (such as setting the media source and
 *   preparing the player).
 * @return The acquired [Player] instance, or `null` if the player is currently suspended waiting
 *   for an available instance from the pool.
 */
@Composable
internal fun <T : Player> rememberPooledPlayer(
  mediaItem: MediaItem,
  playerPool: PlayerPool<T>,
  isActive: Boolean,
  playerSetup: (T) -> Unit,
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
  val currentIsActive by rememberUpdatedState(isActive)
  val currentSetup by rememberUpdatedState(playerSetup)

  DisposableEffect(mediaItem, playerPool) {
    var acquiredPlayer: T? = null
    val job = scope.launch {
      val p = playerPool.acquirePlayer()
      acquiredPlayer = p
      player = p
      currentSetup(p)
      if (currentIsActive) playerPool.play(p)
    }
    onDispose {
      job.cancel()
      playerPool.returnToPool(acquiredPlayer)
      player = null
    }
  }

  // Using isActive instead of currentIsActive to actually restart the effect
  LifecycleStartEffect(isActive, player) {
    if (isActive) {
      player?.let(playerPool::play)
    } else {
      player?.pause()
    }
    onStopOrDispose { player?.pause() }
  }

  return player
}
