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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

@Composable
internal fun MainScreen(player: Player, modifier: Modifier = Modifier) {
  var currentContentScaleIndex by remember { mutableIntStateOf(0) }
  var keepContentOnReset by remember { mutableStateOf(false) } // Shutter is on by default

  Box(modifier) {
    MediaPlayer(
      player,
      contentScale = CONTENT_SCALES[currentContentScaleIndex].second,
      keepContentOnReset = keepContentOnReset,
    )
    ContentScaleButton(
      currentContentScaleIndex,
      Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
      onClick = { currentContentScaleIndex = currentContentScaleIndex.inc() % CONTENT_SCALES.size },
    )
    ShutterToggleButton(
      keepContentOnReset,
      Modifier.align(Alignment.TopEnd).padding(top = 48.dp),
      onClick = { keepContentOnReset = !keepContentOnReset },
    )
  }
}

@Composable
private fun ShutterToggleButton(
  keepContentOnReset: Boolean,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Button(onClick, modifier) { Text("Shutter ${if (keepContentOnReset) "OFF" else "ON"}") }
}

@Composable
private fun ContentScaleButton(
  currentContentScaleIndex: Int,
  modifier: Modifier,
  onClick: () -> Unit,
) {
  Button(onClick, modifier) {
    Text("ContentScale is ${CONTENT_SCALES[currentContentScaleIndex].first}")
  }
}
