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

package androidx.media3.demo.compose.buttons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.compose.material3.R
import androidx.media3.ui.compose.state.observeState

@Composable
internal fun CcButton(player: Player?, modifier: Modifier = Modifier) {
  var isCcEnabled by
    remember(player) {
      mutableStateOf(player?.currentTracks?.isTypeSelected(C.TRACK_TYPE_TEXT) ?: false)
    }
  var hasTextTrack by
    remember(player) {
      mutableStateOf(player?.currentTracks?.containsType(C.TRACK_TYPE_TEXT) ?: false)
    }

  val observer =
    remember(player) {
      player?.observeState(Player.EVENT_TRACKS_CHANGED) {
        isCcEnabled = player.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)
        hasTextTrack = player.currentTracks.containsType(C.TRACK_TYPE_TEXT)
      }
    }

  LaunchedEffect(observer) { observer?.observe() }

  IconButton(
    onClick = {
      player?.let {
        val currentlyTextSelected = it.currentTracks.isTypeSelected(C.TRACK_TYPE_TEXT)
        it.trackSelectionParameters =
          it.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, currentlyTextSelected)
            .setSelectTextByDefault(!currentlyTextSelected)
            .build()
      }
    },
    modifier = modifier,
    enabled = hasTextTrack,
    colors = IconButtonDefaults.iconButtonColors(),
  ) {
    Icon(
      modifier = Modifier.size(24.dp),
      painter =
        painterResource(
          if (isCcEnabled) R.drawable.media3_icon_subtitles
          else R.drawable.media3_icon_subtitles_off
        ),
      contentDescription = if (isCcEnabled) "Disable closed captions" else "Enable closed captions",
    )
  }
}
