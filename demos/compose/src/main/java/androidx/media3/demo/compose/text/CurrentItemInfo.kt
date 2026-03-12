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

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSourceBitmapLoader
import kotlinx.coroutines.guava.await

@Composable
internal fun CurrentItemInfo(meta: MediaMetadata, modifier: Modifier = Modifier) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    Artwork(meta, Modifier.padding(end = 16.dp).size(64.dp))
    Column {
      Text("Title: ${meta.title ?: "Unknown Title"}")
      Text("Artist: ${meta.artist ?: "Unknown Artist"}")
      Text("Duration: ${Util.getStringForTime(meta.durationMs ?: C.TIME_UNSET)}")
    }
  }
}

@Composable
private fun Artwork(meta: MediaMetadata, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  val bitmapLoader = remember(context) { DataSourceBitmapLoader.Builder(context).build() }
  var bitmap by remember(meta) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(meta) {
    bitmap = runCatching { bitmapLoader.loadBitmapFromMetadata(meta)?.await() }.getOrNull()
  }

  bitmap?.let { Image(bitmap = it.asImageBitmap(), contentDescription = "Artwork", modifier) }
    ?: Box(modifier)
}
