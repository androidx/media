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

import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.IntDef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/**
 * Provides a dedicated drawing [Surface] for media playbacks using a [Player].
 *
 * The player's video output is displayed with either a [android.view.SurfaceView] or a
 * [android.view.TextureView].
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
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
) {
  // Player might change between compositions,
  // we need long-lived surface-related lambdas to always use the latest value
  val currentPlayer by rememberUpdatedState(player)

  when (surfaceType) {
    SURFACE_TYPE_SURFACE_VIEW ->
      AndroidView(
        factory = {
          SurfaceView(it).apply {
            if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
              currentPlayer.setVideoSurfaceView(this)
          }
        },
        onReset = {},
        modifier = modifier,
      )
    SURFACE_TYPE_TEXTURE_VIEW ->
      AndroidView(
        factory = {
          TextureView(it).apply {
            if (currentPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
              currentPlayer.setVideoTextureView(this)
          }
        },
        onReset = {},
        modifier = modifier,
      )
    else -> throw IllegalArgumentException("Unrecognized surface type: $surfaceType")
  }
}

/**
 * The type of surface used for media playbacks. One of [SURFACE_TYPE_SURFACE_VIEW] or
 * [SURFACE_TYPE_TEXTURE_VIEW].
 */
@UnstableApi
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.CLASS, AnnotationTarget.TYPE, AnnotationTarget.TYPE_PARAMETER)
@IntDef(SURFACE_TYPE_SURFACE_VIEW, SURFACE_TYPE_TEXTURE_VIEW)
annotation class SurfaceType

/** Surface type to create [android.view.SurfaceView]. */
@UnstableApi const val SURFACE_TYPE_SURFACE_VIEW = 1
/** Surface type to create [android.view.TextureView]. */
@UnstableApi const val SURFACE_TYPE_TEXTURE_VIEW = 2
