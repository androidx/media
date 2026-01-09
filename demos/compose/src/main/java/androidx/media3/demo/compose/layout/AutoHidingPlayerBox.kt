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

package androidx.media3.demo.compose.layout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.demo.compose.buttons.Controls
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SurfaceType
import kotlinx.coroutines.delay

/**
 * A [PlayerBox] variation that automatically hides the controls after a short timeout.
 *
 * The controls are shown initially and will hide after `controlsTimeoutMs` milliseconds of
 * inactivity. Any interaction with the player area (including the controls themselves) will reset
 * the timeout and keep the controls visible for another `controlsTimeoutMs` milliseconds. Tapping
 * on the player area also toggles the visibility of the controls.
 *
 * @param player The [Player] instance to be controlled and whose content is displayed.
 * @param modifier The [Modifier] to be applied to the outer [Box].
 * @param surfaceType The type of surface to use for video rendering. See [SurfaceType].
 * @param contentScale The scaling mode to apply to the content within the
 *   [ContentFrame][androidx.media3.ui.compose.ContentFrame].
 * @param keepContentOnReset Whether to keep the content visible when the player is reset.
 * @param controlsTimeoutMs Delay amount in milliseconds for keeping the controls visible on every
 *   interaction with the screen.
 */
@Composable
internal fun AutoHidingPlayerBox(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  controlsTimeoutMs: Long = 1000,
) {
  var showControls by remember { mutableStateOf(true) }
  var isInteracting by remember { mutableStateOf(false) }

  // Timer only runs when controls are shown AND user is NOT currently touching the screen
  LaunchedEffect(showControls, isInteracting) {
    if (showControls && !isInteracting) {
      delay(controlsTimeoutMs)
      showControls = false
    }
  }

  PlayerBox(
    player = player,
    modifier =
      modifier
        .noRippleClickable { showControls = !showControls }
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              // Check if any pointers are currently down
              // Useful to prevent recomposition mid-drag (pointer will remain pressed)
              isInteracting = event.changes.any { it.pressed }
            }
          }
        },
    surfaceType = surfaceType,
    contentScale = contentScale,
    keepContentOnReset = keepContentOnReset,
    controls = {
      // TODO: b/474553667 - remove guard clause once Controls can take nullable Player
      if (player != null) {
        AnimatedVisibility(visible = showControls, enter = fadeIn(), exit = fadeOut()) {
          Box(Modifier.fillMaxSize()) { Controls(player) }
        }
      }
    },
  )
}
