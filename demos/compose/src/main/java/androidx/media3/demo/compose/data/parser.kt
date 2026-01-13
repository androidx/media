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

package androidx.media3.demo.compose.data

import android.content.Context
import android.net.Uri
import android.util.JsonReader
import androidx.core.net.toUri
import androidx.core.util.Preconditions.checkState
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Log
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun Context.loadPlaylistHolderGroups(): List<PlaylistGroup> =
  withContext(Dispatchers.IO) {
    try {
      assets.open("media.exolist.json").use { inputStream ->
        val jsonReader = JsonReader(InputStreamReader(inputStream, StandardCharsets.UTF_8))
        val playlistGroups = mutableListOf<PlaylistGroup>()
        readPlaylistGroups(jsonReader, playlistGroups)
        playlistGroups
      }
    } catch (e: IOException) {
      Log.e("parser", "Error loading playlist groups", e)
      emptyList()
    }
  }

private fun readPlaylistGroups(reader: JsonReader, groups: MutableList<PlaylistGroup>) {
  reader.beginArray()
  while (reader.hasNext()) {
    readPlaylistGroup(reader, groups)
  }
  reader.endArray()
}

private fun readPlaylistGroup(reader: JsonReader, groups: MutableList<PlaylistGroup>) {
  var groupName = ""
  val playlistHolders = mutableListOf<PlaylistHolder>()

  reader.beginObject()
  while (reader.hasNext()) {
    when (val name = reader.nextName()) {
      "name" -> groupName = reader.nextString()
      "samples" -> {
        reader.beginArray()
        while (reader.hasNext()) {
          playlistHolders.add(readEntry(reader, insidePlaylist = false))
        }
        reader.endArray()
      }
      else -> throw IOException("Unsupported name: $name", /* cause= */ null)
    }
  }
  reader.endObject()

  val group: PlaylistGroup = getGroup(groupName, groups)
  group.playlists.addAll(playlistHolders)
}

private fun readEntry(reader: JsonReader, insidePlaylist: Boolean): PlaylistHolder {
  lateinit var uri: Uri
  var title = ""
  var children: MutableList<PlaylistHolder>? = null

  val mediaItem = MediaItem.Builder()
  reader.beginObject()
  while (reader.hasNext()) {
    when (val name = reader.nextName()) {
      "name" -> title = reader.nextString()
      "uri" -> uri = reader.nextString().toUri()
      "playlist" -> {
        checkState(!insidePlaylist, "Invalid nesting of playlists")
        children = mutableListOf()
        reader.beginArray()
        while (reader.hasNext()) {
          children.add(readEntry(reader, insidePlaylist = true))
        }
        reader.endArray()
      }
      else -> throw IOException("Unsupported attribute name: $name", /* cause= */ null)
    }
  }
  reader.endObject()

  if (children != null) {
    val mediaItems: MutableList<MediaItem> = mutableListOf()
    for (playlistHolder in children) {
      mediaItems.addAll(playlistHolder.mediaItems)
    }
    return PlaylistHolder(title, mediaItems.toList())
  } else {
    return PlaylistHolder(
      title,
      mutableListOf(
        mediaItem
          .setUri(uri)
          .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
          .build()
      ),
    )
  }
}

private fun getGroup(groupName: String, groups: MutableList<PlaylistGroup>): PlaylistGroup {
  return groups.firstOrNull { it.title == groupName }
    ?: run {
      val group = PlaylistGroup(groupName, mutableListOf())
      groups.add(group)
      group
    }
}

data class PlaylistGroup(val title: String, var playlists: MutableList<PlaylistHolder>)

data class PlaylistHolder(val name: String, val mediaItems: List<MediaItem>)
