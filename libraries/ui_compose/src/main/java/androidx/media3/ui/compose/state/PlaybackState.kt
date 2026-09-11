/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.ui.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Creates and remembers a [PlaybackState] for the given [Player]. */
@UnstableApi
@Composable
fun rememberPlaybackState(player: Player?): PlaybackState {
  val playbackState = remember(player) { PlaybackState(player) }
  LaunchedEffect(player) { playbackState.observe() }
  return playbackState
}

/**
 * A general-purpose, read-only state holder that observes and exposes the core playback states of a
 * [Player] reactively for Compose UIs.
 *
 * This state is decoupled from any specific UI component and is ideal for driving overlays,
 * shimmers, or custom control layouts.
 *
 * @property[playbackState] The raw [@Player.State] of the player.
 * @property[playWhenReady] Whether the player should play when ready.
 * @property[playbackSuppressionReason] The reason why playback is suppressed even if
 *   [playWhenReady] is true, or [Player.PLAYBACK_SUPPRESSION_REASON_NONE] if playback is not
 *   suppressed.
 * @property[isPlaying] Whether the player is actively playing.
 * @property[playerError] The last error that caused playback to fail, or null if there is no error.
 */
@UnstableApi
class PlaybackState(private val player: Player?) {
  var playbackState by mutableIntStateOf(player?.playbackState ?: Player.STATE_IDLE)
    private set

  var playWhenReady by mutableStateOf(player?.playWhenReady ?: false)
    private set

  var playbackSuppressionReason by
    mutableIntStateOf(player?.playbackSuppressionReason ?: Player.PLAYBACK_SUPPRESSION_REASON_NONE)
    private set

  var isPlaying by mutableStateOf(player?.isPlaying ?: false)
    private set

  var playerError by mutableStateOf(player?.playerError)
    private set

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(
      Player.EVENT_PLAYBACK_STATE_CHANGED,
      Player.EVENT_PLAY_WHEN_READY_CHANGED,
      Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
      Player.EVENT_IS_PLAYING_CHANGED,
      Player.EVENT_PLAYER_ERROR,
    ) {
      playbackState = player.playbackState
      playWhenReady = player.playWhenReady
      playbackSuppressionReason = player.playbackSuppressionReason
      isPlaying = player.isPlaying
      playerError = player.playerError
    }

  /**
   * Subscribes to updates from [Player.Events] and listens to:
   * * [Player.EVENT_PLAYBACK_STATE_CHANGED], [Player.EVENT_PLAY_WHEN_READY_CHANGED] and
   *   [Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED] in order to determine whether the player
   *   is buffering, has ended, or is playing.
   * * [Player.EVENT_IS_PLAYING_CHANGED] in order to determine whether the player is actively
   *   playing.
   * * [Player.EVENT_PLAYER_ERROR] in order to determine whether a playback error occurred.
   */
  suspend fun observe() = playerStateObserver?.observe()
}
