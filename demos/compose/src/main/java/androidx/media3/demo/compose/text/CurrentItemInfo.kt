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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.Util

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
