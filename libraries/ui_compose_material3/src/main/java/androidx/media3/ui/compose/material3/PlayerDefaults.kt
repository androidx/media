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
import androidx.compose.foundation.layout.BoxScope
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

  internal val regularButtonModifier: Modifier
    @Composable
    get() =
      Modifier.size(PlayerTokens.CenterControlsButtonSize)
        .background(PlayerTokens.controlsBackgroundColor, ButtonDefaults.shape)

  internal val largeButtonModifier: Modifier
    @Composable
    get() =
      Modifier.size(PlayerTokens.CenterControlsButtonSize.times(1.25f))
        .background(PlayerTokens.controlsBackgroundColor, ButtonDefaults.shape)

  /**
   * A Material3 horizontal layout with controls typically found at the top of the player interface.
   *
   * This component acts as a visibility wrapper and provides a flexible [Box]-based structure.
   * Because it executes the content within a [BoxScope], you can easily align individual elements
   * to specific corners (e.g., [Alignment.TopEnd] or [Alignment.TopStart]). By default, the
   * [content] is empty.
   *
   * @param player The [Player] to control.
   * @param visible Whether the controls should be visible.
   * @param modifier The [Modifier] to be applied to the container.
   * @param content The content of the top controls.
   */
  @Composable
  fun TopControls(
    player: Player?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(Player?) -> Unit = {},
  ) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Box(modifier) { content(player) }
    }
  }

  /**
   * A Material3 horizontal layout with controls that are typically found in the center of the
   * player interface.
   *
   * This component provides a flexible slot-based structure for the center player controls. The
   * layout is organized as a centered row of buttons:
   * ```
   * +---------------------------------------------------------------+
   * |                                                               |
   * | +-------------+--------+---------+---------+----------------+ |
   * | |backSecondary|  back  | central | forward |forwardSecondary| |
   * | +-------------+--------+---------+---------+----------------+ |
   * |                                                               |
   * +---------------------------------------------------------------+
   * ```
   *
   * By default, it displays a [PreviousButton], [SeekBackButton], [PlayPauseButton],
   * [SeekForwardButton], and [NextButton] in that order. The central play/pause button is slightly
   * larger than the others.
   *
   * @param player The [Player] to control.
   * @param visible Whether the layout should be visible. When `true`, a fade in-and-out animation
   *   is used.
   * @param modifier The [Modifier] to be applied to the container.
   * @param horizontalArrangement The horizontal arrangement of the layout's children.
   * @param verticalAlignment The vertical alignment of the layout's children.
   * @param backSecondary Slot for the outermost left control. Defaults to [PreviousButton].
   * @param back Slot for the inner left control. Defaults to [SeekBackButton].
   * @param central Slot for the central control. Defaults to a large [PlayPauseButton].
   * @param forward Slot for the inner right control. Defaults to [SeekForwardButton].
   * @param forwardSecondary Slot for the outermost right control. Defaults to [NextButton].
   */
  @Composable
  fun CenterControls(
    player: Player?,
    visible: Boolean,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal =
      Arrangement.spacedBy(PlayerTokens.CenterControlsSpacing, Alignment.CenterHorizontally),
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    backSecondary: @Composable (Player?) -> Unit = {
      PreviousButton(it, modifier = regularButtonModifier)
    },
    back: @Composable (Player?) -> Unit = { SeekBackButton(it, modifier = regularButtonModifier) },
    central: @Composable (Player?) -> Unit = {
      PlayPauseButton(it, modifier = largeButtonModifier)
    },
    forward: @Composable (Player?) -> Unit = {
      SeekForwardButton(it, modifier = regularButtonModifier)
    },
    forwardSecondary: @Composable (Player?) -> Unit = {
      NextButton(it, modifier = regularButtonModifier)
    },
  ) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
      Row(
        modifier = modifier,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
      ) {
        backSecondary(player)
        back(player)
        central(player)
        forward(player)
        forwardSecondary(player)
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
