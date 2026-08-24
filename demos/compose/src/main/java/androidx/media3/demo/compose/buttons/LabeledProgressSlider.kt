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

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.indicators.ProgressIndicator
import androidx.media3.ui.compose.material3.util.isScrubbingModeEnabled
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
 * @param thumbTrackGapSize The gap between the thumb and the track. Set to `0.dp` to remove the gap
 *   completely.
 * @param thumb A custom thumb to be displayed on the slider, it is placed on top of the track. The
 *   lambda receives the [ProgressStateWithTickCount], the current [SliderState], and a boolean
 *   indicating whether changing progress is enabled. If `null`, a standard Material3
 *   `SliderDefaults.Thumb` will be shown.
 * @param track A custom track to be displayed on the slider, it is placed underneath the thumb. The
 *   lambda receives the [ProgressStateWithTickCount], the current [SliderState], and a boolean
 *   indicating whether changing progress is enabled. If `null`, a standard Material3
 *   `SliderDefaults.Track` will be shown.
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
  thumbTrackGapSize: Dp = ThumbTrackGapSize,
  thumb: (@Composable (ProgressStateWithTickCount, SliderState, Boolean) -> Unit)? =
    { progressState, sliderState, enabled ->
      SeekTimeThumb(progressState, sliderState, interactionSource, enabled)
    },
  track: @Composable ((ProgressStateWithTickCount, SliderState, Boolean) -> Unit)? =
    { progressState, sliderState, enabled ->
      BufferingTrack(
        progressState,
        sliderState,
        enabled,
        thumbTrackGapSize = thumbTrackGapSize,
        colors = colors,
        interactionSource = interactionSource,
      )
    },
) {
  var sliderWidthPx by remember { mutableIntStateOf(0) }
  val exoPlayer = remember(player) { player as? ExoPlayer }
  ProgressIndicator(player, totalTickCount = sliderWidthPx, scope) {
    var isDragging by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }

    // Fallback for navigating away before the trailing invoke in onValueChangeFinished is called
    DisposableEffect(exoPlayer) {
      onDispose {
        exoPlayer?.setScrubbingModeEnabled(false)
        isDragging = false
      }
    }
    // Cache the result to avoid repeated scrubbing mode checks
    var scrubbingEnabledForThisPlayer by remember(player) { mutableStateOf(false) }

    Slider(
      value = if (isDragging) seekPosition else currentPositionProgress,
      onValueChange = {
        if (!isDragging) {
          exoPlayer?.setScrubbingModeEnabled(true)
          scrubbingEnabledForThisPlayer = exoPlayer.isScrubbingModeEnabled()
        }
        isDragging = true
        seekPosition = it
        // Dispatch seeks only if they are less expensive (scrubbing mode enabled)
        if (scrubbingEnabledForThisPlayer) updateCurrentPositionProgress(seekPosition)
        onValueChange?.invoke(it)
      },
      onValueChangeFinished = {
        updateCurrentPositionProgress(seekPosition)
        exoPlayer?.setScrubbingModeEnabled(false)
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
          thumb(this, sliderState, changingProgressEnabled)
        }
      },
      track = { sliderState ->
        if (track == null) {
          SliderDefaults.Track(
            colors = colors,
            enabled = changingProgressEnabled,
            sliderState = sliderState,
            thumbTrackGapSize = thumbTrackGapSize,
            trackInsideCornerSize = if (thumbTrackGapSize == 0.dp) 0.dp else TrackInsideCornerSize,
          )
        } else {
          track(this, sliderState, changingProgressEnabled)
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
  enabled: Boolean = true,
) {
  LabeledThumb(interactionSource = interactionSource, enabled = enabled) {
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
  enabled: Boolean = true,
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
        is FocusInteraction.Focus -> interactions.add(interaction)
        is FocusInteraction.Unfocus -> interactions.remove(interaction.focus)
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
    SliderDefaults.Thumb(
      interactionSource = interactionSource,
      enabled = enabled,
      thumbSize = ThumbSize,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BufferingTrack(
  progressState: ProgressStateWithTickCount,
  sliderState: SliderState,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  thumbTrackGapSize: Dp = ThumbTrackGapSize,
  colors: SliderColors = SliderDefaults.colors(),
  interactionSource: MutableInteractionSource? = null,
) {
  val interactions = remember { mutableStateListOf<Interaction>() }
  if (interactionSource != null) {
    LaunchedEffect(interactionSource) {
      interactionSource.interactions.collect { interaction ->
        when (interaction) {
          is PressInteraction.Press -> interactions.add(interaction)
          is PressInteraction.Release -> interactions.remove(interaction.press)
          is PressInteraction.Cancel -> interactions.remove(interaction.press)
          is DragInteraction.Start -> interactions.add(interaction)
          is DragInteraction.Stop -> interactions.remove(interaction.start)
          is DragInteraction.Cancel -> interactions.remove(interaction.start)
          is FocusInteraction.Focus -> interactions.add(interaction)
          is FocusInteraction.Unfocus -> interactions.remove(interaction.focus)
        }
      }
    }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    SliderDefaults.Track(
      sliderState = sliderState,
      enabled = enabled,
      colors = colors,
      thumbTrackGapSize = thumbTrackGapSize,
      trackInsideCornerSize = if (thumbTrackGapSize == 0.dp) 0.dp else TrackInsideCornerSize,
    )

    val isDragging = interactions.isNotEmpty()
    val currentBufferedPos = progressState.bufferedPositionProgress
    var draggingBufferedPos by remember { mutableFloatStateOf(-1f) }

    if (isDragging && draggingBufferedPos < 0f) {
      draggingBufferedPos = currentBufferedPos
    } else if (!isDragging) {
      draggingBufferedPos = -1f
    }

    val bufferedPos =
      if (isDragging && draggingBufferedPos >= 0f) draggingBufferedPos else currentBufferedPos

    Canvas(modifier = Modifier.matchParentSize()) {
      val thumbPos = sliderState.coercedValueAsFraction

      val startX = size.width * thumbPos
      val endX = size.width * bufferedPos

      val trackHeight = 16.dp.toPx()

      val thumbWidth = if (isDragging) PressedThumbWidth else ThumbWidth
      // The gap from the center of the thumb to the start of the visible track
      val visualGap =
        if (thumbTrackGapSize > 0.dp) {
          (thumbWidth.toPx() / 2f) + thumbTrackGapSize.toPx()
        } else {
          0f
        }
      val visualStartX = (startX + visualGap).coerceIn(0f, size.width)
      val visualEndX = endX.coerceIn(0f, size.width)

      if (visualEndX > visualStartX) {
        val bufferingColor =
          if (enabled) {
            colors.activeTrackColor.copy(alpha = colors.activeTrackColor.alpha * 0.5f)
          } else {
            colors.disabledActiveTrackColor.copy(
              alpha = colors.disabledActiveTrackColor.alpha * 0.5f
            )
          }

        val trackInsideCornerRadius =
          if (thumbTrackGapSize > 0.dp) TrackInsideCornerSize.toPx() else 0f
        val fullCornerRadius = trackHeight / 2f

        val path =
          Path().apply {
            addRoundRect(
              RoundRect(
                left = visualStartX,
                top = (size.height - trackHeight) / 2f,
                right = visualEndX,
                bottom = (size.height + trackHeight) / 2f,
                topLeftCornerRadius = CornerRadius(trackInsideCornerRadius),
                bottomLeftCornerRadius = CornerRadius(trackInsideCornerRadius),
                topRightCornerRadius = CornerRadius(fullCornerRadius),
                bottomRightCornerRadius = CornerRadius(fullCornerRadius),
              )
            )
          }

        drawPath(path = path, color = bufferingColor)
      }
    }
  }
}

private val ThumbTrackGapSize =
  6.dp // = androidx.compose.material3.tokens.SliderTokens.ActiveHandleLeadingSpace
private val TrackInsideCornerSize = 2.dp
private val ThumbWidth = 4.dp // = androidx.compose.material3.tokens.SliderTokens.HandleWidth
private val PressedThumbWidth =
  2.dp // = androidx.compose.material3.tokens.SliderTokens.PressedHandleWidth
private val ThumbHeight = 44.0.dp // = androidx.compose.material3.tokens.SliderTokens.HandleHeight
private val ThumbSize = DpSize(ThumbWidth, ThumbHeight)
