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

package androidx.media3.ui.compose.material3

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider

/**
 * Contains the default values used by [Player].
 *
 * This object provides the standard Material3 components that make up the default player interface,
 * including structural layouts like [TopControls], [CenterControls], and [BottomControls].
 *
 * The components have opinionated styling properties to maintain a consistent aesthetic when
 * building custom controls or overriding specific slots within the provided layouts.
 */
@UnstableApi
object PlayerDefaults {

  @Composable
  internal fun TopControls(player: Player?, visible: Boolean) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Row(
        modifier =
          Modifier.fillMaxWidth().padding(horizontal = PlayerTokens.ControlsHorizontalPadding),
        horizontalArrangement = Arrangement.End,
      ) {
        MuteButton(
          player,
          Modifier.background(PlayerTokens.controlsBackgroundColor, ButtonDefaults.shape),
        )
      }
    }
  }

  @Composable
  internal fun CenterControls(player: Player?, showControls: Boolean) {
    AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
          Arrangement.spacedBy(PlayerTokens.CenterControlsSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val buttonModifier =
          Modifier.size(PlayerTokens.CenterControlsButtonSize)
            .background(PlayerTokens.controlsBackgroundColor, ButtonDefaults.shape)
        PreviousButton(player, buttonModifier)
        SeekBackButton(player, buttonModifier)
        PlayPauseButton(player, buttonModifier)
        SeekForwardButton(player, buttonModifier)
        NextButton(player, buttonModifier)
      }
    }
  }

  /**
   * A Material3 columnar layout that display controls that are typically positioned at the bottom
   * of the player interface.
   *
   * This component provides a flexible slot-based structure for building player controls. The
   * layout is organised as follows:
   * ```
   * +-------------------------------------------------+
   * |                      above                      |
   * +-------+---------------------------------+-------+
   * | left  |         progressSlider          | right |
   * +-------+---------------------------------+-------+
   * |                      below                      |
   * +-------------------------------------------------+
   * ```
   *
   * By default, this layout provides a [ProgressSlider] in the center. To its left, it displays the
   * current media position using [PositionText], and to its right, it displays the total media
   * duration using [DurationText]. The [above] and [below] slots are empty by default.
   *
   * @param player The [Player] to control.
   * @param visible Whether the layout should be visible. When `true`, a fade in-and-out animation
   *   is used.
   * @param modifier The [Modifier] to be applied to the column container.
   * @param above Slot for content positioned above the slider.
   * @param left Slot for content positioned to the left of the slider. Defaults to [PositionText]
   *   with padding applied.
   * @param progressSlider Slot for the main progress slider. It expands to fill available width and
   *   defaults to [ProgressSlider].
   * @param right Slot for content positioned to the right of the slider. Defaults to [DurationText]
   *   with padding applied.
   * @param below Slot for content positioned below the slider.
   */
  @Composable
  fun BottomControls(
    player: Player?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    above: @Composable ColumnScope.(Player?) -> Unit = {},
    left: @Composable (Player?) -> Unit = {
      PositionText(it, Modifier.padding(end = PlayerTokens.BottomControlsHorizontalPadding))
    },
    right: @Composable (Player?) -> Unit = {
      DurationText(it, Modifier.padding(start = PlayerTokens.BottomControlsHorizontalPadding))
    },
    below: @Composable ColumnScope.(Player?) -> Unit = {},
    progressSlider: @Composable (Player?) -> Unit = { ProgressSlider(it) },
  ) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Column(
        modifier
          .background(brush = PlayerTokens.bottomControlsGradient)
          .padding(horizontal = PlayerTokens.ControlsHorizontalPadding)
      ) {
        above(player)
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          left(player)
          Box(modifier = Modifier.weight(1f)) { progressSlider(player) }
          right(player)
        }
        below(player)
      }
    }
  }

  @Composable
  internal fun Shutter(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(PlayerTokens.shutterColor))
  }
}
