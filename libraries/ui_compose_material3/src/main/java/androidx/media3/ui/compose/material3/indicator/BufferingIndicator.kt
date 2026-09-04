/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.media3.ui.compose.material3.indicator

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.state.DisplayMode
import androidx.media3.ui.compose.state.SHOW_BUFFERING_ALWAYS
import androidx.media3.ui.compose.state.SHOW_BUFFERING_WHEN_PLAYING
import androidx.media3.ui.compose.state.rememberPlaybackState
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * A circular progress indicator that displays an animated spinner when the [Player] is buffering.
 *
 * @param player The [Player] to observe.
 * @param modifier The [Modifier] to be applied to the buffering indicator.
 * @param displayMode The [DisplayMode] specifying when to show buffering. Defaults to
 *   [SHOW_BUFFERING_ALWAYS].
 * @param delay The delay duration before the buffering indicator is shown.
 */
@UnstableApi
@Composable
fun BufferingIndicator(
  player: Player?,
  modifier: Modifier = Modifier,
  displayMode: @DisplayMode Int = SHOW_BUFFERING_ALWAYS,
  delay: Duration = 500.milliseconds,
  content: @Composable () -> Unit = { CircularProgressIndicator() },
) {
  val playbackState = rememberPlaybackState(player)
  val shouldShowBuffering =
    playbackState.playbackState == Player.STATE_BUFFERING &&
      (((displayMode and SHOW_BUFFERING_ALWAYS) != 0) ||
        (((displayMode and SHOW_BUFFERING_WHEN_PLAYING) != 0) &&
          playbackState.playWhenReady &&
          playbackState.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE))

  var isVisible by remember { mutableStateOf(false) }

  LaunchedEffect(shouldShowBuffering) {
    if (shouldShowBuffering) {
      if (delay > Duration.ZERO) {
        delay(delay)
      }
      isVisible = true
    } else {
      isVisible = false
    }
  }

  AnimatedVisibility(visible = isVisible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
    content()
  }
}
