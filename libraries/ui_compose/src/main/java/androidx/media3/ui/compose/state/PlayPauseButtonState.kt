/*
 * Copyright 2024 The Android Open Source Project
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

import androidx.annotation.IntDef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util.handlePlayPauseButtonAction
import androidx.media3.common.util.Util.shouldEnablePlayPauseButton
import androidx.media3.common.util.Util.shouldShowPlayButton

/**
 * Remembers the value of [PlayPauseButtonState] created based on the passed [Player] and launch a
 * coroutine to listen to [Player's][Player] changes. If the [Player] instance changes between
 * compositions, produce and remember a new value.
 *
 * @param player The [Player] to observe.
 * @param displayMode The [@DisplayMode][DisplayMode] specifying when to show buffering, replay, or
 *   retry.
 */
@UnstableApi
@Composable
fun rememberPlayPauseButtonState(
  player: Player?,
  displayMode: @DisplayMode Int =
    SHOW_BUFFERING_ALWAYS or SHOW_REPLAY_ON_ENDED or SHOW_RETRY_ON_ERROR,
): PlayPauseButtonState {
  val playPauseButtonState =
    remember(player) { PlayPauseButtonState(player, displayMode) }
      .apply { this.displayMode = displayMode }
  LaunchedEffect(player) { playPauseButtonState.observe() }
  return playPauseButtonState
}

/**
 * State that converts the necessary information from the [Player] to correctly deal with a UI
 * component representing a PlayPause button.
 *
 * @property[isEnabled] true if [player] is not `null`, [Player.COMMAND_PLAY_PAUSE] is available and
 *   we have something in the [Timeline][androidx.media3.common.Timeline] to play. See
 *   [shouldEnablePlayPauseButton] for more details.
 * @property[showPlay] true if [player] is `null` or [shouldShowPlayButton] is true. Note that
 *   [showReplay] and [showRetry] are further specifications of this state, meaning if either is
 *   true, [showPlay] will also be true. Callers may prefer to prioritize showing retry and replay
 *   indicators over a generic play indicator.
 * @property[showReplay] true if the replay indicator should be shown based on the [displayMode].
 * @property[showRetry] true if the retry indicator should be shown based on the [displayMode].
 * @param displayMode The [@DisplayMode][DisplayMode] specifying when to show buffering, replay, or
 *   retry.
 */
@UnstableApi
class PlayPauseButtonState(
  private val player: Player?,
  displayMode: @DisplayMode Int =
    SHOW_BUFFERING_ALWAYS or SHOW_REPLAY_ON_ENDED or SHOW_RETRY_ON_ERROR,
) {

  internal var displayMode: @DisplayMode Int = displayMode
    set(value) {
      if (field != value) {
        field = value
        updateDisplayModeState()
      }
    }

  var isEnabled by mutableStateOf(false)
    private set

  var showPlay by mutableStateOf(true)
    private set

  var showBuffering by mutableStateOf(false)
    private set

  var showReplay by mutableStateOf(false)
    private set

  var showRetry by mutableStateOf(false)
    private set

  private fun updateDisplayModeState() {
    val isPlayerBuffering = player?.playbackState == Player.STATE_BUFFERING
    val hasBufferingIntent =
      ((displayMode and SHOW_BUFFERING_ALWAYS) != 0) ||
        (((displayMode and SHOW_BUFFERING_WHEN_PLAYING) != 0) && !showPlay)
    showBuffering = isPlayerBuffering && hasBufferingIntent
    val isEnded = player?.playbackState == Player.STATE_ENDED
    val hasError = player?.playerError != null
    showReplay = isEnded && ((displayMode and SHOW_REPLAY_ON_ENDED) != 0)
    showRetry = hasError && ((displayMode and SHOW_RETRY_ON_ERROR) != 0)
  }

  private val playerStateObserver: PlayerStateObserver? =
    player?.observeState(
      Player.EVENT_PLAYBACK_STATE_CHANGED,
      Player.EVENT_PLAY_WHEN_READY_CHANGED,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
      Player.EVENT_PLAYER_ERROR,
    ) {
      isEnabled = shouldEnablePlayPauseButton(player)
      showPlay = shouldShowPlayButton(player)
      updateDisplayModeState()
    }

  /**
   * Handles the interaction with the PlayPause button according to the current state of the
   * [Player].
   *
   * The [Player] update that follows can take a form of [Player.play], [Player.pause],
   * [Player.prepare] or [Player.seekToDefaultPosition].
   *
   * It will have no effect if no suitable player method is available to handle the play request.
   *
   * @see [androidx.media3.common.util.Util.handlePlayButtonAction]
   * @see [androidx.media3.common.util.Util.handlePauseButtonAction]
   * @see [androidx.media3.common.util.Util.shouldShowPlayButton]
   * @see [androidx.media3.common.Player.COMMAND_PLAY_PAUSE]
   * @see [androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM]
   */
  fun onClick() {
    handlePlayPauseButtonAction(player)
  }

  /**
   * Subscribes to updates from [Player.Events] and listens to
   * * [Player.EVENT_PLAYBACK_STATE_CHANGED] and [Player.EVENT_PLAY_WHEN_READY_CHANGED] in order to
   *   determine whether a play or a pause button should be presented on a UI element for playback
   *   control.
   * * [Player.EVENT_AVAILABLE_COMMANDS_CHANGED] in order to determine whether the button should be
   *   enabled, i.e. respond to user input.
   */
  suspend fun observe() {
    playerStateObserver?.observe()
  }
}

@UnstableApi
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.TYPE, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
@IntDef(
  flag = true,
  value =
    [SHOW_BUFFERING_WHEN_PLAYING, SHOW_BUFFERING_ALWAYS, SHOW_REPLAY_ON_ENDED, SHOW_RETRY_ON_ERROR],
)
annotation class DisplayMode

/**
 * The buffering view is shown when the player is in the buffering state and shouldShowPlayButton is
 * false.
 */
@UnstableApi const val SHOW_BUFFERING_WHEN_PLAYING = 1 shl 0
/** The buffering view is always shown when the player is in the buffering state. */
@UnstableApi const val SHOW_BUFFERING_ALWAYS = 1 shl 1
/** The replay icon is shown when the player is in the ended state. */
@UnstableApi const val SHOW_REPLAY_ON_ENDED = 1 shl 2
/** The retry icon is shown when the player has encountered an error. */
@UnstableApi const val SHOW_RETRY_ON_ERROR = 1 shl 3
