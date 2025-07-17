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

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.annotation.IntDef
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Provides a dedicated drawing [android.view.Surface] for media playbacks using a [Player].
 *
 * The player's video output is displayed with either a [android.view.SurfaceView] or a
 * [android.view.TextureView].
 *
 * [Player] takes care of attaching the rendered output to the [android.view.Surface] and clearing
 * it, when it is destroyed.
 *
 * See
 * [Choosing a surface type](https://developer.android.com/media/media3/ui/playerview#surfacetype)
 * for more information.
 */
@UnstableApi
@Composable
fun PlayerSurface(
  player: Player?,
  modifier: Modifier = Modifier,
  surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
) {
  when (surfaceType) {
    SURFACE_TYPE_SURFACE_VIEW ->
      PlayerSurfaceInternal(
        player,
        modifier,
        createView = ::SurfaceView,
        setVideoView = Player::setVideoSurfaceView,
        clearVideoView = Player::clearVideoSurfaceView,
      )
    SURFACE_TYPE_TEXTURE_VIEW ->
      PlayerSurfaceInternal(
        player,
        modifier,
        createView = ::TextureView,
        setVideoView = Player::setVideoTextureView,
        clearVideoView = Player::clearVideoTextureView,
      )
    else -> throw IllegalArgumentException("Unrecognized surface type: $surfaceType")
  }
}

@Composable
private fun <T : View> PlayerSurfaceInternal(
  player: Player?,
  modifier: Modifier,
  createView: (Context) -> T,
  setVideoView: Player.(T) -> Unit,
  clearVideoView: Player.(T) -> Unit,
) {
  var view by remember { mutableStateOf<T?>(null) }

  AndroidView(
    modifier = modifier,
    factory = { createView(it) },
    onReset = {},
    update = { view = it },
  )

  view?.let { view ->
    LaunchedEffect(view, player) {
      if (player != null) {
        view.attachedPlayer?.let { previousPlayer ->
          if (
            previousPlayer != player &&
              previousPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)
          )
            previousPlayer.clearVideoView(view)
        }
        if (player.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE)) {
          player.setVideoView(view)
          view.attachedPlayer = player
        }
      } else {
        // Now that our player got null'd, we are not in a rush to get the old view from the
        // previous player. Instead, we schedule clearing of the view for later on the main thread,
        // since that player might have a new view attached to it in the meantime. This will avoid
        // unnecessarily creating a Surface placeholder.
        withContext(Dispatchers.Main) {
          view.attachedPlayer?.let { previousPlayer ->
            if (previousPlayer.isCommandAvailable(Player.COMMAND_SET_VIDEO_SURFACE))
              previousPlayer.clearVideoView(view)
            view.attachedPlayer = null
          }
        }
      }
    }
  }
}

private var View.attachedPlayer: Player?
  get() = tag as? Player
  set(player) {
    tag = player
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
