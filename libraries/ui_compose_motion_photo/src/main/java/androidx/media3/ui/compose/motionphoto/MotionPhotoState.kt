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

package androidx.media3.ui.compose.motionphoto

import androidx.annotation.IntDef
import androidx.annotation.IntRange
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.listenTo
import androidx.media3.common.util.Log
import androidx.media3.common.util.Util
import androidx.media3.extractor.metadata.MotionPhotoMetadata
import com.google.errorprone.annotations.CanIgnoreReturnValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Creates and remembers a [MotionPhotoState] for managing Motion Photo playback and visual state.
 *
 * Note: This state holder configures [player] with a single-item playlist containing only
 * [mediaItem] (via [Player.setMediaItem], updating its [MediaItem.ClippingConfiguration] in place
 * via [Player.replaceMediaItem] as playback boundaries change) and prepares playback. Callers
 * should not modify the [player]'s playlist while it is bound to this state. Changing [mediaItem]
 * creates and remembers a new [MotionPhotoState], resetting motion playback to [initialShowMotion]
 * and [MotionPhotoState.config] to [initialConfig].
 *
 * The provided [player] must support updating an item's [MediaItem.ClippingConfiguration] in-place
 * using [Player.replaceMediaItem]. For `ExoPlayer`, this means a `ClippingMediaSource` (with
 * `ClippingMediaSource.Builder.setEnableClippingInMediaPeriod` enabled) must always be present in
 * the `MediaSource` chain, even when no clipping is requested. If using
 * `DefaultMediaSourceFactory`, this can be done using
 * `DefaultMediaSourceFactory.setEnableClippingInMediaPeriod` and
 * `DefaultMediaSourceFactory.setAlwaysWrapInClippingMediaSource`. The provided [player] must be a
 * direct in-process instance such as `ExoPlayer`; `MediaController` is not supported.
 *
 * This state holder also owns [Player.playWhenReady] and [Player.repeatMode] while bound:
 * [Player.playWhenReady] is driven by the motion/still-photo state (callers should pause via
 * [MotionPhotoState.showStillPhoto] or detach by passing `player = null` rather than calling
 * [Player.pause] or [Player.stop] directly), and [Player.repeatMode] is set to
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
 * @param initialShowMotion Whether motion playback is initially requested instead of showing the
 *   still photo; subsequently changed via [MotionPhotoState.playMotion],
 *   [MotionPhotoState.showStillPhoto], or [MotionPhotoState.toggle].
 * @param initialConfig The initial [MotionPhotoConfig] governing motion playback behavior,
 *   subsequently changed via [MotionPhotoState.config].
 * @return A remembered [MotionPhotoState] instance bound to the lifecycle of this composition.
 */
@RestrictTo(LIBRARY_GROUP)
@Composable
fun rememberMotionPhotoState(
  player: Player?,
  mediaItem: MediaItem,
  initialShowMotion: Boolean = false,
  initialConfig: MotionPhotoConfig = MotionPhotoConfig.DEFAULT,
): MotionPhotoState {
  val coroutineScope = rememberCoroutineScope()
  val state =
    remember(mediaItem) {
      MotionPhotoState(mediaItem, initialShowMotion, initialConfig, coroutineScope)
    }
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
 * If the supplied [Player]'s playlist does not already consist solely of [mediaItem] (ignoring its
 * [MediaItem.ClippingConfiguration]), this state holder replaces the playlist via
 * [Player.setMediaItem] so that [mediaItem] is the only item in the playlist, and prepares
 * playback. Playback boundary changes update [mediaItem]'s [MediaItem.ClippingConfiguration] in
 * place via [Player.replaceMediaItem], keeping the current playback position when only the end
 * boundary changes (unless the current position is at or beyond the new end) and seeking to the
 * start position when the start boundary changes. The start and end positions of [mediaItem]'s
 * [MediaItem.ClippingConfiguration] are overridden by the resolved boundaries. Callers should not
 * modify the [Player]'s playlist while it is bound to this state.
 *
 * The supplied [Player] must support updating an item's [MediaItem.ClippingConfiguration] in-place
 * using [Player.replaceMediaItem]; see [rememberMotionPhotoState] for how to configure `ExoPlayer`.
 *
 * This state holder also owns [Player.playWhenReady] and [Player.repeatMode] while bound:
 * [Player.playWhenReady] is driven by the motion/still-photo state (callers should pause via
 * [showStillPhoto] or detach by passing `player = null` rather than calling [Player.pause] or
 * [Player.stop] directly), and [Player.repeatMode] is set to [Player.REPEAT_MODE_ONE] or
 * [Player.REPEAT_MODE_OFF] according to [MotionPhotoConfig.isLooping] and is not restored when the
 * [Player] is detached.
 *
 * @property mediaItem The [MediaItem] representing the motion photo, guaranteed to have a non-null
 *   [MediaItem.LocalConfiguration.uri].
 * @param initialShowMotion Whether motion playback is initially requested instead of showing the
 *   still photo; see [playMotion], [showStillPhoto], and [toggle].
 * @param initialConfig The initial [MotionPhotoConfig] governing motion playback behavior.
 * @param coroutineScope Scope used to launch deferred playback and rewind coroutines during photo
 *   transitions.
 */
