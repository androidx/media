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
package androidx.media3.common

import android.os.Looper
import androidx.annotation.MainThread
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.channels.Channel

/**
 * A pool of [Player] instances.
 *
 * All operations on this class must be called from the main thread.
 *
 * @param poolCapacity The maximum number of players to hold in the pool. Must be positive.
 * @param playerFactory A lambda responsible for instantiating and configuring new players. The
 *   created players must be configured to be accessed from the main thread.
 * @param T The type of the [Player] instances in the pool.
 */
@MainThread
@UnstableApi
class PlayerPool<T : Player>(private val poolCapacity: Int, private val playerFactory: () -> T) {

  init {
    require(poolCapacity > 0) { "poolCapacity must be greater than 0" }
  }

  // Why use channels:
  // - Built-in Suspension: A standard Queue doesn't know how to wait. If it is empty, we would
  // either get null or an exception, plus we would need to poll() at a regular interval. Channel's
  // `receive()` suspending function solves that.
  // - Native Cancellation Support: When a user is swiping quickly through a VerticalPager, they
  // might leave a page before the player even finishes loading. Because `Channel.receive()` is a
  // suspending function, it fully supports Coroutine cancellation.
  private val availablePlayers = Channel<T>(Channel.UNLIMITED)
  private val allPlayers = mutableListOf<T>()
  private val activePlayers = mutableSetOf<T>()
  private var isReleased = false

  private fun verifyMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "PlayerPool methods must be called from the main thread"
    }
  }

  /** Suspends until a player is available in the pool. */
  suspend fun acquire(): T {
    verifyMainThread()
    check(!isReleased) { "PlayerPool is already released" }
    // Use an idle player if one is immediately available in the channel
    availablePlayers.tryReceive().getOrNull()?.let {
      activePlayers.add(it)
      return it
    }

    // If all the players are busy, but the pool isn't full, create a new one and return it directly
    if (allPlayers.size < poolCapacity) {
      val player = playerFactory()
      allPlayers.add(player)
      activePlayers.add(player)
      return player
    }
    val player = availablePlayers.receive()
    activePlayers.add(player)
    return player
  }

  /**
   * Executes the given [action] on all players managed by the pool, including both acquired players
   * and idle players waiting in the pool.
   *
   * @param action The action to perform on each player.
   */
  @MainThread
  fun executeForAll(action: T.() -> Unit) {
    verifyMainThread()
    allPlayers.forEach { it.action() }
  }

  /**
   * Executes the given [action] only on players that are currently acquired from the pool.
   *
   * This is useful for broadcasting state changes (like muting or updating playback speed) to
   * players that are currently active, without affecting idle players in the pool.
   *
   * @param action The action to perform on each acquired player.
   */
  @MainThread
  fun executeForAcquired(action: T.() -> Unit) {
    verifyMainThread()
    activePlayers.forEach { it.action() }
  }

  /**
   * Returns a player to the pool.
   *
   * @param player The [Player] instance to return to the pool.
   */
  fun yield(player: T) {
    verifyMainThread()
    if (activePlayers.remove(player)) {
      player.playWhenReady = false
      player.stop()
      player.clearMediaItems()
      val unused = availablePlayers.trySend(player)
    }
  }

  /** Releases all players in the pool and prevents further acquisition. */
  fun release() {
    verifyMainThread()
    isReleased = true
    allPlayers.forEach(Player::release)
    allPlayers.clear()
    activePlayers.clear()
    availablePlayers.cancel()
  }
}
