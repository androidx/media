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
import androidx.media3.ui.compose.state.rememberSeekBackButtonState

@Composable
internal fun SeekBackButton(player: Player, modifier: Modifier = Modifier) {
  val state = rememberSeekBackButtonState(player)
  Icon(
    painter = painterResource(seekBackIconId(state.seekBackAmountMs)),
    seekBackContentDescription(state.seekBackAmountMs),
    modifier = modifier.clickable(enabled = state.isEnabled, onClick = state::onClick),
  )
}

private fun seekBackIconId(seekBackAmountMs: Long): Int {
  return when (seekBackAmountMs) {
    in 2500..7500 -> R.drawable.media3_icon_skip_back_5
    in 7500..12500 -> R.drawable.media3_icon_skip_back_10
    in 12500..20000 -> R.drawable.media3_icon_skip_back_15
    in 20000..40000 -> R.drawable.media3_icon_skip_back_30
    else -> R.drawable.media3_icon_skip_back
  }
}

@Composable
private fun seekBackContentDescription(seekBackAmountMs: Long): String {
  return when (seekBackAmountMs) {
    in 2500..7500 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 5)
    in 7500..12500 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 10)
    in 12500..20000 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 15)
    in 20000..40000 -> pluralStringResource(R.plurals.seek_back_by_amount_button, count = 30)
    else -> stringResource(R.string.seek_back_button)
  }
}
