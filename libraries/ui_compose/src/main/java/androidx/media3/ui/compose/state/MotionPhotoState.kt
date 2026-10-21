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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.listenTo

/**
 * Creates and remembers a [MotionPhotoState] for managing Motion Photo playback and visual state.
 *
 * Note: This state holder configures [player] with a single-item playlist containing only
 * [mediaItem] (via [Player.setMediaItem]) and prepares playback. Callers should not modify the
 * [player]'s playlist while it is bound to this state. Changing [mediaItem] creates and remembers a
 * new [MotionPhotoState], resetting [MotionPhotoState.showPhoto] to [initialShowPhoto] and
 * [MotionPhotoState.config] to [initialConfig].
 *
 * This state holder also owns [Player.repeatMode] while bound: it is set to
 * [Player.REPEAT_MODE_ONE] or [Player.REPEAT_MODE_OFF] according to [MotionPhotoConfig.isLooping]
 * and is not restored when [player] is detached.
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
 * @param initialConfig The initial [MotionPhotoConfig] governing motion playback behavior,
 *   subsequently changed via [MotionPhotoState.config].
 * @return A remembered [MotionPhotoState] instance bound to the lifecycle of this composition.
 */
@RestrictTo(LIBRARY_GROUP)
@Composable
fun rememberMotionPhotoState(
  player: Player?,
  mediaItem: MediaItem,
  initialShowPhoto: Boolean = true,
  initialConfig: MotionPhotoConfig = MotionPhotoConfig.DEFAULT,
): MotionPhotoState {
  val state = remember(mediaItem) { MotionPhotoState(mediaItem, initialShowPhoto, initialConfig) }
  state.player = player
  LaunchedEffect(state, player) {
    state.bindAndInitialize()
    state.observe()
  }
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
 * This state holder also owns [Player.repeatMode] while bound: it is set to
 * [Player.REPEAT_MODE_ONE] or [Player.REPEAT_MODE_OFF] according to [MotionPhotoConfig.isLooping]
 * and is not restored when the [Player] is detached.
 *
 * @property mediaItem The [MediaItem] representing the motion photo, guaranteed to have a non-null
 *   [MediaItem.LocalConfiguration.uri].
 * @param initialShowPhoto Whether the still photo is initially shown instead of playing motion.
 * @param initialConfig The initial [MotionPhotoConfig] governing motion playback behavior.
 */
@Stable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoState
internal constructor(
  val mediaItem: MediaItem,
  initialShowPhoto: Boolean = true,
  initialConfig: MotionPhotoConfig = MotionPhotoConfig.DEFAULT,
) {

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
  @Immutable
  class VisualState internal constructor(val isPhotoVisible: Boolean) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is VisualState) return false
      return isPhotoVisible == other.isPhotoVisible
    }

    override fun hashCode(): Int = isPhotoVisible.hashCode()
  }

  private var _player by mutableStateOf<Player?>(null)

  /** The [Player] driving media playback. */
  internal var player: Player?
    get() = _player
    set(value) {
      if (_player != value) {
        _player = value
        isMotionUnavailable = false
        hasRenderedFirstFrame = false
      }
    }

  private var isMotionUnavailable by mutableStateOf(false)
  private var hasRenderedFirstFrame by mutableStateOf(false)

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
      if (_showPhoto == value && (value || player?.playbackState != Player.STATE_IDLE)) {
        return
      }
      _showPhoto = value
      player?.let { syncPlaybackState(it, allowPrepareOnError = !value) }
    }

  private var _config by mutableStateOf(initialConfig)

  /**
   * The [MotionPhotoConfig] governing motion playback behavior.
   *
   * This value is initialized from `initialConfig` and can be updated by the application to change
   * playback behavior dynamically. Setting this property syncs the attached [Player] accordingly.
   */
  var config: MotionPhotoConfig
    get() = _config
    set(value) {
      if (_config == value) {
        return
      }
      _config = value
      player?.let { currentPlayer ->
        syncRepeatMode(currentPlayer)
        if (currentPlayer.playbackState == Player.STATE_ENDED && !_config.isLooping) {
          showPhoto = true
        }
      }
    }

  /** The current resolved [VisualState] for presenting the Motion Photo. */
  val visualState: VisualState by derivedStateOf {
    VisualState(
      isPhotoVisible = _showPhoto || player == null || isMotionUnavailable || !hasRenderedFirstFrame
    )
  }

  internal fun bindAndInitialize() {
    val currentPlayer = player ?: return
    val shouldReplaceMediaItem =
      currentPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM) &&
        (!currentPlayer.isCommandAvailable(Player.COMMAND_GET_TIMELINE) ||
          currentPlayer.mediaItemCount != 1 ||
          !currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) ||
          currentPlayer.currentMediaItem != mediaItem)
    if (shouldReplaceMediaItem) {
      currentPlayer.setMediaItem(mediaItem)
      hasRenderedFirstFrame = false
    }
    // TODO(b/544627271): Support custom start boundaries and defer rewind until after the photo
    // crossfade-in completes.
    syncPlaybackState(currentPlayer, allowPrepareOnError = shouldReplaceMediaItem)
    updateMotionAvailability(currentPlayer)
  }

  internal suspend fun observe() {
    val currentPlayer = player ?: return
    currentPlayer.listenTo(
      Player.EVENT_TRACKS_CHANGED,
      Player.EVENT_PLAYER_ERROR,
      Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
      Player.EVENT_RENDERED_FIRST_FRAME,
      Player.EVENT_PLAYBACK_STATE_CHANGED,
    ) { events ->
      if (
        events.containsAny(
          Player.EVENT_TRACKS_CHANGED,
          Player.EVENT_PLAYER_ERROR,
          Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
        )
      ) {
        updateMotionAvailability(this)
      }
      if (events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) {
        syncRepeatMode(this)
      }
      if (
        events.contains(Player.EVENT_TRACKS_CHANGED) &&
          (!isCommandAvailable(Player.COMMAND_GET_TRACKS) || currentTracks.isEmpty)
      ) {
        hasRenderedFirstFrame = false
      }
      if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
        hasRenderedFirstFrame = true
      }
      if (
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && playbackState == Player.STATE_ENDED
      ) {
        if (!_config.isLooping) {
          showPhoto = true
        } else if (!_showPhoto) {
          // Repeat mode is unsupported or was not applied by the player, so emulate
          // REPEAT_MODE_ONE manually.
          if (isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
            seekToDefaultPosition()
          } else {
            showPhoto = true
          }
        }
      }
    }
  }

  private fun updateMotionAvailability(currentPlayer: Player) {
    val hasNoVideoTrack =
      currentPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS) &&
        !currentPlayer.currentTracks.isEmpty &&
        !currentPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
    isMotionUnavailable = currentPlayer.playerError != null || hasNoVideoTrack
    if (isMotionUnavailable) {
      hasRenderedFirstFrame = false
    }
  }

  private fun syncRepeatMode(currentPlayer: Player) {
    val repeatMode = if (_config.isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    if (
      currentPlayer.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) &&
        currentPlayer.repeatMode != repeatMode
    ) {
      currentPlayer.repeatMode = repeatMode
    }
  }

  private fun syncPlaybackState(currentPlayer: Player, allowPrepareOnError: Boolean) {
    syncRepeatMode(currentPlayer)
    if (
      currentPlayer.playbackState == Player.STATE_IDLE &&
        (currentPlayer.playerError == null || allowPrepareOnError) &&
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

/**
 * Configuration for Motion Photo playback behavior.
 *
 * @property isLooping Whether the motion video should loop continuously instead of returning to the
 *   still photo when playback reaches the end. If the [Player] supports neither
 *   [Player.COMMAND_SET_REPEAT_MODE] nor [Player.COMMAND_SEEK_TO_DEFAULT_POSITION], playback
 *   returns to the still photo instead of looping.
 */
// TODO(b/544627271): Add playback boundaries (`startBoundary`, `startPositionOffsetMs`,
// `endBoundary`, `endPositionOffsetMs`), custom key photo frame anchor timestamp
// (`customImageAnchorTimestampMs`), resume behavior (`resumeVideoFromPreviousPosition`), and other
// presets in follow-up CLs.
@Immutable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoConfig(val isLooping: Boolean = false) {
  /** Returns a copy of this [MotionPhotoConfig] with the given properties updated. */
  fun copy(isLooping: Boolean = this.isLooping): MotionPhotoConfig =
    MotionPhotoConfig(isLooping = isLooping)

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MotionPhotoConfig) return false
    return isLooping == other.isLooping
  }

  override fun hashCode(): Int = isLooping.hashCode()

  companion object {
    /** Default configuration instance that plays motion once and returns to the still photo. */
    @JvmField val DEFAULT = MotionPhotoConfig()

    /** Configuration preset that loops motion playback continuously. */
    @JvmField val LOOP = MotionPhotoConfig(isLooping = true)
  }
}
