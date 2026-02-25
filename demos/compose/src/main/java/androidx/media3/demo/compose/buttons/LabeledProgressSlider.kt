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

package androidx.media3.demo.compose.buttons

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.ui.compose.indicators.ProgressIndicator
import androidx.media3.ui.compose.state.ProgressStateWithTickCount
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
 * @param thumb A custom thumb to be displayed on the slider, it is placed on top of the track. The
 *   lambda receives a [SliderState] which is used to obtain the current active track. If `null`, a
 *   standard Material3 `SliderDefaults.Thumb` will be shown.
 */
// TODO: b/304811984 - publish this Slider when the overload with thumb/track is stabilized
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledProgressSlider(
  player: Player?,
  modifier: Modifier = Modifier,
  onValueChange: ((Float) -> Unit)? = null,
  onValueChangeFinished: (() -> Unit)? = null,
  scope: CoroutineScope = rememberCoroutineScope(),
  colors: SliderColors = SliderDefaults.colors(),
  interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
  thumb: (@Composable (ProgressStateWithTickCount, SliderState) -> Unit)? =
    { progressState, sliderState ->
      SeekTimeThumb(progressState, sliderState, interactionSource)
    },
) {
  var sliderWidthPx by remember { mutableIntStateOf(0) }
  ProgressIndicator(player, totalTickCount = sliderWidthPx, scope) {
    var isDragging by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    Slider(
      value = if (isDragging) seekPosition else currentPositionProgress,
      onValueChange = {
        isDragging = true
        seekPosition = it
        onValueChange?.invoke(it)
      },
      onValueChangeFinished = {
        updateCurrentPositionProgress(seekPosition)
        isDragging = false
        onValueChangeFinished?.invoke()
      },
      thumb = { sliderState ->
        if (thumb == null) {
          SliderDefaults.Thumb(
            interactionSource = interactionSource,
            colors = colors,
            enabled = changingProgressEnabled,
          )
        } else {

          thumb(this, sliderState)
        }
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

@OptIn(ExperimentalMaterial3Api::class) // For SliderState
@Composable
private fun SeekTimeThumb(
  progressState: ProgressStateWithTickCount,
  sliderState: SliderState,
  interactionSource: MutableInteractionSource,
) {
  LabeledThumb(interactionSource = interactionSource) {
    Text(
      text = Util.getStringForTime(progressState.progressToPosition(sliderState.value)),
      modifier =
        Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f),
            RoundedCornerShape(8.dp),
          )
          .padding(horizontal = 8.dp, vertical = 4.dp),
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
internal fun LabeledThumb(
  interactionSource: MutableInteractionSource,
  modifier: Modifier = Modifier,
  textOffset: Dp = 8.dp,
  label: @Composable (() -> Unit)? = null,
) {
  val interactions = remember { mutableStateListOf<Interaction>() }
  LaunchedEffect(interactionSource) {
    interactionSource.interactions.collect { interaction ->
      when (interaction) {
        is PressInteraction.Press -> interactions.add(interaction)
        is PressInteraction.Release -> interactions.remove(interaction.press)
        is PressInteraction.Cancel -> interactions.remove(interaction.press)
        is DragInteraction.Start -> interactions.add(interaction)
        is DragInteraction.Stop -> interactions.remove(interaction.start)
        is DragInteraction.Cancel -> interactions.remove(interaction.start)
      }
    }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    if (interactions.isNotEmpty() && label != null) {
      Box(
        Modifier.layout { measurable, constraints ->
          val labelPlaceable = measurable.measure(constraints)
          // Report 0,0 size to the parent Box, so the label does not influence the Track
          layout(0, 0) {
            // private impl [androidx.compose.material3.tokens.SliderTokens.HandleHeight]
            labelPlaceable.placeRelative(
              // Center the label horizontally relative to the thumb center
              x = -labelPlaceable.width / 2,
              // Move label's top-left, leaving textOffset between label's bottom and thumb's top
              y = -labelPlaceable.height - textOffset.roundToPx() - ThumbHeight.roundToPx() / 2,
            )
          }
        }
      ) {
        label()
      }
    }
    SliderDefaults.Thumb(interactionSource = interactionSource, thumbSize = ThumbSize)
  }
}

private val ThumbWidth = 4.dp // = androidx.compose.material3.tokens.SliderTokens.HandleWidth
private val ThumbHeight = 44.0.dp // = androidx.compose.material3.tokens.SliderTokens.HandleHeight
private val ThumbSize = DpSize(ThumbWidth, ThumbHeight)
