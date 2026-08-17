/*
 * Copyright 2026 The Android Open Source Project
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

package androidx.media3.demo.compose.viewmodel

import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.media3.cast.CastPlayer
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.max
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AndroidViewModel] that manages an application-wide singleton [Player] instance and automatically
 * saves and restores its playback state across configuration changes and OS process death.
 *
 * This ViewModel is capable of:
 * - Initializing the [ExoPlayer] when entering the player screen ([initializePlayer]) and releasing
 *   hardware codecs and threads when leaving or backgrounding ([releasePlayer]).
 * - State Persistence via [SavedStateHandle]: Storing playback position ([startPosition]), playlist
 *   index ([startItemIndex]), play-when-ready state ([startAutoPlay]), volume ([startVolume]), and
 *   track selection parameters ([startTrackSelectionParameters]) into the system saved state bundle
 *   without requiring Activity or Composable boilerplate.
 * - Handling loading of the data with the [loadPlaylist] method that handles idempotent playlist
 *   comparison, optional metadata assignment, state restoration
 */
internal class PlayerLifecycleViewModel(
  application: Application,
  private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {

  private val _localPlayer = MutableStateFlow<ExoPlayer?>(null)
  val localPlayer: StateFlow<ExoPlayer?> = _localPlayer.asStateFlow()

  private val _player = MutableStateFlow<Player?>(null)
  val player: StateFlow<Player?> = _player.asStateFlow()

  private var startTrackSelectionParameters: TrackSelectionParameters
    get() =
      savedStateHandle.get<Bundle>(KEY_TRACK_SELECTION_PARAMETERS)?.let {
        TrackSelectionParameters.fromBundle(it)
      } ?: TrackSelectionParameters.DEFAULT
    set(value) {
      savedStateHandle[KEY_TRACK_SELECTION_PARAMETERS] = value.toBundle()
    }

  private var startAutoPlay: Boolean
    get() = savedStateHandle.get<Boolean>(KEY_AUTO_PLAY) ?: true
    set(value) {
      savedStateHandle[KEY_AUTO_PLAY] = value
    }

  private var startItemIndex: Int
    get() = savedStateHandle.get<Int>(KEY_ITEM_INDEX) ?: C.INDEX_UNSET
    set(value) {
      savedStateHandle[KEY_ITEM_INDEX] = value
    }

  private var startPosition: Long
    get() = savedStateHandle.get<Long>(KEY_POSITION) ?: C.TIME_UNSET
    set(value) {
      savedStateHandle[KEY_POSITION] = value
    }

  private var startVolume: Float
    get() = savedStateHandle.get<Float>(KEY_VOLUME) ?: 1f
    set(value) {
      savedStateHandle[KEY_VOLUME] = value
    }

  /**
   * Initializes the singleton [Player] instance if it has not already been created.
   *
   * @param useCast Whether to wrap the local [ExoPlayer] in a [CastPlayer].
   *
   * Applies the saved [startTrackSelectionParameters], [startAutoPlay], and [startVolume] from
   * [SavedStateHandle].
   */
  fun initializePlayer(useCast: Boolean = false) {
    if (_player.value == null) {
      val exoPlayer =
        ExoPlayer.Builder(getApplication()).build().apply {
          trackSelectionParameters = startTrackSelectionParameters
          playWhenReady = startAutoPlay
          volume = startVolume
        }
      if (useCast) {
        _localPlayer.value = exoPlayer
        _player.value = CastPlayer.Builder(getApplication()).setLocalPlayer(exoPlayer).build()
      } else {
        _localPlayer.value = null
        _player.value = exoPlayer
      }
    }
  }

  /**
   * Loads a single [mediaItem] and an optional [playlistName] into the player, then prepares it.
   */
  fun loadPlaylist(mediaItem: MediaItem, playlistName: String? = null) {
    loadPlaylist(listOf(mediaItem), playlistName)
  }

  /**
   * Loads a list of [mediaItems] and an optional [playlistName] into the player, then prepares it.
   *
   * When loading into a newly created player instance (`mediaItemCount == 0`), this method
   * atomically restores any saved [startItemIndex] and [startPosition] via
   * `setMediaItems(mediaItems, startItemIndex, startPosition)` to avoid default position resets.
   */
  fun loadPlaylist(mediaItems: List<MediaItem>, playlistName: String? = null) {
    _player.value?.apply {
      if (isPlaylistDifferent(mediaItems)) {
        val wasEmpty = mediaItemCount == 0
        if (wasEmpty && startItemIndex != C.INDEX_UNSET) {
          setMediaItems(mediaItems, startItemIndex, startPosition)
        } else {
          setMediaItems(mediaItems)
        }
        if (!playlistName.isNullOrEmpty()) {
          setPlaylistMetadata(MediaMetadata.Builder().setTitle(playlistName).build())
        }
        prepare()
      } else if (!playlistName.isNullOrEmpty() && playlistMetadata.title == null) {
        setPlaylistMetadata(MediaMetadata.Builder().setTitle(playlistName).build())
      }
    }
  }

  private fun Player.isPlaylistDifferent(mediaItems: List<MediaItem>): Boolean =
    mediaItemCount != mediaItems.size ||
      mediaItems.indices.any { getMediaItemAt(it) != mediaItems[it] }

  /**
   * Saves the current playback state into [SavedStateHandle] and releases the hardware codecs and
   * player resources.
   *
   * Called when the composable screen is paused or stopped (e.g. app backgrounded or screen
   * rotated).
   */
  fun releasePlayer() {
    _player.value?.let { player ->
      startAutoPlay = player.playWhenReady
      startItemIndex = player.currentMediaItemIndex
      startPosition = max(0L, player.contentPosition)
      startVolume = player.volume
      startTrackSelectionParameters = player.trackSelectionParameters
    }
    // Release standalone ExoPlayer OR CastPlayer (which auto-releases the underlying localPlayer)
    _player.value?.release()
    _localPlayer.value = null
    _player.value = null
  }

  override fun onCleared() {
    releasePlayer()
    clearSavedState()
  }

  /**
   * Resets all saved playback state properties in [SavedStateHandle] back to their defaults.
   *
   * Called when navigating away from the player screen so that subsequent playlists start from the
   * beginning (`0:00`) instead of resuming a previous session's timestamp.
   */
  fun clearSavedState() {
    startAutoPlay = true
    startItemIndex = C.INDEX_UNSET
    startPosition = C.TIME_UNSET
    startVolume = 1f
    startTrackSelectionParameters = TrackSelectionParameters.DEFAULT
  }

  companion object {
    private const val KEY_TRACK_SELECTION_PARAMETERS = "track_selection_parameters"
    private const val KEY_ITEM_INDEX = "item_index"
    private const val KEY_POSITION = "position"
    private const val KEY_AUTO_PLAY = "auto_play"
    private const val KEY_VOLUME = "volume"
  }
}
