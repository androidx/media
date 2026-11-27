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

package androidx.media3.ui.compose.material3

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.indicator.LinearProgressIndicator
import androidx.media3.ui.compose.state.rememberCurrentMediaItemState
import androidx.media3.ui.compose.text.CurrentMediaItemBox
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

private val defaultPlayerControls: @Composable RowScope.(Player) -> Unit = { player ->
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
    PreviousButton(player, modifier = Modifier.size(MiniControllerTokens.ControlSize))
    PlayPauseButton(player, modifier = Modifier.size(MiniControllerTokens.ControlSize))
    NextButton(player, modifier = Modifier.size(MiniControllerTokens.ControlSize))
  }
}

/**
 * A composable that provides a compact control affordance for the [Player].
 *
 * The mini controller displays the title and artist of the current media item, as well as its
 * artwork and a progress indicator. It provides playback controls which default to previous,
 * play/pause, and next buttons.
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the mini controller.
 * @param bitmapLoader The [BitmapLoader] used to load artwork from media metadata. If null, artwork
 *   metadata will not be loaded, and only the [defaultArtwork] (if provided) will be shown.
 * @param defaultArtwork The default artwork to display if the [Player] does not have any artwork or
 *   if [bitmapLoader] is null.
 * @param onClick The action to be performed when the mini controller is clicked.
 * @param playerControls A composable that provides the player controls. The default controls are
 *   previous, play/pause, and next buttons.
 */
@Composable
@UnstableApi
fun MiniController(
  player: Player,
  modifier: Modifier = Modifier,
  bitmapLoader: BitmapLoader? = null,
  defaultArtwork: Painter? = null,
  onClick: () -> Unit = {},
  playerControls: @Composable RowScope.(Player) -> Unit = defaultPlayerControls,
) {
  Card(
    onClick = onClick,
    modifier = modifier,
    elevation = CardDefaults.cardElevation(MiniControllerTokens.CardElevation),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .padding(
              horizontal = MiniControllerTokens.HorizontalPadding,
              vertical = MiniControllerTokens.VerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MiniControllerTokens.SpacerWidth),
      ) {
        Artwork(
          player,
          modifier = Modifier.size(MiniControllerTokens.ArtworkSize),
          bitmapLoader = bitmapLoader,
          defaultArtwork = defaultArtwork,
        )
        MediaDescription(player, modifier = Modifier.weight(1f))
        playerControls(player)
      }
      LinearProgressIndicator(player)
    }
  }
}

// TODO: b/509786666 - Use the Artwork composable once it becomes available.
@Composable
private fun Artwork(
  player: Player?,
  modifier: Modifier = Modifier,
  contentDescription: String? = null,
  alignment: Alignment = Alignment.Center,
  contentScale: ContentScale = ContentScale.Crop,
  bitmapLoader: BitmapLoader? = null,
  defaultArtwork: Painter? = null,
) {
  val metadata = rememberCurrentMediaItemState(player).mediaMetadata
  // Do not use remember(metadata) to avoid an *immediate* reset-to-null flicker
  var bitmap by remember { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(metadata) {
    // A parallel job to clear the old bitmap if the new one takes > 1 second
    val clearStaleArtworkJob = launch {
      delay(1000)
      bitmap = null
    }
    val newBitmap =
      runCatching { bitmapLoader?.loadBitmapFromMetadata(metadata)?.await() }.getOrNull()
    clearStaleArtworkJob.cancel()
    bitmap = newBitmap
  }

  bitmap?.let {
    Image(
      bitmap = it.asImageBitmap(),
      contentDescription = contentDescription,
      modifier = modifier,
      alignment = alignment,
      contentScale = contentScale,
    )
  }
    ?: defaultArtwork?.let {
      Image(
        painter = it,
        contentDescription = contentDescription,
        modifier = modifier,
        alignment = alignment,
        contentScale = contentScale,
      )
    }
    // Equivalent to hideArtwork() - keeps layout bounds but shows nothing
    ?: Box(modifier)
}

@Composable
private fun MediaDescription(player: Player, modifier: Modifier = Modifier) {
  CurrentMediaItemBox(player) {
    Column(modifier = modifier) {
      mediaMetadata.title?.toString()?.let { title ->
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          modifier = Modifier.basicMarquee(),
        )
      }
      mediaMetadata.artist?.toString()?.let { artist ->
        Text(
          text = artist,
          style = MaterialTheme.typography.bodySmall,
          maxLines = 1,
          modifier = Modifier.basicMarquee(),
        )
      }
    }
  }
}
