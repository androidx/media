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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SurfaceType

/**
 * A composable that provides a basic player UI layout with default controls and a shutter.
 *
 * This composable consists of a [ContentFrame] that handles the rendering of the player's video,
 * overlaid with default button controls, progress slider, and a shutter.
 *
 * To customize the UI components, use the overload that accepts these as parameters.
 *
 * @param player The [Player] instance to be controlled and whose content is displayed.
 * @param modifier The [Modifier] to be applied to the outer [Box].
 */
@UnstableApi
@Composable
fun Player(player: Player?, modifier: Modifier = Modifier) {
  Player(
    player,
    modifier,
    surfaceType = SURFACE_TYPE_SURFACE_VIEW,
    contentScale = ContentScale.Fit,
    keepContentOnReset = false,
    shutter = { PlayerDefaults.Shutter() },
    showControls = true,
  )
}

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
 * @param showControls Whether the controls should be visible.
 * @param topControls A composable aligned with [Alignment.TopCenter], receiving the [player] and
 *   [showControls].
 * @param centerControls A composable aligned with [Alignment.Center], receiving the [player] and
 *   [showControls].
 * @param bottomControls A composable aligned with [Alignment.BottomCenter], receiving the [player]
 *   and [showControls].
 */
@ExperimentalApi // TODO: b/490015547 - Move to stable/unstable
@Composable
fun Player(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  shutter: @Composable () -> Unit = PlayerDefaults::Shutter,
  showControls: Boolean = true,
  topControls: (@Composable BoxScope.(Player?, Boolean) -> Unit)? = { player, showControls ->
    PlayerDefaults.TopControls(player, showControls)
  },
  centerControls: (@Composable BoxScope.(Player?, Boolean) -> Unit)? = { player, showControls ->
    PlayerDefaults.CenterControls(player, showControls)
  },
  bottomControls: (@Composable BoxScope.(Player?, Boolean) -> Unit)? = { player, showControls ->
    PlayerDefaults.BottomControls(player, showControls)
  },
) {
  Box(modifier) {
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
