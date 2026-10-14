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

import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player

/**
 * Creates and remembers a [MotionPhotoState] for managing Motion Photo playback and visual state.
 *
 * Note: This state holder configures [player] with a single-item playlist containing only
 * [mediaItem] (via [Player.setMediaItem]) and prepares playback. Callers should not modify the
 * [player]'s playlist while it is bound to this state. Changing [mediaItem] creates and remembers a
 * new [MotionPhotoState], resetting [MotionPhotoState.showPhoto] to [initialShowPhoto].
 *
 * This state holder does not pause, stop, or release [player] when [player] is replaced with a
 * different instance or `null`, or when `rememberMotionPhotoState` leaves the composition. Callers
 * own the [Player] lifecycle and are responsible for pausing, stopping, or releasing [player] when
 * detaching it or leaving the composition.
 *
 * @param player The [Player] to coordinate with, or `null` to just show the still photo.
 * @param mediaItem The [MediaItem] representing the Motion Photo. Must have a non-null
 *   [MediaItem.LocalConfiguration.uri].
 * @param initialShowPhoto Whether the still photo is initially shown instead of playing motion.
 * @return A remembered [MotionPhotoState] instance bound to the lifecycle of this composition.
 */
// TODO(b/544627271): Add `config: MotionPhotoConfig = MotionPhotoConfig.DEFAULT` parameter once
// `MotionPhotoConfig` is introduced in `androidx.media3.ui.compose.state`.
@RestrictTo(LIBRARY_GROUP)
@Composable
fun rememberMotionPhotoState(
  player: Player?,
  mediaItem: MediaItem,
  initialShowPhoto: Boolean = true,
): MotionPhotoState {
  val state = remember(mediaItem) { MotionPhotoState(mediaItem, initialShowPhoto) }
  state.player = player
  LaunchedEffect(state, player) { state.bindAndInitialize() }
  // TODO(b/544627271): Add `LaunchedEffect(state, player) { state.observe() }` using
  // `Player.listen` to observe EVENT_RENDERED_FIRST_FRAME, STATE_ENDED (which automatically flips
  // `showPhoto` back to `true`), and EVENT_PLAYER_ERROR.
  return state
}

/**
 * State holder for coordinating Motion Photo presentation between video and photo.
 *
 * If the supplied [Player]'s playlist does not already consist solely of [mediaItem], this state
 * holder replaces the playlist via [Player.setMediaItem] so that [mediaItem] is the only item in
 * the playlist, and prepares playback. Callers should not modify the [Player]'s playlist while it
 * is bound to this state.
 *
 * @property mediaItem The [MediaItem] representing the motion photo, guaranteed to have a non-null
 *   [MediaItem.LocalConfiguration.uri].
 * @param initialShowPhoto Whether the still photo is initially shown instead of playing motion.
 */
// TODO(b/544627271): Introduce `MotionPhotoConfig` in `androidx.media3.ui.compose.state` (exposed
// on `MotionPhotoState` via `var config: MotionPhotoConfig`) in follow-up CLs to configure playback
// boundaries, looping, custom key photo frame anchor timestamp, resume behavior, crossfade
// duration, presets...
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoState
internal constructor(val mediaItem: MediaItem, initialShowPhoto: Boolean = true) {

  init {
    requireNotNull(mediaItem.localConfiguration?.uri) {
      "MotionPhotoState requires a MediaItem with a non-null localConfiguration.uri."
    }
  }

  /**
   * Represents the resolved visual output state of a Motion Photo.
   *
   * @property isPhotoVisible Whether the still photo overlay should currently cover the video
   *   surface.
   */
  class VisualState internal constructor(val isPhotoVisible: Boolean) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is VisualState) return false
      return isPhotoVisible == other.isPhotoVisible
    }

    override fun hashCode(): Int = isPhotoVisible.hashCode()
  }

  /** The [Player] driving media playback. */
  internal var player: Player? by mutableStateOf(null)

  private var _showPhoto by mutableStateOf(initialShowPhoto)

  /**
   * Whether the still photo is the target state to be shown instead of playing motion.
   *
   * This value is initialized from `initialShowPhoto` and is subsequently flipped either by the
   * application (for example, in response to a user trigger) or automatically back to `true` when
   * non-looping video playback finishes. Setting this property commands the attached [Player]
   * accordingly.
   */
  var showPhoto: Boolean
    get() = _showPhoto
    set(value) {
      if (_showPhoto == value) {
        return
      }
      _showPhoto = value
      player?.let { syncPlaybackState(it) }
    }

  /** The current resolved [VisualState] for presenting the Motion Photo. */
  // TODO(b/544627271): Also keep the photo visible when motion is unavailable (derived from whether
  // `player.currentTracks` contains a selected `C.TRACK_TYPE_VIDEO` track and no player error once
  // player event observation is added).
  val visualState: VisualState by derivedStateOf {
    VisualState(isPhotoVisible = _showPhoto || player == null)
  }

  internal fun bindAndInitialize() {
    val currentPlayer = player ?: return
    if (
      currentPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) &&
        (!currentPlayer.isCommandAvailable(Player.COMMAND_GET_TIMELINE) ||
          currentPlayer.mediaItemCount != 1 ||
          !currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) ||
          currentPlayer.currentMediaItem != mediaItem)
    ) {
      currentPlayer.setMediaItem(mediaItem)
    }
    // TODO(b/544627271): Support custom start boundaries and defer rewind until after the photo
    // crossfade-in completes.
    syncPlaybackState(currentPlayer)
  }

  private fun syncPlaybackState(currentPlayer: Player) {
    if (
      currentPlayer.playbackState == Player.STATE_IDLE &&
        currentPlayer.isCommandAvailable(Player.COMMAND_PREPARE)
    ) {
      currentPlayer.prepare()
    }

    if (
      currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
        currentPlayer.currentMediaItem != null &&
        currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
    ) {
      currentPlayer.seekToDefaultPosition()
    }
    if (currentPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
      currentPlayer.playWhenReady = !_showPhoto
    }
  }

  companion object {
    init {
      MediaLibraryInfo.registerModule("media3.ui.compose")
    }
  }
}
