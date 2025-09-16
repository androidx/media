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

package androidx.media3.demo.compose.layout

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.demo.compose.data.loadPlaylistHolderGroups

@Composable
fun SampleChooserScreen(
  onPlaylistClick: (List<MediaItem>) -> Unit,
  modifier: Modifier = Modifier,
  context: Context = LocalContext.current,
) {
  val playlistGroups = remember { context.loadPlaylistHolderGroups() }
  LazyColumn(modifier = modifier) {
    playlistGroups.forEach { group ->
      item {
        Text(text = group.title, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))
      }
      items(group.playlists) { playlist ->
        ListItem(
          headlineContent = { Text(playlist.name) },
          modifier = Modifier.clickable { onPlaylistClick(playlist.mediaItems) },
        )
      }
    }
  }
}
