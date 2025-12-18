/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.docsamples

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.common.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.SurfaceType
import androidx.media3.ui.compose.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState

@OptIn(UnstableApi::class)
class ComposeSnippets {
  @Composable
  fun CustomPlayPauseButton(player: Player, modifier: Modifier = Modifier) {
    // [START android_compose_custom_play_pause_button]
    val state = rememberPlayPauseButtonState(player)

    IconButton(onClick = state::onClick, modifier = modifier, enabled = state.isEnabled) {
      Icon(
        imageVector = if (state.showPlay) Icons.Default.PlayArrow else Icons.Default.Pause,
        contentDescription =
          if (state.showPlay) stringResource(R.string.playpause_button_play)
          else stringResource(R.string.playpause_button_pause),
      )
    }
    // [END android_compose_custom_play_pause_button]
  }

  // [START android_compose_custom_content_frame]
  @Composable
  fun ContentFrame(
    player: Player?,
    modifier: Modifier = Modifier,
    surfaceType: @SurfaceType Int = SURFACE_TYPE_SURFACE_VIEW,
    contentScale: ContentScale = ContentScale.Fit,
    keepContentOnReset: Boolean = false,
    shutter: @Composable () -> Unit = { Box(Modifier.fillMaxSize().background(Color.Black)) },
  ) {
    val presentationState = rememberPresentationState(player, keepContentOnReset)
    val scaledModifier =
      modifier.resizeWithContentScale(contentScale, presentationState.videoSizeDp)

    // Always leave PlayerSurface to be part of the Compose tree because it will be initialised in
    // the process. If this composable is guarded by some condition, it might never become visible
    // because the Player won't emit the relevant event, e.g. the first frame being ready.
    PlayerSurface(player, scaledModifier, surfaceType)

    if (presentationState.coverSurface) {
      // Cover the surface that is being prepared with a shutter
      shutter()
    }
  }

  // [END android_compose_custom_content_frame]

  @Composable
  fun MixedPlayerControls(player: Player) {
    // [START android_compose_mixed_player_controls]
    Row {
      // Use prebuilt component from the Media3 UI Compose Material3 library
      PreviousButton(player)
      // Use the scaffold component from Media3 UI Compose library
      PlayPauseButton(player) {
        // `this` is PlayPauseButtonState
        FilledTonalButton(
          onClick = {
            Log.d("PlayPauseButton", "Clicking on play-pause button")
            this.onClick()
          },
          enabled = this.isEnabled,
        ) {
          Icon(
            imageVector = if (showPlay) Icons.Default.PlayArrow else Icons.Default.Pause,
            contentDescription = if (showPlay) "Play" else "Pause",
          )
        }
      }
      // Use prebuilt component from the Media3 UI Compose Material3 library
      NextButton(player)
    }
    // [END android_compose_mixed_player_controls]
  }
}
