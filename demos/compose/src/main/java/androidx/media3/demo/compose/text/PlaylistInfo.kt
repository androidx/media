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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.Util
import androidx.media3.demo.compose.R
import androidx.media3.demo.compose.layout.rememberTintedPainter
import androidx.media3.ui.compose.Artwork
import androidx.media3.ui.compose.state.PlaylistState
import androidx.media3.ui.compose.state.rememberPlaylistState

/**
 * A non-modal [BottomSheet] listing the items of the [player]'s playlist, which is shown and hidden
 * through [sheetState]. Clicking an item seeks to it and keeps the sheet open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistInfoBottomSheet(
  player: Player?,
  sheetState: SheetState,
  modifier: Modifier = Modifier,
  bitmapLoader: BitmapLoader? = null,
) {
  val playlistState = rememberPlaylistState(player)
  BottomSheet(modifier = modifier, state = sheetState) {
    PlaylistHeader(playlistState)
    LazyColumn {
      items(playlistState.mediaItemCount) { index ->
        PlaylistItem(
          metadata = playlistState.getMediaItemAt(index).mediaMetadata,
          isCurrent = index == playlistState.currentMediaItemIndex,
          onClick = { playlistState.seekToMediaItem(index) },
          bitmapLoader = bitmapLoader,
        )
      }
    }
  }
}

/**
 * Shows the playlist title and, if there is a current item, its position and the total duration.
 */
@Composable
private fun PlaylistHeader(playlistState: PlaylistState) {
  val totalDurationMs =
    remember(playlistState.timeline) {
      (0 until playlistState.mediaItemCount).sumOf { i ->
        playlistState.getMediaItemAt(i).mediaMetadata.durationMs?.takeIf { it != C.TIME_UNSET }
          ?: 0L
      }
    }
  Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
    Text(
      text = playlistState.playlistMetadata.title?.toString() ?: "Playlist",
      style = MaterialTheme.typography.titleMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    // The index is unset if the player doesn't allow getting the timeline, and out of range if the
    // playlist is empty.
    val currentIndex = playlistState.currentMediaItemIndex
    if (currentIndex in 0 until playlistState.mediaItemCount) {
      Text(
        text =
          "${currentIndex + 1} / ${playlistState.mediaItemCount} • " +
            Util.getStringForTime(totalDurationMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * A full-width row with the artwork, title, artist and duration of a playlist item. The current
 * item is highlighted.
 */
@Composable
private fun PlaylistItem(
  metadata: MediaMetadata,
  isCurrent: Boolean,
  onClick: () -> Unit,
  bitmapLoader: BitmapLoader?,
) {
  val artist = metadata.artist?.toString() ?: "Unknown Artist"
  val durationMs = metadata.durationMs?.takeIf { it != C.TIME_UNSET }
  Row(
    Modifier.fillMaxWidth()
      .background(
        if (isCurrent) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent
      )
      .selectable(selected = isCurrent, onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PlaylistItemArtwork(metadata, isCurrent, bitmapLoader)
    Column(Modifier.weight(1f)) {
      Text(
        text = metadata.title?.toString() ?: "Unknown Title",
        style = MaterialTheme.typography.titleSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = if (durationMs == null) artist else "$artist • ${Util.getStringForTime(durationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/**
 * Shows the artwork of a playlist item, a placeholder icon if it has none, or an error icon if it
 * can't be loaded. The artwork of the current item is dimmed and overlaid with a
 * [NowPlayingIndicator], which replaces the icons.
 */
@Composable
private fun PlaylistItemArtwork(
  metadata: MediaMetadata,
  isCurrent: Boolean,
  bitmapLoader: BitmapLoader?,
) {
  val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
  val errorPainter =
    rememberTintedPainter(painterResource(R.drawable.media3_icon_broken_image), iconTint)
  val fallbackPainter =
    rememberTintedPainter(painterResource(R.drawable.media3_icon_default_album_image), iconTint)
  Box(
    Modifier.size(56.dp)
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant),
    contentAlignment = Alignment.Center,
  ) {
    Artwork(
      metadata = metadata,
      modifier = Modifier.matchParentSize(),
      contentDescription = null,
      contentScale = ContentScale.Crop,
      bitmapLoader = bitmapLoader,
      error = if (isCurrent) null else errorPainter,
      fallback = if (isCurrent) null else fallbackPainter,
    )
    if (isCurrent) {
      Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)))
      NowPlayingIndicator()
    }
  }
}

/** Shows equalizer bars to mark the item that is currently playing. */
@Composable
private fun NowPlayingIndicator() {
  Row(
    Modifier.size(16.dp),
    horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.Bottom,
  ) {
    for (heightFraction in listOf(0.6f, 1f, 0.4f)) {
      Box(Modifier.width(3.dp).fillMaxHeight(heightFraction).background(Color.White))
    }
  }
}
