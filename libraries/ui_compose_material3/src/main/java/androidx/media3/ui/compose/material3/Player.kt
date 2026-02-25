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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SurfaceType
import kotlinx.coroutines.delay

/**
 * A composable that provides a basic player UI layout, combining a [ContentFrame] for displaying
 * player content with customizable controls and a shutter.
 *
 * This composable is designed to be a flexible container for building player interfaces. It
 * consists of a [ContentFrame] that handles the rendering of the player's video, overlaid with
 * optional controls and a shutter.
 *
 * @param player The [Player] instance to be controlled and whose content is displayed.
 * @param modifier The [Modifier] to be applied to the outer [Box].
 * @param surfaceType The type of surface to use for video rendering. See [SurfaceType].
 * @param contentScale The scaling mode to apply to the content within the [ContentFrame].
 * @param keepContentOnReset Whether to keep the content visible when the player is reset.
 * @param shutter A composable to be displayed as a shutter over the content. The default shutter is
 *   a black [Box].
 * @param controlsTimeoutMs Delay amount in milliseconds for keeping the controls visible on every
 *   interaction with the screen. A non-positive value will cause the controls to remain visible
 *   indefinitely, unless visibility is toggled by clicking on the component.
 * @param topControls A composable aligned with [Alignment.TopCenter], receiving the [player].
 * @param centerControls A composable aligned with [Alignment.Center], receiving the [player].
 * @param bottomControls A composable aligned with [Alignment.BottomCenter], receiving the [player].
 */
@UnstableApi
@Composable
fun Player(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  shutter: @Composable () -> Unit = {
    PlayerDefaults.Shutter()
  }, // TODO: b/305035807 change to PlayerDefaults::Shutter with Kotlin 2.2
  controlsTimeoutMs: Long = 3000L,
  topControls: (@Composable BoxScope.(Player?, Boolean) -> Unit)? = defaultTopControls,
  centerControls: (@Composable BoxScope.(Player?, Boolean) -> Unit)? = defaultCenterControls,
  bottomControls: (@Composable BoxScope.(Player?, Boolean) -> Unit)? = defaultBottomControls,
) {
  var showControls by remember { mutableStateOf(true) }
  var anyPointerDown by remember { mutableStateOf(false) } // pointer pressed, interacting

  if (controlsTimeoutMs > 0) {
    LaunchedEffect(showControls, anyPointerDown) {
      // Timer only runs when controls are shown AND user is NOT currently touching the screen
      if (showControls && !anyPointerDown) {
        delay(controlsTimeoutMs)
        showControls = false
      }
    }
  }

  Box(
    modifier.withControlsVisibility(
      onToggleShowControls = { showControls = !showControls },
      onPressedPointerEvent = { anyPointerDown = it },
    )
  ) {
    ContentFrame(
      player = player,
      surfaceType = surfaceType,
      contentScale = contentScale,
      keepContentOnReset = keepContentOnReset,
      shutter = shutter,
    )
    // this = BoxScope of a container-Box
    Box(Modifier.align(Alignment.TopCenter)) { topControls?.invoke(this, player, showControls) }
    Box(Modifier.align(Alignment.Center)) { centerControls?.invoke(this, player, showControls) }
    Box(Modifier.align(Alignment.BottomCenter)) {
      bottomControls?.invoke(this, player, showControls)
    }
  }
}

@Composable
private fun Modifier.withControlsVisibility(
  onToggleShowControls: () -> Unit,
  onPressedPointerEvent: (Boolean) -> Unit,
): Modifier =
  this.clickable(
      interactionSource = remember { MutableInteractionSource() },
      indication = null, // to prevent the ripple from the tap
    ) {
      onToggleShowControls()
    }
    .pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent()
          // Check if any pointers are currently down
          // Useful to prevent recomposition mid-drag (pointer will remain pressed)
          onPressedPointerEvent(event.changes.any { it.pressed })
        }
      }
    }

private val defaultTopControls:
  @Composable
  BoxScope.(player: Player?, showControls: Boolean) -> Unit =
  { player, showControls ->
    PlayerDefaults.TopControls(
      player,
      showControls,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp),
      innerModifier =
        Modifier.background(
          MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
          ButtonDefaults.shape,
        ),
    )
  }

private val defaultCenterControls:
  @Composable
  BoxScope.(player: Player?, showControls: Boolean) -> Unit =
  { player, showControls ->
    PlayerDefaults.CenterControls(
      player,
      showControls,
      modifier = Modifier.fillMaxWidth(),
      innerModifier =
        Modifier.size(50.dp)
          .background(
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
            ButtonDefaults.shape,
          ),
    )
  }

private val defaultBottomControls:
  @Composable
  BoxScope.(player: Player?, showControls: Boolean) -> Unit =
  { player, showControls ->
    PlayerDefaults.BottomControls(
      player,
      showControls,
      Modifier.fillMaxWidth().padding(horizontal = 15.dp),
    )
  }
