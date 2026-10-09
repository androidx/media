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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.text.CurrentMediaItemBox

@Composable
internal fun CurrentItemInfo(
  meta: MediaMetadata,
  modifier: Modifier = Modifier,
  artwork: @Composable () -> Unit = {},
) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    artwork()
    Column {
      Text("Title: ${meta.title ?: "Unknown Title"}")
      Text("Artist: ${meta.artist ?: "Unknown Artist"}")
      Text("Duration: ${Util.getStringForTime(meta.durationMs ?: C.TIME_UNSET)}")
    }
  }
}

/** A [Card] showing the title and artist of the [player]'s current media item. */
@Composable
internal fun CurrentMediaItemCard(player: Player?, modifier: Modifier = Modifier) {
  CurrentMediaItemBox(player) {
    Card(modifier) {
      Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
          text = mediaMetadata.title?.toString() ?: "Unknown Title",
          style = MaterialTheme.typography.titleMedium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = mediaMetadata.artist?.toString() ?: "Unknown Artist",
          style = MaterialTheme.typography.bodyMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}
