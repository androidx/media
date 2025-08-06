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

package androidx.media3.ui.compose.state

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.media3.common.Player
import androidx.media3.common.listen
import androidx.media3.common.util.UnstableApi

/**
 * Remembers the value of a [MetadataState] created based on the passed [Player] and launches a
 * coroutine to listen to the [Player's][Player] changes. If the [Player] instance changes between
 * compositions, this produces and remembers a new [MetadataState].
 */
@UnstableApi
@Composable
fun rememberMetadataState(player: Player): MetadataState {
  val metadataState = remember(player) { MetadataState(player) }
  LaunchedEffect(player) { metadataState.observe() }
  return metadataState
}

/**
 * State that holds information to correctly deal with UI components related to the current
 * [MediaItem][androidx.media3.common.MediaItem] metadata.
 *
 * @property[uri] The URI of the current media item, if available.
 */
@UnstableApi
class MetadataState(private val player: Player) {
  var uri by mutableStateOf(player.getMediaItemUri())
    private set

  suspend fun observe(): Nothing {
    player.listen { events ->
      if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
        uri = getMediaItemUri()
      }
    }
  }

  private fun Player.getMediaItemUri(): Uri? {
    return currentMediaItem?.localConfiguration?.uri
  }
}