@Stable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoState
internal constructor(
  val mediaItem: MediaItem,
  initialShowMotion: Boolean = false,
  initialConfig: MotionPhotoConfig = MotionPhotoConfig.DEFAULT,
  private val coroutineScope: CoroutineScope,
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
   * @property transitionDurationMs The duration of the alpha crossfade transition in milliseconds.
   */
  @Immutable
  class VisualState
  internal constructor(
    val isPhotoVisible: Boolean,
    @IntRange(from = 0) val transitionDurationMs: Long,
  ) {
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is VisualState) return false
      return isPhotoVisible == other.isPhotoVisible &&
        transitionDurationMs == other.transitionDurationMs
    }

    override fun hashCode(): Int {
      var result = isPhotoVisible.hashCode()
      result = 31 * result + transitionDurationMs.hashCode()
      return result
    }
  }

  private var _player by mutableStateOf<Player?>(null)
  private var deferredRewindJob: Job? = null
  private var deferredPlayJob: Job? = null
  private var isPlayPendingFirstFrame = false

  /** The [Player] driving media playback. */
  internal var player: Player?
    get() = _player
    set(value) {
      if (_player != value) {
        cancelDeferredRewind()
        cancelDeferredPlay()
        _player = value
        isMotionUnavailable = false
        isPlaybackRangeEmpty = false
        hasRenderedFirstFrame = false
        videoDurationUs = C.TIME_UNSET
        photoPresentationTimestampUs = C.TIME_UNSET
        hasKnownTracks = false
        hasWarnedAboutPhotoFrameFallback = false
      }
    }

  private var isMotionUnavailable by mutableStateOf(false)
  private var isPlaybackRangeEmpty by mutableStateOf(false)
  private var hasRenderedFirstFrame by mutableStateOf(false)
  private var isPhotoForced by mutableStateOf(!initialShowMotion)
  private var hasMotionEnded by mutableStateOf(false)
  private var videoDurationUs: Long = C.TIME_UNSET
  private var photoPresentationTimestampUs: Long = C.TIME_UNSET
  private var hasKnownTracks = false
  private var hasWarnedAboutPhotoFrameFallback = false

  private val isPhotoTarget: Boolean
    get() = isPhotoForced || hasMotionEnded

  private val showMotion: Boolean
    get() = !isPhotoTarget && player?.playbackState != Player.STATE_IDLE

  /**
   * Requests playing motion according to [config].
   *
   * If motion playback had previously finished, or if the attached [Player] is in
   * [Player.STATE_IDLE] after a playback error, this restarts or re-prepares playback. Calling this
   * while motion playback is already active is a no-op.
   */
  fun playMotion() {
    if (showMotion) {
      return
    }
    isPhotoForced = false
    hasMotionEnded = false
    player?.let { transitionToMotion(it, allowPrepareOnError = true) }
  }

  /**
   * Requests showing the still photo instead of playing motion.
   *
   * Pauses motion while keeping [config] in place. Calling this while the still photo is already
   * requested is a no-op.
   */
  fun showStillPhoto() {
    val wasPhotoTarget = isPhotoTarget
    isPhotoForced = true
    hasMotionEnded = false
    if (!wasPhotoTarget) {
      player?.let { transitionToStillPhoto(it) }
    }
  }

  /**
   * Toggles between playing motion and showing the still photo.
   *
   * Calls [showStillPhoto] when motion playback is active as the target state, and [playMotion]
   * otherwise (including to replay after non-looping motion finishes or to retry after an error).
   */
  fun toggle() {
    if (showMotion) {
      showStillPhoto()
    } else {
      playMotion()
    }
  }

  private fun markMotionEnded() {
    if (isPhotoTarget) {
      return
    }
    hasMotionEnded = true
    player?.let { transitionToStillPhoto(it) }
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
      val previousConfig = _config
      _config = value
      if (_config.resumeVideoFromPreviousPosition) {
        cancelDeferredRewind()
      }
      // Play-once finishes on the still photo, so changing non-looping options (e.g., boundaries
      // or transition duration) should not auto-play motion until playMotion() or looping is set.
      val restartedEndedMotion = !previousConfig.isLooping && _config.isLooping && hasMotionEnded
      val currentPlayer = player
      if (currentPlayer == null) {
        if (restartedEndedMotion) {
          hasMotionEnded = false
        }
        return
      }
      if (
        previousConfig.startBoundary != _config.startBoundary ||
          previousConfig.startBoundaryOffsetMs != _config.startBoundaryOffsetMs ||
          previousConfig.endBoundary != _config.endBoundary ||
          previousConfig.endBoundaryOffsetMs != _config.endBoundaryOffsetMs
      ) {
        syncMediaItem(currentPlayer)
      }
      if (restartedEndedMotion) {
        hasMotionEnded = false
        transitionToMotion(currentPlayer, allowPrepareOnError = false)
      } else if (
        currentPlayer.playbackState == Player.STATE_ENDED && !_config.isLooping && !isPhotoTarget
      ) {
        markMotionEnded()
      } else {
        if (
          isPhotoTarget &&
            previousConfig.resumeVideoFromPreviousPosition &&
            !_config.resumeVideoFromPreviousPosition
        ) {
          seekToStartPosition(currentPlayer)
        }
        syncPlaybackState(currentPlayer)
      }
    }

  /** The current resolved [VisualState] for presenting the Motion Photo. */
  val visualState: VisualState by derivedStateOf {
    VisualState(
      isPhotoVisible =
        isPhotoTarget ||
          player == null ||
          isMotionUnavailable ||
          isPlaybackRangeEmpty ||
          !hasRenderedFirstFrame,
      transitionDurationMs = _config.transitionDurationMs,
    )
  }

  internal fun bindAndInitialize() {
    val currentPlayer = player ?: return
    updateMotionPhotoMetadata(currentPlayer)
    updateVideoDuration(currentPlayer)
    val didUpdateMediaItem = syncMediaItem(currentPlayer)
    updateMotionAvailability(currentPlayer)
    // If no new item or clipping update was applied, an existing error is not stale.
    if (isPhotoTarget) {
      transitionToStillPhoto(currentPlayer, allowPrepareOnError = didUpdateMediaItem)
    } else {
      transitionToMotion(
        currentPlayer,
        allowPrepareOnError = didUpdateMediaItem,
        isInitialBind = true,
      )
    }
    // Re-check in case setMediaItem or prepare synchronously updated state, tracks, or timeline
    // before observe() registered its listener.
    val didMetadataChange = updateMotionPhotoMetadata(currentPlayer)
    val didDurationChange = updateVideoDuration(currentPlayer)
    if (didMetadataChange || didDurationChange) {
      syncMediaItem(currentPlayer)
    }
    if (updateMotionAvailability(currentPlayer)) {
      syncPlayWhenReady(currentPlayer)
    }
  }

  internal suspend fun observe() {
    val currentPlayer = player ?: return
    try {
      observePlayerEvents(currentPlayer)
    } finally {
      // The effect running observe() shares this state's lifetime. A rewind that is still pending
      // when it ends must not reach a player that a newer state may have re-bound in the meantime.
      cancelDeferredRewind()
      cancelDeferredPlay()
    }
  }

  private suspend fun observePlayerEvents(currentPlayer: Player) {
    currentPlayer.listenTo(
      Player.EVENT_TRACKS_CHANGED,
      Player.EVENT_TIMELINE_CHANGED,
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
        ) && updateMotionAvailability(this)
      ) {
        // Motion that becomes unavailable has no frame to fade in, and motion that becomes
        // available again starts underneath the still photo once its first frame has rendered.
        syncPlayWhenReady(this)
      }
      val didMetadataChange =
        events.containsAny(
          Player.EVENT_TRACKS_CHANGED,
          Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
          Player.EVENT_PLAYBACK_STATE_CHANGED,
        ) &&
          updateMotionPhotoMetadata(this, isTracksChanged = Player.EVENT_TRACKS_CHANGED in events)
      val didDurationChange =
        events.containsAny(
          Player.EVENT_TIMELINE_CHANGED,
          Player.EVENT_AVAILABLE_COMMANDS_CHANGED,
        ) && updateVideoDuration(this)
      if (
        didMetadataChange ||
          didDurationChange ||
          events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
      ) {
        syncMediaItem(this)
      }
      if (events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) {
        syncPlaybackState(this)
      }
      if (
        events.contains(Player.EVENT_TRACKS_CHANGED) &&
          (!isCommandAvailable(Player.COMMAND_GET_TRACKS) || currentTracks.isEmpty)
      ) {
        hasRenderedFirstFrame = false
        // The still photo covers the item being prepared again, so motion must wait for the first
        // frame underneath it once more.
        syncPlayWhenReady(this)
      }
      if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME)) {
        hasRenderedFirstFrame = true
        if (isPlayPendingFirstFrame) {
          syncPlayWhenReady(this)
        }
      }
      if (
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
          playbackState == Player.STATE_READY &&
          isPlayPendingFirstFrame
      ) {
        // Ready without having rendered a frame, so the frame only comes once the player plays.
        syncPlayWhenReady(this)
      }
      if (
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && playbackState == Player.STATE_IDLE
      ) {
        hasRenderedFirstFrame = false
        // A stopped player resets to its unrendered initial state, so motion waits for a new
        // first frame on subsequent preparation.
        syncPlayWhenReady(this)
      }
      if (
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && playbackState == Player.STATE_ENDED
      ) {
        if (!_config.isLooping) {
          markMotionEnded()
        } else if (!isPhotoTarget) {
          // Repeat mode is unsupported or was not applied by the player, so emulate
          // REPEAT_MODE_ONE manually.
          if (isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
            seekToDefaultPosition()
          } else {
            markMotionEnded()
          }
        }
      }
    }
  }

  @CanIgnoreReturnValue
  private fun updateMotionAvailability(currentPlayer: Player): Boolean {
    val hasNoVideoTrack =
      currentPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS) &&
        !currentPlayer.currentTracks.isEmpty &&
        !currentPlayer.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
    val wasMotionUnavailable = isMotionUnavailable
    isMotionUnavailable = currentPlayer.playerError != null || hasNoVideoTrack
    if (isMotionUnavailable) {
      hasRenderedFirstFrame = false
    }
    return isMotionUnavailable != wasMotionUnavailable
  }

  @CanIgnoreReturnValue
  private fun updateMotionPhotoMetadata(
    currentPlayer: Player,
    isTracksChanged: Boolean = false,
  ): Boolean {
    val previousPhotoPresentationTimestampUs = photoPresentationTimestampUs
    val hadKnownTracks = hasKnownTracks
    if (getMatchingMediaItemFromPlayer(currentPlayer) == null) {
      photoPresentationTimestampUs = C.TIME_UNSET
      hasKnownTracks = false
      return photoPresentationTimestampUs != previousPhotoPresentationTimestampUs ||
        hasKnownTracks != hadKnownTracks
    }
    val canGetTracks = currentPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS)
    val hasTracks = canGetTracks && !currentPlayer.currentTracks.isEmpty
    if ((currentPlayer.playbackState == Player.STATE_IDLE || isTracksChanged) && !hasTracks) {
      hasKnownTracks = false
    }
    // A player that is ready or ended without reporting tracks is also treated as having known
    // tracks, so a pending BOUNDARY_PHOTO_FRAME falls back rather than staying unresolved.
    hasKnownTracks =
      hasKnownTracks ||
        currentPlayer.playbackState == Player.STATE_READY ||
        currentPlayer.playbackState == Player.STATE_ENDED ||
        hasTracks
    photoPresentationTimestampUs =
      if (canGetTracks) findPhotoPresentationTimestampUs(currentPlayer) else C.TIME_UNSET
    return photoPresentationTimestampUs != previousPhotoPresentationTimestampUs ||
      hasKnownTracks != hadKnownTracks
  }

  private fun findPhotoPresentationTimestampUs(currentPlayer: Player): Long {
    for (group in currentPlayer.currentTracks.groups) {
      for (i in 0 until group.length) {
        val metadata = group.getTrackFormat(i).metadata ?: continue
        for (j in 0 until metadata.length()) {
          val entry = metadata.get(j)
          if (entry is MotionPhotoMetadata && entry.photoPresentationTimestampUs >= 0L) {
            return entry.photoPresentationTimestampUs
          }
        }
      }
    }
    return C.TIME_UNSET
  }

  /**
   * Synchronizes idempotent playback flags (`repeatMode` and `playWhenReady`) to [currentPlayer]
   * without triggering one-shot transition side effects such as preparing or seeking.
   */
  private fun syncPlaybackState(currentPlayer: Player) {
    val repeatMode = if (_config.isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    if (
      currentPlayer.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) &&
        currentPlayer.repeatMode != repeatMode
    ) {
      currentPlayer.repeatMode = repeatMode
    }
    syncPlayWhenReady(currentPlayer)
  }

  @CanIgnoreReturnValue
  private fun prepareIfNeeded(currentPlayer: Player, allowPrepareOnError: Boolean): Boolean {
    val shouldPrepare =
      currentPlayer.playbackState == Player.STATE_IDLE &&
        (currentPlayer.playerError == null || allowPrepareOnError) &&
        currentPlayer.isCommandAvailable(Player.COMMAND_PREPARE)
    if (shouldPrepare) {
      currentPlayer.prepare()
    }
    return shouldPrepare
  }

  private fun transitionToStillPhoto(currentPlayer: Player, allowPrepareOnError: Boolean = false) {
    prepareIfNeeded(currentPlayer, allowPrepareOnError)
    cancelDeferredRewind()
    if (!_config.resumeVideoFromPreviousPosition) {
      if (shouldDeferRewind()) {
        deferredRewindJob = launchAfterTransition { seekToStartPosition(currentPlayer) }
      } else {
        seekToStartPosition(currentPlayer)
      }
    }
    syncPlaybackState(currentPlayer)
  }

  private fun transitionToMotion(
    currentPlayer: Player,
    allowPrepareOnError: Boolean,
    isInitialBind: Boolean = false,
  ) {
    val didPrepare = prepareIfNeeded(currentPlayer, allowPrepareOnError)
    val hadPendingDeferredRewind = deferredRewindJob?.isActive == true
    cancelDeferredRewind()
    val needsRewind =
      if (_config.resumeVideoFromPreviousPosition) {
        currentPlayer.playbackState == Player.STATE_ENDED
      } else {
        // When resuming motion, the player was already rewound when returning to the photo unless a
        // deferred rewind was still in flight, the player is being initialized or re-prepared from
        // idle, or playback had ended.
        hadPendingDeferredRewind ||
          didPrepare ||
          isInitialBind ||
          currentPlayer.playbackState == Player.STATE_ENDED
      }
    if (needsRewind) {
      seekToStartPosition(currentPlayer)
    }
    if (didPrepare) {
      // Refresh synchronously in case prepareIfNeeded() cleared a previous playerError before
      // observePlayerEvents() processes the error change.
      updateMotionAvailability(currentPlayer)
    }
    syncPlaybackState(currentPlayer)
  }

  /**
   * Syncs [Player.getPlayWhenReady] with the target state.
   *
   * Motion starts underneath the still photo: while the player is still preparing the range,
   * playback is held back until its first frame has rendered (which is what starts the crossfade
   * from the still photo), and in any case until that crossfade has completed, so that the still
   * photo fades out over a stationary frame.
   */
  private fun syncPlayWhenReady(currentPlayer: Player) {
    if (!currentPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
      cancelDeferredPlay()
      return
    }
    if (isPhotoTarget || isPlaybackRangeEmpty) {
      cancelDeferredPlay()
      currentPlayer.playWhenReady = false
      return
    }
    if (isMotionUnavailable) {
      cancelDeferredPlay()
      // There is no frame to fade in, so there is nothing to wait for.
      currentPlayer.playWhenReady = true
      return
    }
    if (!hasRenderedFirstFrame) {
      cancelDeferredPlay()
      if (
        currentPlayer.playbackState == Player.STATE_BUFFERING ||
          currentPlayer.playbackState == Player.STATE_IDLE
      ) {
        // The first frame is rendered as part of preparing the range, regardless of playWhenReady.
        // Resumed from observePlayerEvents once it has rendered, or once the player becomes ready
        // without having rendered it.
        currentPlayer.playWhenReady = false
        isPlayPendingFirstFrame = true
      } else {
        // A player that is ready or ended without this state having observed a rendered frame (for
        // example a player handed over across a configuration change, whose surface may have been
        // replaced while paused) only renders once it plays, so the still photo lifts on the first
        // frame.
        currentPlayer.playWhenReady = true
      }
      return
    }
    isPlayPendingFirstFrame = false
    if (currentPlayer.playWhenReady || deferredPlayJob?.isActive == true) {
      return
    }
    if (_config.transitionDurationMs > 0L) {
      deferredPlayJob = launchAfterTransition {
        if (currentPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
          currentPlayer.playWhenReady = true
        }
      }
    } else {
      cancelDeferredPlay()
      currentPlayer.playWhenReady = true
    }
  }

  private fun cancelDeferredPlay() {
    deferredPlayJob?.cancel()
    deferredPlayJob = null
    isPlayPendingFirstFrame = false
  }

  private fun shouldDeferRewind(): Boolean =
    isPhotoTarget &&
      hasRenderedFirstFrame &&
      !isMotionUnavailable &&
      !isPlaybackRangeEmpty &&
      _config.transitionDurationMs > 0L

  private fun cancelDeferredRewind() {
    deferredRewindJob?.cancel()
    deferredRewindJob = null
  }

  /**
   * Launches [action] to run once the crossfade that starts now has completed.
   *
   * The duration is captured on purpose: the crossfade keeps it even if the config changes while it
   * is running, so [action] must wait for the same amount of time.
   */
  private fun launchAfterTransition(action: () -> Unit): Job {
    val transitionDurationMs = _config.transitionDurationMs
    return coroutineScope.launch {
      delay(transitionDurationMs)
      action()
    }
  }

  private fun seekToStartPosition(currentPlayer: Player) {
    if (
      currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
        currentPlayer.currentMediaItem != null &&
        currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
    ) {
      currentPlayer.seekToDefaultPosition()
    }
  }

  /**
   * Returns [currentPlayer]'s current [MediaItem] if its playlist already consists solely of
   * [mediaItem] (ignoring [MediaItem.clippingConfiguration]), or `null` otherwise.
   *
   * A player handed over across recompositions or configuration changes may already play
   * [mediaItem] with the clipping a previous state applied, so the clipping is ignored in the
   * comparison rather than setting the item from scratch again.
   */
  private fun getMatchingMediaItemFromPlayer(currentPlayer: Player): MediaItem? {
    if (
      !currentPlayer.isCommandAvailable(Player.COMMAND_GET_TIMELINE) ||
        currentPlayer.mediaItemCount != 1 ||
        !currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    ) {
      return null
    }
    val currentMediaItem = currentPlayer.currentMediaItem ?: return null
    val matchesIgnoringClipping =
      currentMediaItem
        .buildUpon()
        .setClippingConfiguration(mediaItem.clippingConfiguration)
        .build() == mediaItem
    return if (matchesIgnoringClipping) currentMediaItem else null
  }

  /**
   * Updates [videoDurationUs] from [currentPlayer] if the duration of the whole video is known (or
   * resets it to [C.TIME_UNSET] otherwise), and returns whether [videoDurationUs] changed.
   *
   * [Timeline.Period.getOriginalDurationMs] is used rather than [Player.getDuration] because
   * [Player.getDuration] only reports the clipped window length when the item is clipped.
   */
  @CanIgnoreReturnValue
  private fun updateVideoDuration(currentPlayer: Player): Boolean {
    val previousVideoDurationUs = videoDurationUs
    if (getMatchingMediaItemFromPlayer(currentPlayer) == null) {
      videoDurationUs = C.TIME_UNSET
      return videoDurationUs != previousVideoDurationUs
    }
    val timeline = currentPlayer.currentTimeline
    val window = timeline.getWindow(currentPlayer.currentMediaItemIndex, Timeline.Window())
    val durationMs =
      timeline.getPeriod(window.firstPeriodIndex, Timeline.Period()).originalDurationMs
    videoDurationUs = if (durationMs != C.TIME_UNSET) Util.msToUs(durationMs) else C.TIME_UNSET
    return videoDurationUs != previousVideoDurationUs
  }

  /**
   * Returns the boundary to resolve [boundary] as: [MotionPhotoConfig.BOUNDARY_PHOTO_FRAME] falls
   * back to [MotionPhotoConfig.BOUNDARY_END_OF_VIDEO], treating the still photo as the last frame
   * of the video, once the tracks of the item are known to carry no usable [MotionPhotoMetadata]
   * (or when [currentPlayer] cannot report tracks), and stays unresolved while the tracks are not
   * known yet.
   */
  private fun resolveBoundary(
    currentPlayer: Player,
    boundary: @MotionPhotoConfig.PlaybackBoundary Int,
  ): @MotionPhotoConfig.PlaybackBoundary Int {
    val areTracksKnownOrUnavailable =
      hasKnownTracks || !currentPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS)
    if (
      boundary != MotionPhotoConfig.BOUNDARY_PHOTO_FRAME ||
        photoPresentationTimestampUs != C.TIME_UNSET ||
        !areTracksKnownOrUnavailable
    ) {
      return boundary
    }
    if (!hasWarnedAboutPhotoFrameFallback) {
      hasWarnedAboutPhotoFrameFallback = true
      Log.w(
        TAG,
        "BOUNDARY_PHOTO_FRAME requested but no usable MotionPhotoMetadata is available; treating" +
          " the still photo as the last frame of the video.",
      )
    }
    return MotionPhotoConfig.BOUNDARY_END_OF_VIDEO
  }

  private fun resolveStartPositionUs(startBoundary: @MotionPhotoConfig.PlaybackBoundary Int): Long {
    val offsetUs = Util.msToUs(_config.startBoundaryOffsetMs)
    if (startBoundary == MotionPhotoConfig.BOUNDARY_START_OF_VIDEO && offsetUs == 0L) {
      return 0L
    }
    // Any other start may lie at or beyond the end of the video, which leaves nothing to play and
    // has to be detected before the item is clipped, so that the still photo stays visible without
    // the player reporting STATE_ENDED (see syncMediaItem). The duration is therefore
    // required even when the photo frame itself is known, unlike for an end position, which the
    // player clamps to the end of the video.
    if (videoDurationUs == C.TIME_UNSET) {
      return C.TIME_UNSET
    }
    val baseUs =
      when (startBoundary) {
        MotionPhotoConfig.BOUNDARY_START_OF_VIDEO -> 0L
        MotionPhotoConfig.BOUNDARY_PHOTO_FRAME -> photoPresentationTimestampUs
        else -> videoDurationUs
      }
    return if (baseUs != C.TIME_UNSET) baseUs + offsetUs else C.TIME_UNSET
  }

  private fun resolveEndPositionUs(endBoundary: @MotionPhotoConfig.PlaybackBoundary Int): Long {
    val offsetUs = Util.msToUs(_config.endBoundaryOffsetMs)
    if (endBoundary == MotionPhotoConfig.BOUNDARY_END_OF_VIDEO && offsetUs >= 0L) {
      // The offset of a BOUNDARY_PHOTO_FRAME end that fell back may be positive, so an end beyond
      // the end of the video is clamped to it.
      return C.TIME_END_OF_SOURCE
    }
    val baseUs =
      when (endBoundary) {
        MotionPhotoConfig.BOUNDARY_START_OF_VIDEO -> 0L
        MotionPhotoConfig.BOUNDARY_PHOTO_FRAME -> photoPresentationTimestampUs
        else -> videoDurationUs
      }
    if (baseUs == C.TIME_UNSET) {
      return C.TIME_UNSET
    }
    return baseUs + offsetUs
  }

  @CanIgnoreReturnValue
  private fun syncMediaItem(currentPlayer: Player): Boolean {
    val startBoundary = resolveBoundary(currentPlayer, _config.startBoundary)
    val resolvedStartPositionUs = resolveStartPositionUs(startBoundary)
    // A start before the start of the video is clamped to it, as the player would do.
    val startPositionUs =
      if (resolvedStartPositionUs == C.TIME_UNSET) C.TIME_UNSET
      else maxOf(0L, resolvedStartPositionUs)
    val endBoundary = resolveBoundary(currentPlayer, _config.endBoundary)
    val endPositionUs = resolveEndPositionUs(endBoundary)
    val isStartOutOfBounds =
      startPositionUs == C.TIME_UNSET ||
        (videoDurationUs != C.TIME_UNSET && startPositionUs >= videoDurationUs)
    val isEndOutOfBounds =
      endPositionUs == C.TIME_UNSET ||
        (endPositionUs != C.TIME_END_OF_SOURCE && endPositionUs <= startPositionUs)
    val loadedMediaItem = getMatchingMediaItemFromPlayer(currentPlayer)
    if (isStartOutOfBounds || isEndOutOfBounds) {
      val didUpdateMediaItem =
        // Ensure mediaItem is set on the player when boundaries cannot be resolved until the
        // player prepares the item and reports its timeline.
        loadedMediaItem == null && syncMediaItem(currentPlayer, mediaItem.clippingConfiguration)
      isPlaybackRangeEmpty = true
      syncPlaybackState(currentPlayer)
      return didUpdateMediaItem
    }
    val wasPlaybackRangeEmpty = isPlaybackRangeEmpty
    isPlaybackRangeEmpty = false
    // The photo frame itself must be decoded at the end of a range ending at BOUNDARY_PHOTO_FRAME
    // without an offset (see PHOTO_FRAME_TIMESTAMP_TOLERANCE_US), so that end position is moved
    // later. The un-toleranced position is used for everything else, in particular for deciding
    // whether the range is empty.
    val clippingEndPositionUs =
      if (
        endBoundary == MotionPhotoConfig.BOUNDARY_PHOTO_FRAME && _config.endBoundaryOffsetMs == 0L
      ) {
        endPositionUs + PHOTO_FRAME_TIMESTAMP_TOLERANCE_US
      } else {
        endPositionUs
      }
    // The state owns the clipping of mediaItem: a caller-supplied end position could otherwise end
    // up before a start position resolved relative to the end of the video.
    val clippingConfiguration =
      mediaItem.clippingConfiguration
        .buildUpon()
        .setStartPositionUs(startPositionUs)
        .setEndPositionUs(clippingEndPositionUs)
        .build()
    val didUpdateMediaItem =
      clippingConfiguration != loadedMediaItem?.clippingConfiguration &&
        syncMediaItem(currentPlayer, clippingConfiguration, endPositionUs = endPositionUs)
    if (didUpdateMediaItem && loadedMediaItem == null && !wasPlaybackRangeEmpty) {
      // The first frame of the newly set item renders underneath the still photo before motion
      // resumes.
      syncPlayWhenReady(currentPlayer)
    }
    if (wasPlaybackRangeEmpty) {
      if (isPhotoTarget) {
        transitionToStillPhoto(currentPlayer)
      } else {
        transitionToMotion(currentPlayer, allowPrepareOnError = false)
      }
    }
    return didUpdateMediaItem
  }

  /**
   * Sets [mediaItem] with [clippingConfiguration] on [currentPlayer], or updates its clipping in
   * place via [Player.replaceMediaItem] if [mediaItem] is already loaded, and returns whether the
   * item on [currentPlayer] was updated.
   *
   * An in-place update that keeps the start position preserves the current playback position
   * (unless [isPhotoTarget] is `true` and [MotionPhotoConfig.resumeVideoFromPreviousPosition] is
   * `false`, or the current position is at or beyond the new end position), while a change of the
   * start position restarts from the new start.
   */
  private fun syncMediaItem(
    currentPlayer: Player,
    clippingConfiguration: MediaItem.ClippingConfiguration,
    endPositionUs: Long = clippingConfiguration.endPositionUs,
  ): Boolean {
    val clippedMediaItem =
      mediaItem.buildUpon().setClippingConfiguration(clippingConfiguration).build()
    val loadedMediaItem = getMatchingMediaItemFromPlayer(currentPlayer)
    if (loadedMediaItem == null) {
      if (!currentPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
        return false
      }
      cancelDeferredRewind()
      currentPlayer.setMediaItem(clippedMediaItem)
      hasRenderedFirstFrame = false
      return true
    }
    if (!currentPlayer.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return false
    }
    val previousClippingConfiguration = loadedMediaItem.clippingConfiguration
    currentPlayer.replaceMediaItem(0, clippedMediaItem)
    val isCurrentPositionPastEnd =
      if (
        previousClippingConfiguration.startPositionUs == clippingConfiguration.startPositionUs &&
          endPositionUs != C.TIME_END_OF_SOURCE
      ) {
        val rangeDurationUs = endPositionUs - clippingConfiguration.startPositionUs
        Util.msToUs(currentPlayer.currentPosition) >= rangeDurationUs
      } else {
        false
      }
    if (
      previousClippingConfiguration.startPositionUs != clippingConfiguration.startPositionUs ||
        (isPhotoTarget && !_config.resumeVideoFromPreviousPosition) ||
        isCurrentPositionPastEnd
    ) {
      cancelDeferredRewind()
      seekToStartPosition(currentPlayer)
    }
    return true
  }

  companion object {
    private const val TAG = "MotionPhotoState"

    // Tolerance added to a clipping end position resolved from BOUNDARY_PHOTO_FRAME, so that the
    // photo frame itself is decoded at the end of the range: ClippingSampleStream's end position is
    // exclusive (buffer.timeUs >= endUs), and the XMP timestamp and the sample timestamps the
    // extractor derives from the track timescale (rounded down) can disagree by up to one timescale
    // tick. 1ms covers track timescales down to 1kHz while staying well below the shortest frame
    // interval (about 8ms at 120fps), so no neighbouring frame is included.
    private const val PHOTO_FRAME_TIMESTAMP_TOLERANCE_US = 1_000L

    init {
      MediaLibraryInfo.registerModule("media3.ui.compose.motionphoto")
    }
  }
}

