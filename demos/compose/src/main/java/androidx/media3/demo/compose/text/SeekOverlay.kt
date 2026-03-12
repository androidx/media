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

package androidx.media3.demo.compose.text

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.Util
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class SeekOverlayState(private val scope: CoroutineScope) {
  var visible by mutableStateOf(false)
    private set

  /** The seek amount in milliseconds (negative for seek back, positive for seek forward) */
  var seekAmountMs by mutableLongStateOf(0L)
    private set

  private var hideJob: Job? = null

  fun show(seekAmountMs: Long, durationMillis: Long = 1000L) {
    // Note, this will always show seekAmountMs, unlike YouTube that adds seekAmountMs * taps
    this.seekAmountMs = seekAmountMs
    visible = true
    hideJob?.cancel()
    hideJob =
      scope.launch {
        delay(durationMillis)
        visible = false
      }
  }
}

@Composable
internal fun SeekOverlay(state: SeekOverlayState, modifier: Modifier = Modifier) {
  AnimatedVisibility(
    visible = state.visible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    Box(
      modifier =
        Modifier.background(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            shape = RoundedCornerShape(16.dp),
          )
          .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
      Text(
        text = Util.formatInvariant("%+d", state.seekAmountMs / 1000), // always includes a sign
        style = TextStyle(color = MaterialTheme.colorScheme.primary, fontSize = 40.sp),
      )
    }
  }
}
