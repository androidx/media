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

package androidx.media3.ui.compose.material3.text

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntRect
import androidx.media3.common.Player
import androidx.media3.ui.compose.state.rememberCuesState

/**
 * A Composable for rendering subtitle cues from a Media3 [Player].
 *
 * This view observes cue changes from the [Player] using [rememberCuesState].
 *
 * This component currently only supports bitmap cues, support for text cues will be added later.
 *
 * @param player The [Player] instance to observe for cues.
 * @param modifier The [Modifier] to be applied to the layout.
 */
@Composable
internal fun Subtitles(player: Player?, modifier: Modifier = Modifier) {
  val cues = rememberCuesState(player).cues
  if (cues.isEmpty()) return

  BoxWithConstraints(modifier = modifier) {
    val viewWidth = this.constraints.maxWidth
    val viewHeight = this.constraints.maxHeight
    for (cue in cues) {
      if (cue.bitmap != null) {
        ImageCue(cue, viewport = IntRect(0, 0, viewWidth, viewHeight))
      }
      // TODO: Handle text cues here once TextCue is implemented
    }
  }
}
