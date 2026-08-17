/*
 * Copyright 2025 The Android Open Source Project
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

import android.os.Bundle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaLibraryInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [ViewModel] responsible for holding and sharing user-selected sample data across Compose
 * Navigation destinations.
 *
 * This ViewModel is capable of:
 * - Passing the selected [mediaItems] and [playlistName] from the sample selection screen to player
 *   destinations.
 * - Saving and restoring playlist metadata and [MediaItem] lists via [SavedStateHandle], allowing
 *   playback screens to recover after OS process death (`adb shell am kill`).
 */
internal class NavigationViewModel(private val savedStateHandle: SavedStateHandle) : ViewModel() {
  private val _mediaItems =
    MutableStateFlow(
      savedStateHandle.get<ArrayList<Bundle>>(KEY_MEDIA_ITEMS)?.map {
        MediaItem.fromBundle(it, MediaLibraryInfo.INTERFACE_VERSION)
      } ?: emptyList()
    )
  /** The currently selected list of [MediaItem]s to play. */
  val mediaItems = _mediaItems.asStateFlow()

  private val _playlistName =
    MutableStateFlow(savedStateHandle.get<String>(KEY_PLAYLIST_NAME) ?: "")
  /** The display name of the currently selected playlist. */
  val playlistName = _playlistName.asStateFlow()

  /**
   * Updates the selected [mediaItems] and serializes them into [SavedStateHandle] using
   * `toBundleIncludeLocalConfiguration()` to preserve local URIs and subtitle configurations across
   * process death.
   */
  fun selectMediaItems(mediaItems: List<MediaItem>) {
    savedStateHandle[KEY_MEDIA_ITEMS] =
      ArrayList(
        mediaItems.map { it.toBundleIncludeLocalConfiguration(MediaLibraryInfo.INTERFACE_VERSION) }
      )
    _mediaItems.value = mediaItems
  }

  /** Updates the selected [playlistName] and stores it into [SavedStateHandle]. */
  fun selectPlaylistName(playlistName: String) {
    savedStateHandle[KEY_PLAYLIST_NAME] = playlistName
    _playlistName.value = playlistName
  }

  companion object {
    private const val KEY_MEDIA_ITEMS = "media_items"
    private const val KEY_PLAYLIST_NAME = "playlist_name"
  }
}
