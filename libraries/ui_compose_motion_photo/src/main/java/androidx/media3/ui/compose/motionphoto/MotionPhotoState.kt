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
import kotlinx.coroutines.awaitCancellation
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
 * [Player.REPEAT_MODE_ONE] or [Player.REPEAT_MODE_OFF] according to [MotionPhotoConfig.loopMode]
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
 * boundary changes (unless the current position is at or beyond the new end) and restarting from
 * scratch (see [MotionPhotoConfig.loopMode]) when the start boundary changes. The start and end
 * positions of [mediaItem]'s [MediaItem.ClippingConfiguration] are overridden by the resolved
 * boundaries. Callers should not modify the [Player]'s playlist while it is bound to this state.
 *
 * The supplied [Player] must support updating an item's [MediaItem.ClippingConfiguration] in-place
 * using [Player.replaceMediaItem]; see [rememberMotionPhotoState] for how to configure `ExoPlayer`.
 *
 * This state holder also owns [Player.playWhenReady] and [Player.repeatMode] while bound:
 * [Player.playWhenReady] is driven by the motion/still-photo state (callers should pause via
 * [showStillPhoto] or detach by passing `player = null` rather than calling [Player.pause] or
 * [Player.stop] directly), and [Player.repeatMode] is set to [Player.REPEAT_MODE_ONE] or
 * [Player.REPEAT_MODE_OFF] according to [MotionPhotoConfig.loopMode] and is not restored when the
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
  /**
   * Whether [player] has yet to return to the start position. Set when it is bound, when the still
   * photo is shown or resume is turned off while it is shown, and before it is prepared from
   * [Player.STATE_IDLE] (it may have stopped mid-motion). Always `false` while
   * [MotionPhotoConfig.resumeVideoFromPreviousPosition] is `true`. Cleared once [rewind] seeks, and
   * when a new media item or player resets the position anyway.
   */
  private var isRewindPending = false

  /** The [Player] driving media playback. */
  internal var player: Player?
    get() = _player
    set(value) {
      if (_player != value) {
        cancelDeferredRewind()
        cancelDeferredPlay()
        isRewindPending = false
        _player = value
        isMotionUnavailable = false
        isPlaybackRangeEmpty = false
        hasRenderedFirstFrame = false
        isAwaitingStartSeekReady = false
        videoDurationUs = C.TIME_UNSET
        photoPresentationTimestampUs = C.TIME_UNSET
        hasKnownTracks = false
        hasWarnedAboutPhotoFrameFallback = false
        appliedPhotoFrameStartPositionMs = C.TIME_UNSET
        isNewItemAwaitingInitialStart = false
        loadedClippingHasPhotoFrameEndTolerance = false
      }
    }

  private var isMotionUnavailable by mutableStateOf(false)
  private var isPlaybackRangeEmpty by mutableStateOf(false)
  private var hasRenderedFirstFrame by mutableStateOf(false)
  private var isAwaitingStartSeekReady by mutableStateOf(false)
  private var motionTarget by
    mutableStateOf(if (initialShowMotion) MotionTarget.MOTION else MotionTarget.PHOTO_FORCED)
  private var videoDurationUs: Long = C.TIME_UNSET
  private var photoPresentationTimestampUs: Long = C.TIME_UNSET
  private var hasKnownTracks = false
  private var hasWarnedAboutPhotoFrameFallback = false
  private var appliedPhotoFrameStartPositionMs: Long = C.TIME_UNSET
  private var isNewItemAwaitingInitialStart = false
  private var loadedClippingHasPhotoFrameEndTolerance = false

  private val isPhotoTarget: Boolean
    get() = motionTarget != MotionTarget.MOTION

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
    motionTarget = MotionTarget.MOTION
    player?.let { currentPlayer ->
      if (
        !_config.resumeVideoFromPreviousPosition && currentPlayer.playbackState == Player.STATE_IDLE
      ) {
        isRewindPending = true
      }
      syncMotion(currentPlayer, allowPrepareOnError = true)
    }
  }

  /**
   * Requests showing the still photo instead of playing motion.
   *
   * Pauses motion while keeping [config] in place. Calling this while the still photo is already
   * requested is a no-op.
   */
  fun showStillPhoto() {
    val wasPhotoTarget = isPhotoTarget
    motionTarget = MotionTarget.PHOTO_FORCED
    if (!wasPhotoTarget) {
      if (hasRenderedFirstFrame && player?.playWhenReady == true) {
        isNewItemAwaitingInitialStart = false
      }
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
    motionTarget = MotionTarget.MOTION_ENDED
    if (player?.playWhenReady == true) {
      isNewItemAwaitingInitialStart = false
    }
    player?.let { transitionToStillPhoto(it) }
  }

  private var _config by mutableStateOf(initialConfig)

  /**
   * The [MotionPhotoConfig] governing motion playback behavior.
   *
   * This value is initialized from `initialConfig` and can be updated by the application to change
   * playback behavior dynamically. Setting this property syncs the attached [Player] accordingly,
   * except changes to the photo-frame start position implied by [MotionPhotoConfig.loopMode], which
   * are not reconciled immediately while stationary.
   */
  // TODO(b/544627271): Reconcile loopMode start-position updates immediately while stationary.
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
        isRewindPending = false
      } else if (previousConfig.resumeVideoFromPreviousPosition && isPhotoTarget) {
        isRewindPending = true
      }
      // Play-once finishes on the still photo, so changing non-looping options (e.g., boundaries
      // or transition duration) should not auto-play motion until playMotion() or looping is set.
      val restartedEndedMotion =
        !previousConfig.isLooping && _config.isLooping && motionTarget == MotionTarget.MOTION_ENDED
      val currentPlayer = player
      if (currentPlayer == null) {
        if (restartedEndedMotion) {
          motionTarget = MotionTarget.MOTION
        }
        return
      }
      if (
        previousConfig.rangeStart != _config.rangeStart ||
          previousConfig.rangeEnd != _config.rangeEnd
      ) {
        syncMediaItem(currentPlayer)
      }
      if (restartedEndedMotion) {
        motionTarget = MotionTarget.MOTION
        syncMotion(currentPlayer, allowPrepareOnError = false)
      } else if (
        currentPlayer.playbackState == Player.STATE_ENDED && !_config.isLooping && !isPhotoTarget
      ) {
        markMotionEnded()
      } else {
        if (isRewindPending && isPhotoTarget && deferredRewindJob == null) {
          rewind(currentPlayer)
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
          !hasRenderedFirstFrame ||
          isAwaitingStartSeekReady,
      transitionDurationMs = _config.transitionDurationMs,
    )
  }

  internal fun bindAndInitialize() {
    val currentPlayer = player ?: return
    isRewindPending = !_config.resumeVideoFromPreviousPosition
    updateMotionPhotoMetadata(currentPlayer)
    updateVideoDuration(currentPlayer)
    val wasUnpreparedAtStart =
      currentPlayer.playbackState == Player.STATE_IDLE &&
        currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) &&
        currentPlayer.currentPosition == 0L
    val didUpdateMediaItem = syncMediaItem(currentPlayer)
    if (wasUnpreparedAtStart) {
      isNewItemAwaitingInitialStart = true
    }
    // If no new item or clipping update was applied, an existing error is not stale.
    prepare(currentPlayer, allowPrepareOnError = didUpdateMediaItem)
    updateMotionAvailability(currentPlayer)
    // Re-check in case setMediaItem or prepare synchronously updated state, tracks, or timeline
    // before observe() registered its listener.
    val didMetadataChange = updateMotionPhotoMetadata(currentPlayer)
    val didDurationChange = updateVideoDuration(currentPlayer)
    if (didMetadataChange || didDurationChange) {
      syncMediaItem(currentPlayer)
    }
    if (_config.resumeVideoFromPreviousPosition) {
      reconcileStartPosition(currentPlayer)
    }
    if (isPhotoTarget) {
      syncStillPhoto(currentPlayer)
    } else {
      syncMotion(currentPlayer, allowPrepareOnError = false)
    }
  }

  internal suspend fun observe(): Nothing {
    val currentPlayer = player ?: awaitCancellation()
    try {
      observePlayerEvents(currentPlayer)
    } finally {
      // The effect running observe() shares this state's lifetime. A rewind that is still pending
      // when it ends must not reach a player that a newer state may have re-bound in the meantime.
      cancelDeferredRewind()
      cancelDeferredPlay()
    }
  }

  private suspend fun observePlayerEvents(currentPlayer: Player): Nothing {
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
      val isTracksCleared =
        events.contains(Player.EVENT_TRACKS_CHANGED) &&
          (!isCommandAvailable(Player.COMMAND_GET_TRACKS) || currentTracks.isEmpty)
      val isStoppedToIdle =
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && playbackState == Player.STATE_IDLE
      val shouldResetRenderedFrame = isTracksCleared || isStoppedToIdle
      if (shouldResetRenderedFrame) {
        hasRenderedFirstFrame = false
        if (isStoppedToIdle && !_config.resumeVideoFromPreviousPosition) {
          isRewindPending = true
        }
      }
      val didSyncMediaItem =
        didMetadataChange ||
          didDurationChange ||
          events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)
      if (didSyncMediaItem) {
        syncMediaItem(this)
      }
      if (
        isRewindPending &&
          deferredRewindJob == null &&
          !isPlaybackRangeEmpty &&
          playbackState != Player.STATE_IDLE &&
          playbackState != Player.STATE_ENDED
      ) {
        rewind(this)
      } else if (
        !didSyncMediaItem &&
          events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
          playbackState != Player.STATE_IDLE &&
          playbackState != Player.STATE_ENDED
      ) {
        reconcileStartPosition(this)
      }
      if (events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) {
        syncPlaybackState(this)
      }
      if (shouldResetRenderedFrame) {
        // An item being prepared again or stopped resets to its unrendered state, so motion waits
        // for a new first frame underneath the still photo.
        syncPlayWhenReady(this)
      }
      if (events.contains(Player.EVENT_RENDERED_FIRST_FRAME) && !isStoppedToIdle) {
        hasRenderedFirstFrame = true
        if (!isPhotoTarget && !isPlaybackRangeEmpty && !isAwaitingStartSeekReady) {
          isNewItemAwaitingInitialStart = false
        }
        if (isPlayPendingFirstFrame) {
          syncPlayWhenReady(this)
        }
      }
      if (
        isAwaitingStartSeekReady &&
          events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) &&
          playbackState != Player.STATE_BUFFERING
      ) {
        isAwaitingStartSeekReady = false
        if (!isPhotoTarget && !isPlaybackRangeEmpty && hasRenderedFirstFrame) {
          isNewItemAwaitingInitialStart = false
        }
        syncPlayWhenReady(this)
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
        events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) && playbackState == Player.STATE_ENDED
      ) {
        isNewItemAwaitingInitialStart = false
        if (!_config.isLooping) {
          markMotionEnded()
        } else if (!isPhotoTarget) {
          // Repeat mode is unsupported or was not applied by the player, so emulate
          // REPEAT_MODE_ONE manually.
          if (isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
            appliedPhotoFrameStartPositionMs = C.TIME_UNSET
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
      isAwaitingStartSeekReady = false
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
    // tracks, so a pending Boundary.PHOTO_FRAME falls back rather than staying unresolved.
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

  /**
   * Reconciles [currentPlayer]'s start position when photo-frame metadata, duration, or seek
   * capabilities resolve while the still photo is shown (or [allowAfterFirstFrame] is `true` when
   * exiting an empty playback range, which also showed the still photo) or before the first frame
   * of motion has rendered.
   *
   * Only seeks when a photo-frame start position becomes actionable and has not yet been applied,
   * or falls back to the start of the range when reverting a previously applied photo-frame start,
   * avoiding unnecessary seeks during preparation when motion starts at the range start anyway.
   */
  @CanIgnoreReturnValue
  private fun reconcileStartPosition(
    currentPlayer: Player,
    allowAfterFirstFrame: Boolean = false,
  ): Boolean {
    if (
      isPlaybackRangeEmpty ||
        deferredRewindJob?.isActive == true ||
        (!hasKnownTracks && currentPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS)) ||
        (currentPlayer.playbackState == Player.STATE_IDLE &&
          appliedPhotoFrameStartPositionMs == C.TIME_UNSET) ||
        (!isPhotoTarget &&
          hasRenderedFirstFrame &&
          !allowAfterFirstFrame &&
          !isAwaitingStartSeekReady) ||
        (_config.resumeVideoFromPreviousPosition && !isNewItemAwaitingInitialStart)
    ) {
      return false
    }
    if (resolvedPhotoFrameStartPositionMs(currentPlayer) == appliedPhotoFrameStartPositionMs) {
      return false
    }
    val didSeek = seekToStartPosition(currentPlayer, fallbackToDefaultPosition = false)
    if (didSeek) {
      cancelDeferredRewind()
      isRewindPending = false
    }
    return didSeek
  }

  private fun prepare(currentPlayer: Player, allowPrepareOnError: Boolean) {
    if (
      currentPlayer.playbackState == Player.STATE_IDLE &&
        (currentPlayer.playerError == null || allowPrepareOnError) &&
        currentPlayer.isCommandAvailable(Player.COMMAND_PREPARE)
    ) {
      currentPlayer.prepare()
      // Refresh synchronously in case prepare() cleared a previous playerError before
      // observePlayerEvents() processes the error change.
      updateMotionAvailability(currentPlayer)
    }
  }

  private fun rewind(currentPlayer: Player) {
    cancelDeferredRewind()
    if (seekToStartPosition(currentPlayer)) {
      isRewindPending = false
    }
  }

  /**
   * Owes a rewind for the still photo that was just requested, deferred until the crossfade to it
   * has completed if there is one, and syncs [currentPlayer] to it.
   */
  private fun transitionToStillPhoto(currentPlayer: Player) {
    cancelDeferredRewind()
    if (!_config.resumeVideoFromPreviousPosition) {
      isRewindPending = true
      if (shouldDeferRewind()) {
        deferredRewindJob = launchAfterTransition { rewind(currentPlayer) }
      }
    }
    syncStillPhoto(currentPlayer)
  }

  /**
   * Syncs [currentPlayer] while the still photo is the target. Unlike [syncPlaybackState], this
   * prepares the player and performs a rewind that is still pending (or that ended playback needs),
   * but it never starts or restarts a deferred one.
   */
  private fun syncStillPhoto(currentPlayer: Player) {
    prepare(currentPlayer, allowPrepareOnError = false)
    if (
      !_config.resumeVideoFromPreviousPosition &&
        deferredRewindJob == null &&
        (isRewindPending || currentPlayer.playbackState == Player.STATE_ENDED)
    ) {
      rewind(currentPlayer)
    }
    syncPlaybackState(currentPlayer)
  }

  /**
   * Syncs [currentPlayer] while motion is the target. Unlike [syncPlaybackState], this prepares the
   * player and performs a rewind if one is still pending or playback has ended.
   */
  private fun syncMotion(currentPlayer: Player, allowPrepareOnError: Boolean) {
    prepare(currentPlayer, allowPrepareOnError)
    if (isRewindPending || currentPlayer.playbackState == Player.STATE_ENDED) {
      rewind(currentPlayer)
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
      isNewItemAwaitingInitialStart = false
      currentPlayer.playWhenReady = true
      return
    }
    if (isAwaitingStartSeekReady) {
      deferredPlayJob?.cancel()
      deferredPlayJob = null
      currentPlayer.playWhenReady = false
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
          isNewItemAwaitingInitialStart = false
          currentPlayer.playWhenReady = true
        }
      }
    } else {
      cancelDeferredPlay()
      isNewItemAwaitingInitialStart = false
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

  /**
   * Seeks [currentPlayer] to where motion playback starts from scratch: the photo frame if
   * [MotionPhotoConfig.loopMode] is [MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME] and applies (see
   * [motionStartPositionMs]), otherwise the start of the playback range.
   *
   * A player that cannot seek within the current item is left alone if it already reports the photo
   * frame as its position; otherwise it seeks to the start of the range when
   * [fallbackToDefaultPosition] is `true`, or is left alone so a later update can apply the photo
   * frame once seeking becomes available.
   */
  @CanIgnoreReturnValue
  private fun seekToStartPosition(
    currentPlayer: Player,
    fallbackToDefaultPosition: Boolean = true,
  ): Boolean {
    if (
      !currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM) ||
        currentPlayer.currentMediaItem == null
    ) {
      return false
    }
    val wasAwaitingStartSeekReady = isAwaitingStartSeekReady
    val startPositionMs = resolvedPhotoFrameStartPositionMs(currentPlayer)
    if (startPositionMs != C.TIME_UNSET) {
      if (currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
        currentPlayer.seekTo(startPositionMs)
        appliedPhotoFrameStartPositionMs = startPositionMs
        isAwaitingStartSeekReady = currentPlayer.playbackState == Player.STATE_BUFFERING
        if (isAwaitingStartSeekReady || wasAwaitingStartSeekReady) {
          syncPlayWhenReady(currentPlayer)
        }
        return true
      }
      if (
        currentPlayer.playbackState != Player.STATE_ENDED &&
          currentPlayer.currentPosition == startPositionMs
      ) {
        appliedPhotoFrameStartPositionMs = startPositionMs
        return true
      }
      if (!fallbackToDefaultPosition) {
        return false
      }
    }
    if (currentPlayer.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)) {
      val hadAppliedPhotoFrame = appliedPhotoFrameStartPositionMs != C.TIME_UNSET
      appliedPhotoFrameStartPositionMs = C.TIME_UNSET
      currentPlayer.seekToDefaultPosition()
      isAwaitingStartSeekReady =
        (wasAwaitingStartSeekReady || hadAppliedPhotoFrame) &&
          currentPlayer.playbackState == Player.STATE_BUFFERING
      if (isAwaitingStartSeekReady || wasAwaitingStartSeekReady) {
        syncPlayWhenReady(currentPlayer)
      }
      return true
    }
    return false
  }

  private fun resolvedPhotoFrameStartPositionMs(currentPlayer: Player): Long {
    if (isPlaybackRangeEmpty) {
      return C.TIME_UNSET
    }
    val loadedClippingConfiguration =
      getMatchingMediaItemFromPlayer(currentPlayer)?.clippingConfiguration ?: return C.TIME_UNSET
    return motionStartPositionMs(
      loadedClippingConfiguration.startPositionUs,
      loadedClippingConfiguration.endPositionUs,
    )
  }

  /**
   * Returns the position within the playback range `[rangeStartUs, rangeEndUs)` (relative to its
   * start, in milliseconds) from which motion playback starts from scratch, or [C.TIME_UNSET] to
   * start from the start of the range.
   *
   * With [MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME], playback starts from the photo frame when
   * its timestamp is known from the metadata and lies inside the range, so that the crossfade from
   * the still photo reveals the matching video frame. A range that starts at the photo frame needs
   * no seek, and a range that ends at it (such as [MotionPhotoConfig.PLAY_TO_PHOTO]) would leave
   * nothing to play, so both start from their start.
   */
  private fun motionStartPositionMs(rangeStartUs: Long, rangeEndUs: Long): Long {
    if (
      _config.loopMode != MotionPhotoConfig.LOOP_MODE_FROM_PHOTO_FRAME ||
        photoPresentationTimestampUs == C.TIME_UNSET
    ) {
      return C.TIME_UNSET
    }
    val offsetMs = Util.usToMs(photoPresentationTimestampUs - rangeStartUs)
    val endToleranceUs =
      if (loadedClippingHasPhotoFrameEndTolerance) {
        PHOTO_FRAME_TIMESTAMP_TOLERANCE_US
      } else {
        0L
      }
    val isAtOrBeforeStart = offsetMs <= 0L
    val isAtOrBeyondEnd =
      (rangeEndUs != C.TIME_END_OF_SOURCE &&
        rangeEndUs - endToleranceUs <= photoPresentationTimestampUs) ||
        (videoDurationUs != C.TIME_UNSET && photoPresentationTimestampUs >= videoDurationUs)
    if (isAtOrBeforeStart || isAtOrBeyondEnd) {
      return C.TIME_UNSET
    }
    return offsetMs
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
   * Returns the [MotionPhotoConfig.Boundary] to resolve [boundary] as:
   * [MotionPhotoConfig.Boundary.PHOTO_FRAME] falls back to
   * [MotionPhotoConfig.Boundary.END_OF_VIDEO] (preserving [MotionPhotoConfig.Boundary.offsetMs]),
   * treating the still photo as the last frame of the video, once the tracks of the item are known
   * to carry no usable [MotionPhotoMetadata] (or when [currentPlayer] cannot report tracks), and
   * stays unresolved while the tracks are not known yet.
   */
  private fun resolveBoundary(
    currentPlayer: Player,
    boundary: MotionPhotoConfig.Boundary,
  ): MotionPhotoConfig.Boundary {
    val areTracksKnownOrUnavailable =
      hasKnownTracks || !currentPlayer.isCommandAvailable(Player.COMMAND_GET_TRACKS)
    if (
      boundary.origin != MotionPhotoConfig.Boundary.PHOTO_FRAME ||
        photoPresentationTimestampUs != C.TIME_UNSET ||
        !areTracksKnownOrUnavailable
    ) {
      return boundary
    }
    if (!hasWarnedAboutPhotoFrameFallback) {
      hasWarnedAboutPhotoFrameFallback = true
      Log.w(
        TAG,
        "Boundary.PHOTO_FRAME requested but no usable MotionPhotoMetadata is available; treating" +
          " the still photo as the last frame of the video.",
      )
    }
    return MotionPhotoConfig.Boundary.END_OF_VIDEO.plusMs(boundary.offsetMs)
  }

  private fun addOffsetUs(baseUs: Long, offsetUs: Long): Long =
    if (offsetUs > 0L && baseUs > Long.MAX_VALUE - offsetUs) {
      Long.MAX_VALUE
    } else {
      // A position before the start of the video is its start. This also keeps extreme negative
      // offsets away from the C.TIME_UNSET and C.TIME_END_OF_SOURCE sentinels.
      maxOf(0L, baseUs + offsetUs)
    }

  private fun resolvePositionUs(boundary: MotionPhotoConfig.Boundary): Long {
    val baseUs =
      when (boundary.origin) {
        MotionPhotoConfig.Boundary.START_OF_VIDEO -> 0L
        MotionPhotoConfig.Boundary.PHOTO_FRAME -> photoPresentationTimestampUs
        else -> videoDurationUs
      }
    return if (baseUs == C.TIME_UNSET) {
      C.TIME_UNSET
    } else {
      addOffsetUs(baseUs, Util.msToUs(boundary.offsetMs))
    }
  }

  private fun resolveStartPositionUs(boundary: MotionPhotoConfig.Boundary): Long {
    if (boundary.origin == MotionPhotoConfig.Boundary.START_OF_VIDEO && boundary.offsetMs <= 0L) {
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
    return resolvePositionUs(boundary)
  }

  private fun resolveEndPositionUs(boundary: MotionPhotoConfig.Boundary): Long {
    if (boundary.origin == MotionPhotoConfig.Boundary.END_OF_VIDEO && boundary.offsetMs >= 0L) {
      // An end at or beyond the end of the video (including after a Boundary.PHOTO_FRAME fallback)
      // is clamped to it.
      return C.TIME_END_OF_SOURCE
    }
    return resolvePositionUs(boundary)
  }

  @CanIgnoreReturnValue
  private fun syncMediaItem(currentPlayer: Player): Boolean {
    val startBoundary = resolveBoundary(currentPlayer, _config.rangeStart)
    val startPositionUs = resolveStartPositionUs(startBoundary)
    val endBoundary = resolveBoundary(currentPlayer, _config.rangeEnd)
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
      if (
        !isPlaybackRangeEmpty &&
          !isPhotoTarget &&
          hasRenderedFirstFrame &&
          !_config.resumeVideoFromPreviousPosition
      ) {
        isRewindPending = true
      }
      isPlaybackRangeEmpty = true
      syncPlaybackState(currentPlayer)
      return didUpdateMediaItem
    }
    val wasPlaybackRangeEmpty = isPlaybackRangeEmpty
    isPlaybackRangeEmpty = false
    // Record an idle player's pending rewind before the in-place clipping update below, so a
    // rewind there (for example on a start change) clears the flag instead of seeking again in
    // syncMotion().
    if (
      wasPlaybackRangeEmpty &&
        !isPhotoTarget &&
        currentPlayer.playbackState == Player.STATE_IDLE &&
        !_config.resumeVideoFromPreviousPosition
    ) {
      isRewindPending = true
    }
    // The photo frame itself must be decoded at the end of a range ending at Boundary.PHOTO_FRAME
    // without an offset (see PHOTO_FRAME_TIMESTAMP_TOLERANCE_US), so that end position is moved
    // later. The un-toleranced position is used for everything else, in particular for deciding
    // whether the range is empty.
    val hasPhotoFrameEndTolerance = endBoundary == MotionPhotoConfig.Boundary.PHOTO_FRAME
    val clippingEndPositionUs =
      if (hasPhotoFrameEndTolerance) {
        addOffsetUs(endPositionUs, PHOTO_FRAME_TIMESTAMP_TOLERANCE_US)
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
    val isClippingAlreadyLoaded = clippingConfiguration == loadedMediaItem?.clippingConfiguration
    if (isClippingAlreadyLoaded) {
      loadedClippingHasPhotoFrameEndTolerance = hasPhotoFrameEndTolerance
    }
    val didUpdateMediaItem =
      !isClippingAlreadyLoaded &&
        syncMediaItem(
          currentPlayer,
          clippingConfiguration,
          endPositionUs = endPositionUs,
          hasPhotoFrameEndTolerance = hasPhotoFrameEndTolerance,
          wasPlaybackRangeEmpty = wasPlaybackRangeEmpty,
        )
    if (didUpdateMediaItem && loadedMediaItem == null && !wasPlaybackRangeEmpty) {
      // The first frame of the newly set item renders underneath the still photo before motion
      // resumes.
      syncPlayWhenReady(currentPlayer)
    }
    if (!didUpdateMediaItem) {
      reconcileStartPosition(currentPlayer, allowAfterFirstFrame = wasPlaybackRangeEmpty)
    }
    if (wasPlaybackRangeEmpty) {
      if (isPhotoTarget) {
        syncStillPhoto(currentPlayer)
      } else {
        syncMotion(currentPlayer, allowPrepareOnError = false)
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
   * (unless [isPhotoTarget] and [isRewindPending] are `true`, or the current position is at or
   * beyond the new end position), while a change of the start position restarts from scratch (see
   * [motionStartPositionMs]).
   */
  private fun syncMediaItem(
    currentPlayer: Player,
    clippingConfiguration: MediaItem.ClippingConfiguration,
    endPositionUs: Long = clippingConfiguration.endPositionUs,
    hasPhotoFrameEndTolerance: Boolean = false,
    wasPlaybackRangeEmpty: Boolean = false,
  ): Boolean {
    val clippedMediaItem =
      mediaItem.buildUpon().setClippingConfiguration(clippingConfiguration).build()
    val loadedMediaItem = getMatchingMediaItemFromPlayer(currentPlayer)
    if (loadedMediaItem == null) {
      if (!currentPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
        return false
      }
      cancelDeferredRewind()
      isRewindPending = false
      currentPlayer.setMediaItem(clippedMediaItem)
      loadedClippingHasPhotoFrameEndTolerance = hasPhotoFrameEndTolerance
      hasRenderedFirstFrame = false
      isAwaitingStartSeekReady = false
      appliedPhotoFrameStartPositionMs = C.TIME_UNSET
      isNewItemAwaitingInitialStart = true
      return true
    }
    if (!currentPlayer.isCommandAvailable(Player.COMMAND_CHANGE_MEDIA_ITEMS)) {
      return false
    }
    val previousClippingConfiguration = loadedMediaItem.clippingConfiguration
    currentPlayer.replaceMediaItem(0, clippedMediaItem)
    loadedClippingHasPhotoFrameEndTolerance = hasPhotoFrameEndTolerance
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
    val didStartChange =
      previousClippingConfiguration.startPositionUs != clippingConfiguration.startPositionUs
    if (didStartChange) {
      isNewItemAwaitingInitialStart = true
    }
    if (didStartChange || (isPhotoTarget && isRewindPending) || isCurrentPositionPastEnd) {
      rewind(currentPlayer)
    } else {
      reconcileStartPosition(currentPlayer, allowAfterFirstFrame = wasPlaybackRangeEmpty)
    }
    return true
  }

  private enum class MotionTarget {
    MOTION,
    PHOTO_FORCED,
    MOTION_ENDED,
  }

  companion object {
    private const val TAG = "MotionPhotoState"

    // Tolerance added to a clipping end position resolved from Boundary.PHOTO_FRAME without an
    // offset, so that the photo frame itself is decoded at the end of the range:
    // ClippingSampleStream's end position is exclusive (buffer.timeUs >= endUs), and the XMP
    // timestamp and the sample timestamps the extractor derives from the track timescale (rounded
    // down) can disagree by up to one timescale tick. 1ms covers track timescales down to 1kHz
    // while staying well below the shortest frame interval (about 8ms at 120fps), so no
    // neighbouring frame is included.
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
 * @property loopMode The [LoopMode] controlling whether and how motion playback loops. With
 *   [LOOP_MODE_OFF], playback plays the configured range once and returns to the still photo. With
 *   [LOOP_MODE_FROM_RANGE_START], playback loops continuously starting from [rangeStart]. With
 *   [LOOP_MODE_FROM_PHOTO_FRAME], playback loops continuously and starts from scratch at the still
 *   photo frame (when playback begins or rewinds after returning to the still photo) so that the
 *   crossfade from the photo reveals the matching video frame, continues to [rangeEnd], and wraps
 *   around to [rangeStart] so the whole range is still played; if container metadata has no usable
 *   timestamp, or it does not lie strictly inside the playback range, playback falls back to
 *   starting from [rangeStart] (unlike setting [rangeStart] to [Boundary.PHOTO_FRAME], which leaves
 *   an empty range when metadata is absent). [LOOP_MODE_FROM_PHOTO_FRAME] requires a [Player] that
 *   can seek within the item ([Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM]), which `ExoPlayer` can
 *   for motion photos. For looping modes, if the [Player] supports neither
 *   [Player.COMMAND_SET_REPEAT_MODE] nor [Player.COMMAND_SEEK_TO_DEFAULT_POSITION], playback
 *   returns to the still photo instead of looping.
 * @property resumeVideoFromPreviousPosition Whether resuming motion playback should continue from
 *   the paused position rather than rewinding to where motion starts from scratch (see [loopMode])
 *   when returning to the still photo. When `false`, the rewind is deferred until the crossfade to
 *   the still photo has completed, so that it happens underneath the opaque photo.
 * @property transitionDurationMs The duration of the alpha crossfade transition between the still
 *   photo and video in milliseconds. Must be non-negative. Motion playback starts once the first
 *   frame has rendered underneath the still photo and the crossfade has completed, so that the
 *   still photo fades out over a stationary frame.
 * @property rangeStart The [Boundary] at which motion playback starts. A start position before the
 *   start of the video is clamped to it. A start position at or beyond the end of the video
 *   (including [Boundary.END_OF_VIDEO] with a non-negative offset) leaves nothing to play, so the
 *   still photo stays visible without mutating the requested motion state.
 * @property rangeEnd The [Boundary] at which motion playback ends. An end position beyond the end
 *   of the video is clamped to it by the player. An end position at or before the start position
 *   (including [Boundary.START_OF_VIDEO] with a non-positive offset) leaves nothing to play, so the
 *   still photo stays visible without mutating the requested motion state.
 */
// TODO(b/544627271): Add custom key photo frame anchor timestamp
// (`customImageAnchorTimestampMs`) and remaining presets in follow-up CLs.
@Immutable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoConfig(
  val loopMode: @LoopMode Int = LOOP_MODE_OFF,
  val resumeVideoFromPreviousPosition: Boolean = false,
  @IntRange(from = 0) val transitionDurationMs: Long = DEFAULT_TRANSITION_DURATION_MS,
  val rangeStart: Boundary = Boundary.START_OF_VIDEO,
  val rangeEnd: Boundary = Boundary.END_OF_VIDEO,
) {

  init {
    require(transitionDurationMs >= 0) { "transitionDurationMs must be non-negative." }
    require(
      loopMode == LOOP_MODE_OFF ||
        loopMode == LOOP_MODE_FROM_RANGE_START ||
        loopMode == LOOP_MODE_FROM_PHOTO_FRAME
    ) {
      "Invalid loopMode: $loopMode"
    }
  }

  internal val isLooping: Boolean
    get() = loopMode != LOOP_MODE_OFF

  /** Returns a copy of this [MotionPhotoConfig] with the given properties updated. */
  fun copy(
    loopMode: @LoopMode Int = this.loopMode,
    resumeVideoFromPreviousPosition: Boolean = this.resumeVideoFromPreviousPosition,
    @IntRange(from = 0) transitionDurationMs: Long = this.transitionDurationMs,
    rangeStart: Boundary = this.rangeStart,
    rangeEnd: Boundary = this.rangeEnd,
  ): MotionPhotoConfig =
    MotionPhotoConfig(
      loopMode = loopMode,
      resumeVideoFromPreviousPosition = resumeVideoFromPreviousPosition,
      transitionDurationMs = transitionDurationMs,
      rangeStart = rangeStart,
      rangeEnd = rangeEnd,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MotionPhotoConfig) return false
    return loopMode == other.loopMode &&
      resumeVideoFromPreviousPosition == other.resumeVideoFromPreviousPosition &&
      transitionDurationMs == other.transitionDurationMs &&
      rangeStart == other.rangeStart &&
      rangeEnd == other.rangeEnd
  }

  override fun hashCode(): Int {
    var result = loopMode
    result = 31 * result + resumeVideoFromPreviousPosition.hashCode()
    result = 31 * result + transitionDurationMs.hashCode()
    result = 31 * result + rangeStart.hashCode()
    result = 31 * result + rangeEnd.hashCode()
    return result
  }

  /**
   * Looping behavior for Motion Photo playback. One of [LOOP_MODE_OFF],
   * [LOOP_MODE_FROM_RANGE_START], or [LOOP_MODE_FROM_PHOTO_FRAME].
   *
   * @see LOOP_MODE_OFF
   * @see LOOP_MODE_FROM_RANGE_START
   * @see LOOP_MODE_FROM_PHOTO_FRAME
   */
  @Retention(AnnotationRetention.SOURCE)
  @Target(AnnotationTarget.TYPE)
  @MustBeDocumented
  @IntDef(LOOP_MODE_OFF, LOOP_MODE_FROM_RANGE_START, LOOP_MODE_FROM_PHOTO_FRAME)
  annotation class LoopMode

  /**
   * Reference point and millisecond offset defining a boundary of a Motion Photo playback range.
   *
   * Start from [START_OF_VIDEO], [PHOTO_FRAME], or [END_OF_VIDEO], and optionally shift it with
   * [plusMs] or [minusMs]. Offsets are kept as given and only clamped to the video when the range
   * is resolved, so `START_OF_VIDEO.minusMs(500L)` is a different value from [START_OF_VIDEO] that
   * plays the same.
   *
   * @property offsetMs The offset in milliseconds added to [origin].
   */
  @Immutable
  class Boundary private constructor(private val type: Int, val offsetMs: Long) {

    /** This boundary without its offset: [START_OF_VIDEO], [PHOTO_FRAME], or [END_OF_VIDEO]. */
    val origin: Boundary
      get() =
        when (type) {
          TYPE_START_OF_VIDEO -> START_OF_VIDEO
          TYPE_PHOTO_FRAME -> PHOTO_FRAME
          else -> END_OF_VIDEO
        }

    /**
     * Returns a [Boundary] shifted forward by [durationMs] milliseconds (or backward when
     * negative).
     */
    fun plusMs(durationMs: Long): Boundary =
      if (durationMs == 0L) this else Boundary(type, offsetMs + durationMs)

    /**
     * Returns a [Boundary] shifted backward by [durationMs] milliseconds (or forward when
     * negative).
     */
    fun minusMs(durationMs: Long): Boundary =
      if (durationMs == 0L) this else Boundary(type, offsetMs - durationMs)

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Boundary) return false
      return type == other.type && offsetMs == other.offsetMs
    }

    override fun hashCode(): Int {
      var result = type
      result = 31 * result + offsetMs.hashCode()
      return result
    }

    companion object {
      private const val TYPE_START_OF_VIDEO = 1
      private const val TYPE_PHOTO_FRAME = 2
      private const val TYPE_END_OF_VIDEO = 3

      /** Boundary at the start of the video track (`0ms`). */
      @JvmField val START_OF_VIDEO = Boundary(TYPE_START_OF_VIDEO, offsetMs = 0L)

      /**
       * Boundary at the presentation timestamp of the still photo frame from container metadata
       * ([MotionPhotoMetadata.photoPresentationTimestampUs]). A photo frame at or before the start
       * position leaves nothing to play when used as the end, and a photo frame at or beyond the
       * end of the video leaves nothing to play when used as the start, whereas a photo frame end
       * beyond the end of the video is clamped to it by the player.
       *
       * Once the tracks of the item are known to carry no usable metadata, or if the player cannot
       * report tracks, the still photo is treated as the last frame of the video: the boundary
       * falls back to [END_OF_VIDEO], keeping [offsetMs]. For example,
       * [MotionPhotoConfig.PLAY_TO_PHOTO] degrades to the whole video, a start of
       * `Boundary.PHOTO_FRAME.minusMs(300L)` with an end of `Boundary.PHOTO_FRAME.plusMs(300L)`
       * degrades to the last 300ms of the video, and a start at the photo frame itself leaves
       * nothing to play. The timestamp is read from the track metadata the extractor exposes via
       * `Format.metadata` on a direct in-process [Player] such as `ExoPlayer` (`MediaController` is
       * not supported); a [Player] implementation or source that does not expose `Format` metadata
       * triggers the fallback.
       */
      @JvmField val PHOTO_FRAME = Boundary(TYPE_PHOTO_FRAME, offsetMs = 0L)

      /** Boundary at the end of the video track (`duration`). */
      @JvmField val END_OF_VIDEO = Boundary(TYPE_END_OF_VIDEO, offsetMs = 0L)
    }
  }

  companion object {
    /** Play the configured range once and return to the still photo when playback ends. */
    const val LOOP_MODE_OFF = 0

    /** Loop the configured playback range continuously, starting from [rangeStart]. */
    const val LOOP_MODE_FROM_RANGE_START = 1

    /**
     * Loop the configured playback range continuously, starting from the still photo frame when its
     * timestamp is known from container metadata and lies strictly inside the playback range (and
     * wrapping around to [rangeStart] after reaching [rangeEnd]), or starting from [rangeStart]
     * otherwise.
     */
    const val LOOP_MODE_FROM_PHOTO_FRAME = 2

    /** Default crossfade transition duration in milliseconds. */
    const val DEFAULT_TRANSITION_DURATION_MS = 200L

    /** Default configuration instance that plays motion once and returns to the still photo. */
    @JvmField val DEFAULT = MotionPhotoConfig()

    /**
     * Configuration preset that plays motion from the start of the video up to the key photo frame
     * ([Boundary.PHOTO_FRAME]) and returns to the still photo. A photo frame at the start of the
     * video (`photoPresentationTimestampUs == 0`) leaves nothing to play, so the still photo stays
     * visible. If no usable `MotionPhotoMetadata` is available, [Boundary.PHOTO_FRAME] falls back
     * to [Boundary.END_OF_VIDEO] so the full video plays instead.
     */
    @JvmField
    val PLAY_TO_PHOTO =
      MotionPhotoConfig(rangeStart = Boundary.START_OF_VIDEO, rangeEnd = Boundary.PHOTO_FRAME)

    /**
     * Configuration preset that loops motion playback continuously from [Boundary.START_OF_VIDEO].
     */
    @JvmField val LOOP = MotionPhotoConfig(loopMode = LOOP_MODE_FROM_RANGE_START)
  }
}