/**
 * Configuration for Motion Photo playback behavior.
 *
 * Playback boundaries are applied through [MediaItem.ClippingConfiguration] via
 * [Player.setMediaItem] and updated in place via [Player.replaceMediaItem]; see
 * [rememberMotionPhotoState] for how to configure `ExoPlayer`.
 *
 * @property isLooping Whether the motion video should loop continuously instead of returning to the
 *   still photo when playback reaches the end. If the [Player] supports neither
 *   [Player.COMMAND_SET_REPEAT_MODE] nor [Player.COMMAND_SEEK_TO_DEFAULT_POSITION], playback
 *   returns to the still photo instead of looping.
 * @property resumeVideoFromPreviousPosition Whether resuming motion playback should continue from
 *   the paused position rather than rewinding to the start position when returning to the still
 *   photo. When `false`, the rewind is deferred until the crossfade to the still photo has
 *   completed, so that it happens underneath the opaque photo.
 * @property transitionDurationMs The duration of the alpha crossfade transition between the still
 *   photo and video in milliseconds. Must be non-negative. Motion playback starts once the first
 *   frame has rendered underneath the still photo and the crossfade has completed, so that the
 *   still photo fades out over a stationary frame.
 * @property startBoundary The [PlaybackBoundary] anchor from which motion playback starts.
 * @property startBoundaryOffsetMs The offset in milliseconds added to [startBoundary] to compute
 *   the start position. Must be non-negative when [startBoundary] is [BOUNDARY_START_OF_VIDEO] and
 *   non-positive when [startBoundary] is [BOUNDARY_END_OF_VIDEO]. A start position before the start
 *   of the video is clamped to it. A start position at or beyond the end of the video (including
 *   [BOUNDARY_END_OF_VIDEO] with an offset of `0`) leaves nothing to play, so the still photo stays
 *   visible without mutating the requested motion state.
 * @property endBoundary The [PlaybackBoundary] anchor at which motion playback ends.
 * @property endBoundaryOffsetMs The offset in milliseconds added to [endBoundary] to compute the
 *   end position. Must be non-negative when [endBoundary] is [BOUNDARY_START_OF_VIDEO] and
 *   non-positive when [endBoundary] is [BOUNDARY_END_OF_VIDEO]. An end position beyond the end of
 *   the video is clamped to it by the player. An end position at or before the start position
 *   (including [BOUNDARY_START_OF_VIDEO] with an offset of `0`) leaves nothing to play, so the
 *   still photo stays visible without mutating the requested motion state.
 */
