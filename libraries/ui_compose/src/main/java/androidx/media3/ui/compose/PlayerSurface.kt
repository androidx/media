/*
 * Copyright 2024 The Android Open Source Project
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

import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.IntDef
import androidx.compose.foundation.AndroidEmbeddedExternalSurface
import androidx.compose.foundation.AndroidExternalSurface
import androidx.compose.foundation.AndroidExternalSurfaceScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.RenderingState
import androidx.media3.ui.compose.state.rememberRenderingState
import kotlin.math.roundToInt

/**
 * Provides a dedicated drawing [Surface] for media playbacks using a [Player].
 *
 * The player's video output is displayed with either a [SurfaceView]/[AndroidExternalSurface] or a
 * [TextureView]/[AndroidEmbeddedExternalSurface].
 *
 * [Player] takes care of attaching the rendered output to the [Surface] and clearing it, when it is
 * destroyed.
 *
 * See
 * [Choosing a surface type](https://developer.android.com/media/media3/ui/playerview#surfacetype)
 * for more information.
 */
@UnstableApi
@Composable
fun PlayerSurface(
  player: Player,
  modifier: Modifier = Modifier,
  renderingState: RenderingState = rememberRenderingState(player),
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
  contentScale: ContentScale = ContentScale.Fit
) {
  // Player might change between compositions,
  // we need long-lived surface-related lambdas to always use the latest value
  val currentPlayer by rememberUpdatedState(player)
  val onSurfaceCreated: (Surface) -> Unit = { surface ->
    if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
      player.setVideoSurface(surface)
  }
  val onSurfaceDestroyed: () -> Unit = {
    if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
      player.clearVideoSurface()
  }
  val onSurfaceInitialized: AndroidExternalSurfaceScope.() -> Unit = {
    onSurface { surface, _, _ ->
      onSurfaceCreated(surface)
      surface.onDestroyed { onSurfaceDestroyed() }
    }
  }

  val myModifier = modifier
    .fillMaxSize()
    .wrapContentSize()
    .then(
      renderingState.size?.let { srcSizePx ->
        Modifier.layout { measurable, constraints ->
          val dstSizePx = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
          val scaleFactor = contentScale.computeScaleFactor(srcSizePx, dstSizePx)

          val placeable = measurable.measure(
            constraints.copy(
              maxWidth = (srcSizePx.width * scaleFactor.scaleX).roundToInt(),
              maxHeight = (srcSizePx.height * scaleFactor.scaleY).roundToInt()
            )
          )

          layout(placeable.width, placeable.height) {
            placeable.place(0, 0)
          }
        }
      } ?: Modifier
    )

  when (surfaceType) {
    SURFACE_TYPE_SURFACE_VIEW ->
      AndroidExternalSurface(myModifier, onInit = onSurfaceInitialized)

    SURFACE_TYPE_TEXTURE_VIEW ->
      AndroidEmbeddedExternalSurface(myModifier, onInit = onSurfaceInitialized)

    else -> throw IllegalArgumentException("Unrecognized surface type: $surfaceType")
  }
}

/**
 * The type of surface view used for media playbacks. One of [SURFACE_TYPE_SURFACE_VIEW] or
 * [SURFACE_TYPE_TEXTURE_VIEW].
 */
@UnstableApi
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
@IntDef(SURFACE_TYPE_SURFACE_VIEW, SURFACE_TYPE_TEXTURE_VIEW)
annotation class SurfaceType

/** Surface type equivalent to [SurfaceView] . */
@UnstableApi
const val SURFACE_TYPE_SURFACE_VIEW = 1

/** Surface type equivalent to [TextureView]. */
@UnstableApi
const val SURFACE_TYPE_TEXTURE_VIEW = 2
