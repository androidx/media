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
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager.Builder
import androidx.media3.exoplayer.util.EventLogger
import kotlinx.coroutines.channels.Channel

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
 */
@MainThread
@OptIn(UnstableApi::class)
internal class PlayerPool(
  private val poolCapacity: Int,
  private val preloadManagerBuilder: Builder,
) {
  private val availablePlayers = Channel<ExoPlayer>(Channel.UNLIMITED)
  private val allPlayers = mutableListOf<ExoPlayer>()
  private var isReleased = false

  /** Suspends until a player is available in the pool. */
  suspend fun acquirePlayer(): ExoPlayer {
    check(!isReleased) { "PlayerPool is already released" }
    // Use an idle player if one is immediately available in the channel
    availablePlayers.tryReceive().getOrNull()?.let {
      return it
    }

    // If all the players are busy, but the pool isn't full, create a new one and return it directly
    if (allPlayers.size < poolCapacity) {
      val player = preloadManagerBuilder.buildExoPlayer()
      player.addAnalyticsListener(EventLogger("player-${allPlayers.size + 1}-of-$poolCapacity"))
      player.repeatMode = ExoPlayer.REPEAT_MODE_ONE
      allPlayers.add(player)
      return player
    }
    return availablePlayers.receive()
  }

  /** Calls [Player.play()] for the given player and pauses all other players. */
  fun play(player: Player) {
    pauseAllPlayers(player)
    player.play()
  }

  private fun pauseAllPlayers(keepOngoingPlayer: Player? = null) {
    allPlayers.filter { it != keepOngoingPlayer }.forEach(Player::pause)
  }

  fun returnToPool(player: ExoPlayer?) {
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
