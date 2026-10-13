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

import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.demo.compose.viewmodel.findActivity

/**
 * Forces landscape orientation and hides the system bars while [isFullscreen], even if the device
 * isn't rotated. Otherwise, shows the system bars and keeps phones in portrait, which the layout
 * outside fullscreen is designed for. Both are released when leaving the composition.
 */
@Composable
internal fun FullscreenEffect(isFullscreen: Boolean) {
  val activity = LocalContext.current.findActivity() ?: return
  val isPhone = LocalConfiguration.current.smallestScreenWidthDp < 600
  LaunchedEffect(activity, isFullscreen, isPhone) {
    activity.requestedOrientation =
      when {
        isFullscreen -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        isPhone -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      }
    val insetsController =
      WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    if (isFullscreen) {
      insetsController.systemBarsBehavior =
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
      insetsController.hide(WindowInsetsCompat.Type.systemBars())
    } else {
      insetsController.show(WindowInsetsCompat.Type.systemBars())
    }
  }
  DisposableEffect(activity) {
    onDispose {
      activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
      WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        .show(WindowInsetsCompat.Type.systemBars())
    }
  }
}
