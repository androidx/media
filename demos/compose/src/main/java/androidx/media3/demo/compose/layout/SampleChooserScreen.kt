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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.demo.compose.data.PlaylistGroup
import androidx.media3.demo.compose.data.loadPlaylistHolderGroups

@Composable
fun SampleChooserScreen(
  onPlaylistClick: (List<MediaItem>) -> Unit,
  modifier: Modifier = Modifier,
  context: Context = LocalContext.current,
) {
  var playlistGroups by remember { mutableStateOf<List<PlaylistGroup>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }

  LaunchedEffect(context) {
    playlistGroups = context.loadPlaylistHolderGroups()
    isLoading = false
  }

  Box(modifier = modifier.fillMaxSize()) {
    if (isLoading) {
      Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
      ) {
        CircularProgressIndicator()
        Text("Loading samples...", modifier = Modifier.padding(top = 16.dp))
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize()) {
        playlistGroups.forEach { group ->
          item {
            Text(
              text = group.title,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(16.dp),
            )
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
  }
}
