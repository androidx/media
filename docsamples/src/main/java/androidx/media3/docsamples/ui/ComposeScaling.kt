/*
 * Copyright 2026 The Android Open Source Project
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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue", "DEPRECATION")

package androidx.media3.docsamples.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalMediaQueryApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.derivedMediaQuery
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.Player
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.buttons.MuteButton
import androidx.window.core.layout.WindowSizeClass

@androidx.annotation.OptIn(UnstableApi::class, ExperimentalApi::class)
class ComposeScaling {

  // [START android_media3_compose_scaling_non_immersive_insets]
  @Composable
  fun NonImmersiveVideoPlayer(
    player: Player,
    showControls: Boolean,
    modifier: Modifier = Modifier,
  ) {
    Player(
      player = player,
      modifier = modifier,
      showControls = showControls,
      // Keep top controls clear of the status bar, caption bar, and display cutout
      topControls = {
        PlayerDefaults.TopControls(
          player = player,
          modifier =
            Modifier.fillMaxWidth()
              .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
              ),
        ) {
          MuteButton(player = player, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
      },
      // Keep bottom controls clear of the navigation bar and display cutout
      bottomControls = {
        PlayerDefaults.BottomControls(
          player = player,
          modifier =
            Modifier.fillMaxWidth()
              .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                  WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
              ),
        )
      },
    )
  }

  // [END android_media3_compose_scaling_non_immersive_insets]

  // [START android_media3_compose_scaling_immersive_insets]
  @Composable
  fun ImmersiveVideoPlayer(player: Player, showControls: Boolean, modifier: Modifier = Modifier) {
    val view = LocalView.current

    // Manages the system UI lifecycle to create an edge-to-edge cinematic experience
    DisposableEffect(view) {
      // Finds the hosting Activity, unwrapping any ContextWrapper around the view's context
      val activity =
        generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
          .filterIsInstance<Activity>()
          .firstOrNull()
      val window = activity?.window ?: return@DisposableEffect onDispose {}
      val controller = WindowCompat.getInsetsController(window, view)

      // Records the current system bar state so it can be restored on dispose
      val insets = ViewCompat.getRootWindowInsets(view)
      val wereSystemBarsVisible =
        insets == null ||
          insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
          insets.isVisible(WindowInsetsCompat.Type.navigationBars())
      val previousBehavior = controller.systemBarsBehavior

      // Hides status bars, navigation bars, and caption bars to maximize the video canvas
      controller.hide(WindowInsetsCompat.Type.systemBars())

      // Allows hidden system bars to temporarily reappear with a swipe gesture from the edge
      controller.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

      onDispose {
        // Restores the previous system bar state when leaving the composable
        controller.systemBarsBehavior = previousBehavior
        if (wereSystemBarsVisible) {
          controller.show(WindowInsetsCompat.Type.systemBars())
        }
      }
    }

    Player(
      player = player,
      modifier = modifier,
      showControls = showControls,
      // Keep top controls clear of the display cutout while the system bars are hidden
      topControls = {
        PlayerDefaults.TopControls(
          player = player,
          modifier =
            Modifier.fillMaxWidth()
              .windowInsetsPadding(
                WindowInsets.displayCutout.only(
                  WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                )
              ),
        ) {
          MuteButton(player = player, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))
        }
      },
      // Keep bottom controls clear of the display cutout and system gesture areas
      bottomControls = {
        PlayerDefaults.BottomControls(
          player = player,
          modifier =
            Modifier.fillMaxWidth()
              .windowInsetsPadding(
                WindowInsets.displayCutout
                  .union(WindowInsets.systemGestures)
                  .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
              ),
        )
      },
    )
  }

  // [END android_media3_compose_scaling_immersive_insets]

  // [START android_media3_compose_scaling_derived_media_query]
  @OptIn(ExperimentalMediaQueryApi::class)
  @Composable
  fun AdaptiveVideoPlayerScreen(
    player: Player,
    modifier: Modifier = Modifier,
    relatedContent: @Composable () -> Unit,
  ) {
    val isWideWindow by derivedMediaQuery {
      windowWidth >= WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND.dp
    }

    if (isWideWindow) {
      // Medium or expanded width: place the player alongside a details or playlist pane
      Row(modifier = modifier.fillMaxSize()) {
        Player(player = player, modifier = Modifier.weight(0.65f).fillMaxHeight())
        Box(modifier = Modifier.weight(0.35f).fillMaxHeight()) { relatedContent() }
      }
    } else {
      // Compact width: stack a 16:9 player above the details pane
      Column(modifier = modifier.fillMaxSize()) {
        Player(player = player, modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { relatedContent() }
      }
    }
  }

  // [END android_media3_compose_scaling_derived_media_query]

  // [START android_media3_compose_scaling_player_fit]
  @Composable
  fun FitVideoPlayer(player: Player, modifier: Modifier = Modifier) {
    Player(
      player = player,
      modifier = modifier.fillMaxSize(), // Fill the available space
      contentScale = ContentScale.Fit, // Default; shown for clarity
    )
  }

  // [END android_media3_compose_scaling_player_fit]

  // [START android_media3_compose_scaling_dynamic_toggle]
  @Composable
  fun ZoomableVideoPlayer(player: Player, modifier: Modifier = Modifier) {
    var isCropped by rememberSaveable { mutableStateOf(false) }

    Player(
      player = player,
      modifier =
        modifier
          .fillMaxSize()
          // Handle double-tap to toggle between Fit and Crop scaling
          .pointerInput(Unit) { detectTapGestures(onDoubleTap = { isCropped = !isCropped }) },
      contentScale = if (isCropped) ContentScale.Crop else ContentScale.Fit,
    )
  }

  // [END android_media3_compose_scaling_dynamic_toggle]
}
