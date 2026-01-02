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

package androidx.media3.demo.compose.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SurfaceType

/**
 * A composable that provides a basic player UI layout, combining a
 * [ContentFrame][androidx.media3.ui.compose.ContentFrame] for displaying player content with
 * customizable controls and a shutter.
 *
 * This composable is designed to be a flexible container for building player interfaces. It
 * consists of a [ContentFrame][androidx.media3.ui.compose.ContentFrame] that handles the rendering
 * of the player's video or audio, overlaid with optional controls and a shutter.
 *
 * @param player The [Player] instance to be controlled and whose content is displayed.
 * @param modifier The [Modifier] to be applied.
 * @param surfaceType The type of surface to use for video rendering. See [SurfaceType].
 * @param contentScale The scaling mode to apply to the content within the
 *   [ContentFrame][androidx.media3.ui.compose.ContentFrame].
 * @param keepContentOnReset Whether to keep the content visible when the player is reset.
 * @param shutter A composable to be displayed as a shutter over the content. The default shutter is
 *   a black [Box].
 * @param controls A composable that provides the player controls and placed within the same
 *   [BoxScope] of [ContentFrame].
 */
@Composable
internal fun MediaPlayer(
  player: Player,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  shutter: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Black)) },
  controls: @Composable BoxScope.() -> Unit,
) {
  Box(modifier) {
    var showControls by remember { mutableStateOf(true) }
    ContentFrame(
      player = player,
      modifier = Modifier.noRippleClickable { showControls = !showControls },
      surfaceType = surfaceType,
      contentScale = contentScale,
      keepContentOnReset = keepContentOnReset,
      shutter = shutter,
    )

    if (showControls) {
      // drawn on top of a potential shutter
      controls()
    }
  }
}