// TODO(b/544627271): Add custom key photo frame anchor timestamp
// (`customImageAnchorTimestampMs`) and remaining presets in follow-up CLs.
@Immutable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoConfig(
  val isLooping: Boolean = false,
  val resumeVideoFromPreviousPosition: Boolean = false,
  @IntRange(from = 0) val transitionDurationMs: Long = DEFAULT_TRANSITION_DURATION_MS,
  val startBoundary: @PlaybackBoundary Int = BOUNDARY_START_OF_VIDEO,
  val startBoundaryOffsetMs: Long = 0L,
  val endBoundary: @PlaybackBoundary Int = BOUNDARY_END_OF_VIDEO,
  val endBoundaryOffsetMs: Long = 0L,
) {

  init {
    require(transitionDurationMs >= 0) { "transitionDurationMs must be non-negative." }
    require(
      startBoundary == BOUNDARY_START_OF_VIDEO ||
        startBoundary == BOUNDARY_PHOTO_FRAME ||
        startBoundary == BOUNDARY_END_OF_VIDEO
    ) {
      "Invalid startBoundary: $startBoundary"
    }
    require(startBoundary != BOUNDARY_START_OF_VIDEO || startBoundaryOffsetMs >= 0L) {
      "startBoundaryOffsetMs must be non-negative when startBoundary is BOUNDARY_START_OF_VIDEO."
    }
    require(startBoundary != BOUNDARY_END_OF_VIDEO || startBoundaryOffsetMs <= 0L) {
      "startBoundaryOffsetMs must be non-positive when startBoundary is BOUNDARY_END_OF_VIDEO."
    }
    require(
      endBoundary == BOUNDARY_START_OF_VIDEO ||
        endBoundary == BOUNDARY_PHOTO_FRAME ||
        endBoundary == BOUNDARY_END_OF_VIDEO
    ) {
      "Invalid endBoundary: $endBoundary"
    }
    require(endBoundary != BOUNDARY_START_OF_VIDEO || endBoundaryOffsetMs >= 0L) {
      "endBoundaryOffsetMs must be non-negative when endBoundary is BOUNDARY_START_OF_VIDEO."
    }
    require(endBoundary != BOUNDARY_END_OF_VIDEO || endBoundaryOffsetMs <= 0L) {
      "endBoundaryOffsetMs must be non-positive when endBoundary is BOUNDARY_END_OF_VIDEO."
    }
  }

  /** Returns a copy of this [MotionPhotoConfig] with the given properties updated. */
  fun copy(
    isLooping: Boolean = this.isLooping,
    resumeVideoFromPreviousPosition: Boolean = this.resumeVideoFromPreviousPosition,
    @IntRange(from = 0) transitionDurationMs: Long = this.transitionDurationMs,
    startBoundary: @PlaybackBoundary Int = this.startBoundary,
    startBoundaryOffsetMs: Long = this.startBoundaryOffsetMs,
    endBoundary: @PlaybackBoundary Int = this.endBoundary,
    endBoundaryOffsetMs: Long = this.endBoundaryOffsetMs,
  ): MotionPhotoConfig =
    MotionPhotoConfig(
      isLooping = isLooping,
      resumeVideoFromPreviousPosition = resumeVideoFromPreviousPosition,
      transitionDurationMs = transitionDurationMs,
      startBoundary = startBoundary,
      startBoundaryOffsetMs = startBoundaryOffsetMs,
      endBoundary = endBoundary,
      endBoundaryOffsetMs = endBoundaryOffsetMs,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MotionPhotoConfig) return false
    return isLooping == other.isLooping &&
      resumeVideoFromPreviousPosition == other.resumeVideoFromPreviousPosition &&
      transitionDurationMs == other.transitionDurationMs &&
      startBoundary == other.startBoundary &&
      startBoundaryOffsetMs == other.startBoundaryOffsetMs &&
      endBoundary == other.endBoundary &&
      endBoundaryOffsetMs == other.endBoundaryOffsetMs
  }

  override fun hashCode(): Int {
    var result = isLooping.hashCode()
    result = 31 * result + resumeVideoFromPreviousPosition.hashCode()
    result = 31 * result + transitionDurationMs.hashCode()
    result = 31 * result + startBoundary
    result = 31 * result + startBoundaryOffsetMs.hashCode()
    result = 31 * result + endBoundary
    result = 31 * result + endBoundaryOffsetMs.hashCode()
    return result
  }

  /**
   * Boundary anchor points for Motion Photo playback. One of [BOUNDARY_START_OF_VIDEO],
   * [BOUNDARY_PHOTO_FRAME], or [BOUNDARY_END_OF_VIDEO].
   *
   * @see BOUNDARY_START_OF_VIDEO
   * @see BOUNDARY_PHOTO_FRAME
   * @see BOUNDARY_END_OF_VIDEO
   */
  @Retention(AnnotationRetention.SOURCE)
  @Target(AnnotationTarget.TYPE)
  @MustBeDocumented
  @IntDef(BOUNDARY_START_OF_VIDEO, BOUNDARY_PHOTO_FRAME, BOUNDARY_END_OF_VIDEO)
  annotation class PlaybackBoundary

  companion object {
    /** Boundary at the start of the video track (`0ms`). */
    const val BOUNDARY_START_OF_VIDEO = 1

    /**
     * Boundary at the presentation timestamp of the still photo frame from container metadata
     * ([MotionPhotoMetadata.photoPresentationTimestampUs]). A photo frame at or before the start
     * position leaves nothing to play when used as the end, and a photo frame at or beyond the end
     * of the video leaves nothing to play when used as the start, whereas a photo frame end beyond
     * the end of the video is clamped to it by the player.
     *
     * Once the tracks of the item are known to carry no usable metadata, or if the player cannot
     * report tracks, the still photo is treated as the last frame of the video: the boundary falls
     * back to [BOUNDARY_END_OF_VIDEO], keeping its offset. For example, [PLAY_TO_PHOTO] degrades to
     * the whole video, a start of `BOUNDARY_PHOTO_FRAME - 300ms` with an end of
     * `BOUNDARY_PHOTO_FRAME + 300ms` to the last 300ms of the video, and a start at the photo frame
     * itself leaves nothing to play. The timestamp is read from the track metadata the extractor
     * exposes via `Format.metadata` on a direct in-process [Player] such as `ExoPlayer`
     * (`MediaController` is not supported); a [Player] implementation or source that does not
     * expose `Format` metadata triggers the fallback.
     */
    const val BOUNDARY_PHOTO_FRAME = 2

    /** Boundary at the end of the video track (`duration`). */
    const val BOUNDARY_END_OF_VIDEO = 3

    /** Default crossfade transition duration in milliseconds. */
    const val DEFAULT_TRANSITION_DURATION_MS = 200L

    /** Default configuration instance that plays motion once and returns to the still photo. */
    @JvmField val DEFAULT = MotionPhotoConfig()

    /**
     * Configuration preset that plays motion from the start of the video up to the key photo frame
     * ([BOUNDARY_PHOTO_FRAME]) and returns to the still photo. A photo frame at the start of the
     * video (`photoPresentationTimestampUs == 0`) leaves nothing to play, so the still photo stays
     * visible. If no usable `MotionPhotoMetadata` is available, [BOUNDARY_PHOTO_FRAME] falls back
     * to [BOUNDARY_END_OF_VIDEO] so the full video plays instead.
     */
    @JvmField
    val PLAY_TO_PHOTO =
      MotionPhotoConfig(startBoundary = BOUNDARY_START_OF_VIDEO, endBoundary = BOUNDARY_PHOTO_FRAME)

    /** Configuration preset that loops motion playback continuously. */
    @JvmField val LOOP = MotionPhotoConfig(isLooping = true)
  }
}
