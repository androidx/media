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

package androidx.media3.demo.compose.text

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.state.PlaylistState
import androidx.media3.ui.compose.state.rememberPlaylistState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistInfoBottomSheet(
  player: Player?,
  onDismissRequest: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playlistState = rememberPlaylistState(player)
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.primary,
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      PlaylistTitle(playlistState)
      PlaylistStats(playlistState)
      PlaylistAsCards(playlistState, onDismissRequest)
    }
  }
}

@Composable
private fun PlaylistAsCards(playlistState: PlaylistState, onDismissRequest: () -> Unit) {
  LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
    items(playlistState.mediaItemCount) { index ->
      val chosen = index == playlistState.currentMediaItemIndex
      Card(
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(6.dp, MaterialTheme.colorScheme.inversePrimary).takeIf { chosen },
        colors =
          CardDefaults.cardColors(
            contentColor =
              MaterialTheme.colorScheme.inverseSurface.copy(alpha = if (chosen) 1f else 0.8f),
            containerColor =
              MaterialTheme.colorScheme.onPrimary.copy(alpha = if (chosen) 1f else 0.8f),
          ),
        modifier =
          Modifier.padding(8.dp).fillMaxWidth().clickable {
            playlistState.seekToMediaItem(index)
            onDismissRequest()
          },
      ) {
        CurrentItemInfo(playlistState.getMediaItemAt(index).mediaMetadata, Modifier.padding(16.dp))
      }
    }
  }
}

@Composable
private fun PlaylistTitle(playlistState: PlaylistState) {
  Text(
    text = playlistState.playlistMetadata.title.toString(),
    style = MaterialTheme.typography.titleMedium,
    color = MaterialTheme.colorScheme.onPrimary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
  )
}

@Composable
private fun PlaylistStats(playlistState: PlaylistState) {
  val totalDurationMs =
    (0 until playlistState.mediaItemCount).sumOf { i ->
      playlistState.getMediaItemAt(i).mediaMetadata.durationMs?.takeIf { it != C.TIME_UNSET } ?: 0L
    }
  val totalDurationText = Util.getStringForTime(totalDurationMs)
  Text(
    text = "${playlistState.mediaItemCount} item(s), $totalDurationText total duration",
    style = MaterialTheme.typography.titleSmall,
    color = MaterialTheme.colorScheme.onPrimary,
    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
  )
}
