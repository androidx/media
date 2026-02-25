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

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.indicators.ProgressIndicator
import kotlinx.coroutines.CoroutineScope

/**
 * A Material3 [Slider] that displays the current position of the player.
 *
 * @param player The [Player] to get the progress from.
 * @param modifier The [Modifier] to be applied to the slider.
 * @param onValueChange An optional callback that is invoked continuously as the user drags the
 *   slider thumb. The lambda receives a `Float` representing the new progress value (from 0.0 to
 *   1.0). This can be used to display a preview of the seek position. You should not use this
 *   callback to update the slider's value, as this is handled internally.
 * @param onValueChangeFinished An optional callback that is invoked when the user has finished
 *   their interaction (by lifting their finger or tapping). The underlying `Player.seekTo`
 *   operation is performed internally just before this callback is invoked.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param colors [SliderColors] that will be used to resolve the colors used for this slider in
 *   different states. See [SliderDefaults.colors].
 * @param interactionSource the [MutableInteractionSource] representing the stream of
 *   [Interactions][androidx.compose.foundation.interaction.Interaction] for this slider. You can
 *   create and pass in your own `remember`ed instance to observe `Interactions` and customize the
 *   appearance / behavior of this slider in different states.
 */
@UnstableApi
@Composable
fun ProgressSlider(
  player: Player?,
  modifier: Modifier = Modifier,
  onValueChange: ((Float) -> Unit)? = null,
  onValueChangeFinished: (() -> Unit)? = null,
  scope: CoroutineScope = rememberCoroutineScope(),
  colors: SliderColors = SliderDefaults.colors(),
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
  var sliderWidthPx by remember { mutableIntStateOf(0) }
  ProgressIndicator(player, totalTickCount = sliderWidthPx, scope) {
    var isDragging by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    Slider(
      value = if (isDragging) seekPosition else currentPositionProgress,
      onValueChange = {
        // TODO: b/459444117 - Add ScrubbingMode
        isDragging = true
        seekPosition = it
        onValueChange?.invoke(it)
      },
      onValueChangeFinished = {
        updateCurrentPositionProgress(seekPosition)
        isDragging = false
        onValueChangeFinished?.invoke()
      },
      // Beware the order: This measurement will happen first and it is unaware of any final size
      // changes made by the subsequent modifier of Material3 Slider. This means that the correct
      // number of pixels will be fed into ProgressStateWithTickCount class because those pixels
      // will actually represent the visible and draggable Track. The final size of ProgressSlider
      // might end up larger due to application of padding (minimumInteractiveComponentSize), but
      // that should not affect the position update interval.
      modifier = modifier.onSizeChanged { (w, _) -> sliderWidthPx = w },
      enabled = changingProgressEnabled,
      colors = colors,
      interactionSource = interactionSource,
    )
  }
}
