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

package androidx.media3.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.PresentationState
import androidx.media3.ui.compose.state.rememberPresentationState

/**
 * A container for displaying media content from a [Player].
 *
 * This composable handles the underlying [PlayerSurface] for video playback, resizing the video
 * based on the provided [ContentScale], and displaying a [shutter] according to the
 * [PresentationState] based off the [Player].
 *
 * @param player The attached [Player] that provides media to this content frame.
 * @param modifier The [Modifier] to be applied to the layout.
 * @param surfaceType The type of surface to use for video playback. Can be either
 *   [SURFACE_TYPE_SURFACE_VIEW] or [SURFACE_TYPE_TEXTURE_VIEW].
 * @param contentScale The [ContentScale] strategy for the container.
 * @param keepContentOnReset If `true`, the last rendered frame will remain visible when the player
 *   is reset. If `false`, the surface will be cleared.
 * @param shutter A composable that is displayed when the video surface needs to be covered. By
 *   default, this is a black background.
 */
@UnstableApi
@Composable
fun ContentFrame(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit,
  keepContentOnReset: Boolean = false,
  shutter: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Black)) },
) {
  val presentationState: PresentationState = rememberPresentationState(player, keepContentOnReset)
  val scaledModifier = modifier.resizeWithContentScale(contentScale, presentationState.videoSizeDp)

  // Always leave PlayerSurface to be part of the Compose tree because it will be initialised in
  // the process. If this composable is guarded by some condition, it might never become visible
  // because the Player will not emit the relevant event, e.g. the first frame being ready.
  PlayerSurface(player, scaledModifier, surfaceType)

  if (presentationState.coverSurface) {
    shutter()
  }
}
