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

import android.view.accessibility.CaptioningManager
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntRect
import androidx.media3.common.Player
import androidx.media3.common.text.CueUtil
import androidx.media3.common.text.SubtitleStyle
import androidx.media3.ui.compose.state.rememberCuesState

/**
 * A Composable for rendering subtitle cues from a Media3 [Player].
 *
 * This view observes cue changes from the [Player] using [rememberCuesState].
 *
 * Text cues are styled using the user's system caption preferences (see [CaptioningManager]).
 *
 * This component currently only supports horizontal text and bitmap cues, support for vertical text
 * cues will be added later.
 *
 * @param player The [Player] instance to observe for cues.
 * @param modifier The [Modifier] to be applied to the layout.
 */
@Composable
internal fun Subtitles(player: Player?, modifier: Modifier = Modifier) {
  val cues = rememberCuesState(player).cues
  if (cues.isEmpty()) return

  val context = LocalContext.current
  val captioningManager =
    remember(context) { context.getSystemService(CaptioningManager::class.java) }
  val subtitleStyle =
    remember(captioningManager) {
      captioningManager?.let { SubtitleStyle.fromCaptionStyle(it.userStyle) }
        ?: SubtitleStyle.DEFAULT
    }
  val defaultTextSizeFraction =
    remember(captioningManager) {
      CueUtil.DEFAULT_TEXT_SIZE_FRACTION * (captioningManager?.fontScale ?: 1f)
    }

  BoxWithConstraints(modifier = modifier) {
    val viewport = IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
    for (cue in cues) {
      key(cue) {
        if (cue.bitmap != null) {
          ImageCue(cue, viewport)
        } else {
          TextCue(
            cue,
            viewport,
            subtitleStyle = subtitleStyle,
            defaultTextSizeFraction = defaultTextSizeFraction,
          )
        }
      }
    }
  }
}
