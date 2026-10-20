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

package androidx.media3.ui.compose.material3.buttons

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.ProgressStateWithTickInterval
import androidx.media3.ui.compose.state.rememberProgressStateWithTickInterval
import kotlinx.coroutines.CoroutineScope

/** Contains default values used by [LiveButton]. */
@UnstableApi
object LiveButtonDefaults {
  /**
   * Creates a [ButtonColors] that represents the default colors used in a [LiveButton].
   *
   * @param contentColor The content color of the button when enabled (behind the live edge).
   * @param disabledContentColor The content color of the button when disabled (at the live edge).
   */
  @Composable
  fun textButtonColors(
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledContentColor: Color = Color.Red,
  ): ButtonColors =
    ButtonDefaults.textButtonColors(
      contentColor = contentColor,
      disabledContentColor = disabledContentColor,
    )
}

/**
 * A Material3 [TextButton] that is only visible during live streams when seeking to the default
 * position is available. It indicates whether the playback is at the live edge and seeks to the
 * default position (typically the live edge) when clicked while behind the live edge.
 *
 * ![LiveButton
 * image](https://developer.android.com/images/reference/androidx/media3/ui/compose/material3/live-button.svg)
 *
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param liveEdgeThresholdMs How far behind the target live offset, in milliseconds, playback can
 *   be while still being considered at the live edge. Without playback speed control the player
 *   typically settles a segment duration or more behind the target, so this should be larger than
 *   the expected segment duration. Defaults to 5000ms.
 * @param shape Defines the shape of this button's container, border (when [border] is not null),
 *   and shadow (when using [elevation]). See [ButtonDefaults.textShape].
 * @param colors [ButtonColors] that will be used to resolve the colors for this button in different
 *   states. Defaults to [LiveButtonDefaults.textButtonColors].
 * @param elevation [ButtonElevation] used to resolve the elevation for this button in different
 *   states. A `TextButton` typically has no elevation. See [ButtonDefaults.buttonElevation].
 * @param border The border to draw around the container of this button.
 * @param contentPadding The spacing values to apply internally between the container and the
 *   content. Defaults to `PaddingValues(0.dp)`.
 * @param interactionSource An optional hoisted [MutableInteractionSource] for observing and
 *   emitting [Interaction][androidx.compose.foundation.interaction.Interaction]s for this button.
 *   You can use this to change the button's appearance or preview the button in different states.
 *   Note that if `null` is provided, interactions will still happen internally.
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param onClick The action to be performed when the button is clicked. This lambda has
 *   [ProgressStateWithTickInterval] as its receiver, providing access to the button's current
 *   state. The default behavior is to call [ProgressStateWithTickInterval.seekToDefaultPosition].
 *   Consumers can customize this behavior:
 * * To add custom logic while still performing the default action, call
 *   `this.seekToDefaultPosition()` within your lambda.
 * * To completely override the default behavior, implement your custom logic without calling
 *   `this.seekToDefaultPosition()`.
 *
 * @param content A composable lambda with [ProgressStateWithTickInterval] as its receiver, allowing
 *   the content of the button to be customized.
 */
@UnstableApi
@Composable
fun LiveButton(
  player: Player?,
  modifier: Modifier = Modifier,
  liveEdgeThresholdMs: Long = DEFAULT_LIVE_EDGE_THRESHOLD_MS,
  shape: Shape = ButtonDefaults.textShape,
  colors: ButtonColors = LiveButtonDefaults.textButtonColors(),
  elevation: ButtonElevation? = null,
  border: BorderStroke? = null,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  interactionSource: MutableInteractionSource? = null,
  scope: CoroutineScope = rememberCoroutineScope(),
  onClick: ProgressStateWithTickInterval.() -> Unit =
    ProgressStateWithTickInterval::seekToDefaultPosition,
  content: @Composable ProgressStateWithTickInterval.() -> Unit = {
    Text(stringResource(R.string.live_button_text))
  },
) {
  val customOnClick: ProgressStateWithTickInterval.() -> Unit = onClick
  val progressState =
    rememberProgressStateWithTickInterval(
      player = player,
      tickIntervalMs = DEFAULT_TICK_INTERVAL_MS,
      scope = scope,
    )
  if (progressState.isLive && progressState.seekToDefaultPositionEnabled) {
    val baseTargetOffsetMs =
      if (progressState.targetLiveOffsetMs != C.TIME_UNSET) progressState.targetLiveOffsetMs else 0L

    val atLiveEdge =
      if (progressState.liveOffsetMs != C.TIME_UNSET) {
        progressState.liveOffsetMs <= baseTargetOffsetMs + liveEdgeThresholdMs
      } else {
        progressState.durationMs != C.TIME_UNSET &&
          progressState.targetLiveOffsetMs != C.TIME_UNSET &&
          (progressState.durationMs - progressState.currentPositionMs).coerceAtLeast(0L) <=
            progressState.targetLiveOffsetMs + liveEdgeThresholdMs
      }

    TextButton(
      onClick = { progressState.customOnClick() },
      enabled = !atLiveEdge,
      modifier = modifier,
      shape = shape,
      colors = colors,
      elevation = elevation,
      border = border,
      contentPadding = contentPadding,
      interactionSource = interactionSource,
    ) {
      progressState.content()
    }
  }
}

private const val DEFAULT_TICK_INTERVAL_MS = 1_000L
private const val DEFAULT_LIVE_EDGE_THRESHOLD_MS = 5_000L
