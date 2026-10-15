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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.demo.compose.R
import androidx.media3.demo.compose.layout.ZoomMode
import androidx.media3.demo.compose.layout.ZoomState

/** Shows the [ZoomMode] or zoom level of a [ZoomState] while and shortly after it changes. */
@Composable
internal fun ZoomOverlay(state: ZoomState, modifier: Modifier = Modifier) {
  AnimatedVisibility(
    visible = state.isIndicatorVisible,
    enter = fadeIn(),
    exit = fadeOut(),
    modifier = modifier,
  ) {
    Text(
      text =
        when (state.mode) {
          ZoomMode.ORIGINAL -> stringResource(R.string.zoom_original)
          ZoomMode.FILL -> stringResource(R.string.zoom_fill)
          ZoomMode.FREE -> stringResource(R.string.zoom_level, state.zoom)
        },
      modifier =
        Modifier.clip(RoundedCornerShape(16.dp))
          .background(MaterialTheme.colorScheme.primaryContainer)
          // Animates the size of the indicator from its center when the text changes, clipping the
          // text while the indicator is narrower than it.
          .animateContentSize(alignment = Alignment.Center)
          .padding(horizontal = 12.dp, vertical = 6.dp),
      color = MaterialTheme.colorScheme.onPrimaryContainer,
      style = MaterialTheme.typography.titleMedium,
    )
  }
}
