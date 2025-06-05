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

package androidx.media3.demo.compose.buttons

import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.media3.common.Player
import androidx.media3.demo.compose.R
import androidx.media3.ui.compose.state.rememberSeekForwardButtonState

@Composable
internal fun SeekForwardButton(player: Player, modifier: Modifier = Modifier) {
  val state = rememberSeekForwardButtonState(player)
  Icon(
    painter = painterResource(seekForwardIconId(state.seekForwardAmountMs)),
    seekForwardContentDescription(state.seekForwardAmountMs),
    modifier = modifier.clickable(enabled = state.isEnabled, onClick = state::onClick),
  )
}

private fun seekForwardIconId(seekForwardAmountMs: Long): Int {
  return when (seekForwardAmountMs) {
    in 2500..7500 -> R.drawable.media3_icon_skip_forward_5
    in 7500..12500 -> R.drawable.media3_icon_skip_forward_10
    in 12500..20000 -> R.drawable.media3_icon_skip_forward_15
    in 20000..40000 -> R.drawable.media3_icon_skip_forward_30
    else -> R.drawable.media3_icon_skip_forward
  }
}

@Composable
private fun seekForwardContentDescription(seekForwardAmountMs: Long): String {
  return when (seekForwardAmountMs) {
    in 2500..7500 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 5)
    in 7500..12500 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 10)
    in 12500..20000 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 15)
    in 20000..40000 -> pluralStringResource(R.plurals.seek_forward_by_amount_button, count = 30)
    else -> stringResource(R.string.seek_forward_button)
  }
}
