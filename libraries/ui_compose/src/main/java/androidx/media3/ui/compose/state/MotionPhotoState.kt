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
import androidx.media3.common.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Creates and remembers a [MotionPhotoState] for managing Motion Photo playback and visual state.
 *
 * Note: This state holder configures [player] with a single-item playlist containing only
 * [mediaItem] (via [Player.setMediaItem], with a [MediaItem.ClippingConfiguration] reflecting the
 * playback boundaries) and prepares playback. Callers should not modify the [player]'s playlist
 * while it is bound to this state. Changing [mediaItem] creates and remembers a new
 * [MotionPhotoState], resetting [MotionPhotoState.showPhoto] to [initialShowPhoto] and
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
  val coroutineScope = rememberCoroutineScope()
  val state =
    remember(mediaItem) {
      MotionPhotoState(mediaItem, initialShowPhoto, initialConfig, coroutineScope)
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
 * playback. Playback boundary changes set [mediaItem] again with an updated
 * [MediaItem.ClippingConfiguration]. The start and end positions of [mediaItem]'s
 * [MediaItem.ClippingConfiguration] are overridden by the resolved boundaries. Callers should not
 * modify the [Player]'s playlist while it is bound to this state.
 *
 * This state holder also owns [Player.repeatMode] while bound: it is set to
 * [Player.REPEAT_MODE_ONE] or [Player.REPEAT_MODE_OFF] according to [MotionPhotoConfig.isLooping]
 * and is not restored when the [Player] is detached.
 *
 * @property mediaItem The [MediaItem] representing the motion photo, guaranteed to have a non-null
 *   [MediaItem.LocalConfiguration.uri].
 * @param initialShowPhoto Whether the still photo is initially shown instead of playing motion.
 * @param initialConfig The initial [MotionPhotoConfig] governing motion playback behavior.
 * @param coroutineScope Scope used to launch deferred rewind coroutines during photo transitions.
 */
@Stable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoState
internal constructor(
  val mediaItem: MediaItem,
  initialShowPhoto: Boolean = true,
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

  /** The [Player] driving media playback. */
  internal var player: Player?
    get() = _player
    set(value) {
      if (_player != value) {
        cancelDeferredRewind()
        _player = value
        isMotionUnavailable = false
        isPlaybackRangeEmpty = false
        hasRenderedFirstFrame = false
        videoDurationUs = C.TIME_UNSET
        appliedClippingConfiguration = null
      }
    }

  private var isMotionUnavailable by mutableStateOf(false)
  private var isPlaybackRangeEmpty by mutableStateOf(false)
  private var hasRenderedFirstFrame by mutableStateOf(false)
  private var videoDurationUs: Long = C.TIME_UNSET
  private var appliedClippingConfiguration: MediaItem.ClippingConfiguration? = null

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
      val previousConfig = _config
      _config = value
      if (_config.resumeVideoFromPreviousPosition) {
        cancelDeferredRewind()
      }
      player?.let { currentPlayer ->
        syncRepeatMode(currentPlayer)
        if (
          previousConfig.startBoundary != _config.startBoundary ||
            previousConfig.startPositionOffsetMs != _config.startPositionOffsetMs ||
            previousConfig.endBoundary != _config.endBoundary ||
            previousConfig.endPositionOffsetMs != _config.endPositionOffsetMs
        ) {
          updateClippingConfiguration(currentPlayer)
        }
        when {
          currentPlayer.playbackState == Player.STATE_ENDED && !_config.isLooping && !_showPhoto ->
            showPhoto = true
          _showPhoto &&
            previousConfig.resumeVideoFromPreviousPosition &&
            !_config.resumeVideoFromPreviousPosition -> seekToStartPosition(currentPlayer)
        }
      }
    }

  /** The current resolved [VisualState] for presenting the Motion Photo. */
  val visualState: VisualState by derivedStateOf {
    VisualState(
      isPhotoVisible =
        _showPhoto ||
          player == null ||
          isMotionUnavailable ||
          isPlaybackRangeEmpty ||
          !hasRenderedFirstFrame,
      transitionDurationMs = _config.transitionDurationMs,
    )
  }

  internal fun bindAndInitialize() {
    val currentPlayer = player ?: return
    val loadedMediaItem =
      if (
        currentPlayer.isCommandAvailable(Player.COMMAND_GET_TIMELINE) &&
          currentPlayer.mediaItemCount == 1 &&
          currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
      ) {
        currentPlayer.currentMediaItem
      } else {
        null
      }
    // A player handed over across recompositions or configuration changes may already play
    // mediaItem with the clipping a previous state applied, so the clipping is ignored in the
    // comparison and adopted from the player instead of setting the item again.
    val isMediaItemAlreadyLoaded =
      loadedMediaItem != null &&
        loadedMediaItem
          .buildUpon()
          .setClippingConfiguration(mediaItem.clippingConfiguration)
          .build() == mediaItem
    if (isMediaItemAlreadyLoaded) {
      appliedClippingConfiguration = loadedMediaItem.clippingConfiguration
      updateVideoDuration(currentPlayer)
    }
    updateClippingConfiguration(currentPlayer)
    // Without COMMAND_SET_MEDIA_ITEM no new item was set, so an existing error is not stale.
    syncPlaybackState(
      currentPlayer,
      allowPrepareOnError =
        (!isMediaItemAlreadyLoaded ||
          loadedMediaItem.clippingConfiguration != appliedClippingConfiguration) &&
          currentPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM),
      isInitialBind = true,
    )
    updateClippingConfigurationIfVideoDurationChanged(currentPlayer)
    updateMotionAvailability(currentPlayer)
  }

  internal suspend fun observe() {
    val currentPlayer = player ?: return
    try {
      observePlayerEvents(currentPlayer)
    } finally {
      // The effect running observe() shares this state's lifetime. A rewind that is still pending
      // when it ends must not reach a player that a newer state may have re-bound in the meantime.
      cancelDeferredRewind()
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
        )
      ) {
        updateMotionAvailability(this)
      }
      if (events.contains(Player.EVENT_AVAILABLE_COMMANDS_CHANGED)) {
        syncRepeatMode(this)
      }
      if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
        updateClippingConfigurationIfVideoDurationChanged(this)
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

  private fun syncPlaybackState(
    currentPlayer: Player,
    allowPrepareOnError: Boolean,
    isInitialBind: Boolean = false,
  ) {
    syncRepeatMode(currentPlayer)
    val wasIdle = currentPlayer.playbackState == Player.STATE_IDLE
    if (
      wasIdle &&
        (currentPlayer.playerError == null || allowPrepareOnError) &&
        currentPlayer.isCommandAvailable(Player.COMMAND_PREPARE)
    ) {
      currentPlayer.prepare()
    }

    val hadPendingDeferredRewind = deferredRewindJob?.isActive == true
    cancelDeferredRewind()
    when {
      _config.resumeVideoFromPreviousPosition -> {
        if (!_showPhoto && currentPlayer.playbackState == Player.STATE_ENDED) {
          seekToStartPosition(currentPlayer)
        }
      }
      shouldDeferRewind() -> {
        // Captured on purpose: the crossfade that starts now keeps this duration even if the config
        // changes while it is running, so the rewind must wait for the same amount of time.
        val transitionDurationMs = _config.transitionDurationMs
        deferredRewindJob = coroutineScope.launch {
          delay(transitionDurationMs)
          seekToStartPosition(currentPlayer)
        }
      }
      // When resuming motion (_showPhoto == false), the player was already rewound when returning
      // to the photo unless a deferred rewind was still in flight, the player is being initialized
      // or re-prepared from idle, or playback had ended.
      _showPhoto ||
        isInitialBind ||
        hadPendingDeferredRewind ||
        wasIdle ||
        currentPlayer.playbackState == Player.STATE_ENDED -> {
        seekToStartPosition(currentPlayer)
      }
    }
    if (currentPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
      currentPlayer.playWhenReady = !_showPhoto && !isPlaybackRangeEmpty
    }
  }

  private fun shouldDeferRewind(): Boolean =
    _showPhoto &&
      hasRenderedFirstFrame &&
      !isMotionUnavailable &&
      !isPlaybackRangeEmpty &&
      _config.transitionDurationMs > 0L

  private fun cancelDeferredRewind() {
    deferredRewindJob?.cancel()
    deferredRewindJob = null
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
   * Updates [videoDurationUs] from [currentPlayer] if the duration of the whole video is known.
   *
   * Once a clipped item is set, [Player.getDuration] only reports the clipped window length, so the
   * period's original duration is used whenever the player exposes it, and [Player.getDuration] is
   * only trusted while the player still holds the unclipped [mediaItem].
   */
  private fun updateVideoDuration(currentPlayer: Player) {
    if (appliedClippingConfiguration == null) {
      return
    }
    var durationMs = C.TIME_UNSET
    if (currentPlayer.isCommandAvailable(Player.COMMAND_GET_TIMELINE)) {
      val timeline = currentPlayer.currentTimeline
      if (!timeline.isEmpty) {
        val window = timeline.getWindow(currentPlayer.currentMediaItemIndex, Timeline.Window())
        if (window.firstPeriodIndex == window.lastPeriodIndex) {
          durationMs =
            timeline.getPeriod(window.firstPeriodIndex, Timeline.Period()).originalDurationMs
        }
      }
    }
    if (
      durationMs == C.TIME_UNSET &&
        appliedClippingConfiguration == unclippedClippingConfiguration() &&
        currentPlayer.isCommandAvailable(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
    ) {
      durationMs = currentPlayer.duration
    }
    if (durationMs != C.TIME_UNSET) {
      videoDurationUs = Util.msToUs(durationMs)
    }
  }

  private fun unclippedClippingConfiguration(): MediaItem.ClippingConfiguration =
    mediaItem.clippingConfiguration
      .buildUpon()
      .setStartPositionUs(0L)
      .setEndPositionUs(C.TIME_END_OF_SOURCE)
      .build()

  private fun updateClippingConfigurationIfVideoDurationChanged(currentPlayer: Player) {
    val previousVideoDurationUs = videoDurationUs
    updateVideoDuration(currentPlayer)
    if (videoDurationUs != previousVideoDurationUs) {
      updateClippingConfiguration(currentPlayer)
    }
  }

  private fun resolveStartPositionUs(): Long {
    if (
      _config.startBoundary == MotionPhotoConfig.BOUNDARY_START_OF_VIDEO &&
        _config.startPositionOffsetMs == 0L
    ) {
      return 0L
    }
    if (videoDurationUs == C.TIME_UNSET) {
      return C.TIME_UNSET
    }
    val baseUs =
      when (_config.startBoundary) {
        MotionPhotoConfig.BOUNDARY_END_OF_VIDEO -> videoDurationUs
        else -> 0L
      }
    return baseUs + Util.msToUs(_config.startPositionOffsetMs)
  }

  private fun resolveEndPositionUs(): Long {
    if (
      _config.endBoundary == MotionPhotoConfig.BOUNDARY_END_OF_VIDEO &&
        _config.endPositionOffsetMs == 0L
    ) {
      return C.TIME_END_OF_SOURCE
    }
    val baseUs =
      when (_config.endBoundary) {
        MotionPhotoConfig.BOUNDARY_START_OF_VIDEO -> 0L
        else -> videoDurationUs
      }
    if (baseUs == C.TIME_UNSET) {
      return C.TIME_UNSET
    }
    return baseUs + Util.msToUs(_config.endPositionOffsetMs)
  }

  private fun updateClippingConfiguration(currentPlayer: Player) {
    if (!currentPlayer.isCommandAvailable(Player.COMMAND_SET_MEDIA_ITEM)) {
      return
    }
    val isInitialSet = appliedClippingConfiguration == null
    val resolvedStartPositionUs = resolveStartPositionUs()
    // A start before the start of the video is clamped to it, as the player would do.
    val startPositionUs =
      if (resolvedStartPositionUs == C.TIME_UNSET) C.TIME_UNSET
      else maxOf(0L, resolvedStartPositionUs)
    val endPositionUs = resolveEndPositionUs()
    val isStartOutOfBounds =
      startPositionUs == C.TIME_UNSET ||
        (videoDurationUs != C.TIME_UNSET && startPositionUs >= videoDurationUs)
    val isEndOutOfBounds =
      endPositionUs == C.TIME_UNSET ||
        (endPositionUs != C.TIME_END_OF_SOURCE && endPositionUs <= startPositionUs)
    if (isStartOutOfBounds || isEndOutOfBounds) {
      // The unclipped item is prepared to learn the video duration. This is also needed when a
      // position cannot be resolved from an item that is loaded clipped, for example after binding
      // to a player that does not report the original duration of a clipped period.
      val unclippedConfiguration = unclippedClippingConfiguration()
      if (
        isInitialSet ||
          ((startPositionUs == C.TIME_UNSET || endPositionUs == C.TIME_UNSET) &&
            appliedClippingConfiguration != unclippedConfiguration)
      ) {
        setMediaItemWithClippingConfiguration(currentPlayer, unclippedConfiguration)
      }
      isPlaybackRangeEmpty = true
      if (currentPlayer.isCommandAvailable(Player.COMMAND_PLAY_PAUSE)) {
        currentPlayer.playWhenReady = false
      }
      return
    }
    val wasPlaybackRangeEmpty = isPlaybackRangeEmpty
    isPlaybackRangeEmpty = false
    // The state owns the clipping of mediaItem: a caller-supplied end position could otherwise end
    // up before a start position resolved relative to the end of the video.
    val clippingConfiguration =
      mediaItem.clippingConfiguration
        .buildUpon()
        .setStartPositionUs(startPositionUs)
        .setEndPositionUs(endPositionUs)
        .build()
    if (clippingConfiguration != appliedClippingConfiguration) {
      setMediaItemWithClippingConfiguration(currentPlayer, clippingConfiguration)
    }
    if (wasPlaybackRangeEmpty) {
      syncPlaybackState(currentPlayer, allowPrepareOnError = false)
    }
  }

  /**
   * Sets [mediaItem] with the given [clippingConfiguration] on [currentPlayer].
   *
   * Clipping changes always go through [Player.setMediaItem]: with a stock
   * `DefaultMediaSourceFactory`, an item that was prepared without clipping has no
   * `ClippingMediaSource` around it, and [Player.replaceMediaItem] would then update the item in
   * place without applying the new clipping (see
   * `DefaultMediaSourceFactory.setAlwaysWrapInClippingMediaSource`).
   */
  private fun setMediaItemWithClippingConfiguration(
    currentPlayer: Player,
    clippingConfiguration: MediaItem.ClippingConfiguration,
  ) {
    // Assigned before setMediaItem so that events delivered for the new item already see the
    // applied configuration.
    appliedClippingConfiguration = clippingConfiguration
    currentPlayer.setMediaItem(
      mediaItem.buildUpon().setClippingConfiguration(clippingConfiguration).build()
    )
    hasRenderedFirstFrame = false
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
 * Playback boundaries are applied through [MediaItem.ClippingConfiguration] and therefore require a
 * [Player] that honors it (`ExoPlayer` does; players that ignore clipping play the whole video).
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
 *   photo and video in milliseconds. Must be non-negative.
 * @property startBoundary The [PlaybackBoundary] anchor from which motion playback starts.
 * @property startPositionOffsetMs The offset in milliseconds added to [startBoundary] to compute
 *   the start position. Must be non-negative when [startBoundary] is [BOUNDARY_START_OF_VIDEO] and
 *   non-positive when [startBoundary] is [BOUNDARY_END_OF_VIDEO]. A start position before the start
 *   of the video is clamped to it. A start position at or beyond the end of the video (including
 *   [BOUNDARY_END_OF_VIDEO] with an offset of `0`) leaves nothing to play, so the still photo stays
 *   visible and [MotionPhotoState.showPhoto] is left unchanged.
 * @property endBoundary The [PlaybackBoundary] anchor at which motion playback ends.
 * @property endPositionOffsetMs The offset in milliseconds added to [endBoundary] to compute the
 *   end position. Must be non-negative when [endBoundary] is [BOUNDARY_START_OF_VIDEO] and
 *   non-positive when [endBoundary] is [BOUNDARY_END_OF_VIDEO]. An end position beyond the end of
 *   the video is clamped to it by the player. An end position at or before the start position
 *   (including [BOUNDARY_START_OF_VIDEO] with an offset of `0`) leaves nothing to play, so the
 *   still photo stays visible and [MotionPhotoState.showPhoto] is left unchanged.
 */
// TODO(b/544627271): Add photo frame metadata boundary (`BOUNDARY_PHOTO_FRAME`), custom key photo
// frame anchor timestamp (`customImageAnchorTimestampMs`), and presets in follow-up CLs.
@Immutable
@RestrictTo(LIBRARY_GROUP)
class MotionPhotoConfig(
  val isLooping: Boolean = false,
  val resumeVideoFromPreviousPosition: Boolean = false,
  @IntRange(from = 0) val transitionDurationMs: Long = DEFAULT_TRANSITION_DURATION_MS,
  val startBoundary: @PlaybackBoundary Int = BOUNDARY_START_OF_VIDEO,
  val startPositionOffsetMs: Long = 0L,
  val endBoundary: @PlaybackBoundary Int = BOUNDARY_END_OF_VIDEO,
  val endPositionOffsetMs: Long = 0L,
) {

  init {
    require(transitionDurationMs >= 0) { "transitionDurationMs must be non-negative." }
    require(startBoundary == BOUNDARY_START_OF_VIDEO || startBoundary == BOUNDARY_END_OF_VIDEO) {
      "Invalid startBoundary: $startBoundary"
    }
    require(startBoundary != BOUNDARY_START_OF_VIDEO || startPositionOffsetMs >= 0L) {
      "startPositionOffsetMs must be non-negative when startBoundary is BOUNDARY_START_OF_VIDEO."
    }
    require(startBoundary != BOUNDARY_END_OF_VIDEO || startPositionOffsetMs <= 0L) {
      "startPositionOffsetMs must be non-positive when startBoundary is BOUNDARY_END_OF_VIDEO."
    }
    require(endBoundary == BOUNDARY_START_OF_VIDEO || endBoundary == BOUNDARY_END_OF_VIDEO) {
      "Invalid endBoundary: $endBoundary"
    }
    require(endBoundary != BOUNDARY_START_OF_VIDEO || endPositionOffsetMs >= 0L) {
      "endPositionOffsetMs must be non-negative when endBoundary is BOUNDARY_START_OF_VIDEO."
    }
    require(endBoundary != BOUNDARY_END_OF_VIDEO || endPositionOffsetMs <= 0L) {
      "endPositionOffsetMs must be non-positive when endBoundary is BOUNDARY_END_OF_VIDEO."
    }
  }

  /** Returns a copy of this [MotionPhotoConfig] with the given properties updated. */
  fun copy(
    isLooping: Boolean = this.isLooping,
    resumeVideoFromPreviousPosition: Boolean = this.resumeVideoFromPreviousPosition,
    @IntRange(from = 0) transitionDurationMs: Long = this.transitionDurationMs,
    startBoundary: @PlaybackBoundary Int = this.startBoundary,
    startPositionOffsetMs: Long = this.startPositionOffsetMs,
    endBoundary: @PlaybackBoundary Int = this.endBoundary,
    endPositionOffsetMs: Long = this.endPositionOffsetMs,
  ): MotionPhotoConfig =
    MotionPhotoConfig(
      isLooping = isLooping,
      resumeVideoFromPreviousPosition = resumeVideoFromPreviousPosition,
      transitionDurationMs = transitionDurationMs,
      startBoundary = startBoundary,
      startPositionOffsetMs = startPositionOffsetMs,
      endBoundary = endBoundary,
      endPositionOffsetMs = endPositionOffsetMs,
    )

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is MotionPhotoConfig) return false
    return isLooping == other.isLooping &&
      resumeVideoFromPreviousPosition == other.resumeVideoFromPreviousPosition &&
      transitionDurationMs == other.transitionDurationMs &&
      startBoundary == other.startBoundary &&
      startPositionOffsetMs == other.startPositionOffsetMs &&
      endBoundary == other.endBoundary &&
      endPositionOffsetMs == other.endPositionOffsetMs
  }

  override fun hashCode(): Int {
    var result = isLooping.hashCode()
    result = 31 * result + resumeVideoFromPreviousPosition.hashCode()
    result = 31 * result + transitionDurationMs.hashCode()
    result = 31 * result + startBoundary
    result = 31 * result + startPositionOffsetMs.hashCode()
    result = 31 * result + endBoundary
    result = 31 * result + endPositionOffsetMs.hashCode()
    return result
  }

  /**
   * Boundary anchor points for Motion Photo playback. One of [BOUNDARY_START_OF_VIDEO] or
   * [BOUNDARY_END_OF_VIDEO].
   *
   * @see BOUNDARY_START_OF_VIDEO
   * @see BOUNDARY_END_OF_VIDEO
   */
  @Retention(AnnotationRetention.SOURCE)
  @Target(AnnotationTarget.TYPE)
  @MustBeDocumented
  @IntDef(BOUNDARY_START_OF_VIDEO, BOUNDARY_END_OF_VIDEO)
  annotation class PlaybackBoundary

  companion object {
    /** Boundary at the start of the video track (`0ms`). */
    const val BOUNDARY_START_OF_VIDEO = 1

    // 2 is reserved for BOUNDARY_PHOTO_FRAME (added in a follow-up CL).
    /** Boundary at the end of the video track (`duration`). */
    const val BOUNDARY_END_OF_VIDEO = 3

    /** Default crossfade transition duration in milliseconds. */
    const val DEFAULT_TRANSITION_DURATION_MS = 200L

    /** Default configuration instance that plays motion once and returns to the still photo. */
    @JvmField val DEFAULT = MotionPhotoConfig()

    /** Configuration preset that loops motion playback continuously. */
    @JvmField val LOOP = MotionPhotoConfig(isLooping = true)
  }
}
