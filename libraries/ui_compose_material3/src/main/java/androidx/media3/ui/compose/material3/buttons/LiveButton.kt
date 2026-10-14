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

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * @param player The [Player] to control.
 * @param modifier The [Modifier] to be applied to the button.
 * @param liveEdgeThresholdMs How far behind the target live offset, in milliseconds, playback can
 *   be while still being considered at the live edge. Without playback speed control the player
 *   typically settles a segment duration or more behind the target, so this should be larger than
 *   the expected segment duration. Defaults to 5000ms.
 * @param colors [ButtonColors] that will be used to resolve the colors for this button in different
 *   states. Defaults to [LiveButtonDefaults.textButtonColors].
 * @param scope The [CoroutineScope] to use for listening to player progress updates.
 * @param content A composable lambda with [ProgressStateWithTickInterval] as its receiver, allowing
 *   the content of the button to be customized.
 */
@UnstableApi
@Composable
fun LiveButton(
  player: Player?,
  modifier: Modifier = Modifier,
  liveEdgeThresholdMs: Long = DEFAULT_LIVE_EDGE_THRESHOLD_MS,
  colors: ButtonColors = LiveButtonDefaults.textButtonColors(),
  scope: CoroutineScope = rememberCoroutineScope(),
  content: @Composable ProgressStateWithTickInterval.() -> Unit = {
    Text(stringResource(R.string.live_button_text))
  },
) {
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
      onClick = { progressState.seekToDefaultPosition() },
      enabled = !atLiveEdge,
      modifier = modifier,
      colors = colors,
      contentPadding = PaddingValues(0.dp),
    ) {
      progressState.content()
    }
  }
}

private const val DEFAULT_TICK_INTERVAL_MS = 1_000L
private const val DEFAULT_LIVE_EDGE_THRESHOLD_MS = 5_000L
