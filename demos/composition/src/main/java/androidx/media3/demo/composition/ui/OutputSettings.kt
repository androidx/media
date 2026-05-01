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
package androidx.media3.demo.composition.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.HDR_MODE_DESCRIPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.RESOLUTION_HEIGHTS
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.data.OutputSettingsState
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding
import androidx.media3.transformer.Composition

@OptIn(UnstableApi::class)
@Composable
internal fun OutputSettings(
  outputSettings: OutputSettingsState,
  onResolutionChanged: (String) -> Unit,
  onHdrModeChanged: (Int) -> Unit,
) {
  var resolutionExpanded by remember { mutableStateOf(false) }
  var hdrExpanded by remember { mutableStateOf(false) }
  val selectedHdrKey =
    HDR_MODE_DESCRIPTIONS.entries.find { it.value == outputSettings.hdrMode }?.key

  Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = stringResource(R.string.output_video_resolution),
        modifier = Modifier.textPadding(),
      )
      DropDownSpinner(
        isDropDownOpen = resolutionExpanded,
        selectedOption = outputSettings.resolutionHeight,
        dropDownOptions = RESOLUTION_HEIGHTS,
        changeDropDownOpen = { newExpanded -> resolutionExpanded = newExpanded },
        changeSelectedOption = { newSelection -> onResolutionChanged(newSelection) },
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(text = stringResource(R.string.hdr_mode), modifier = Modifier.textPadding())
      DropDownSpinner(
        isDropDownOpen = hdrExpanded,
        selectedOption = selectedHdrKey ?: HDR_MODE_DESCRIPTIONS.keys.first(),
        dropDownOptions = HDR_MODE_DESCRIPTIONS.keys.toList(),
        changeDropDownOpen = { newExpanded -> hdrExpanded = newExpanded },
        changeSelectedOption = { newSelection ->
          val newMode = HDR_MODE_DESCRIPTIONS[newSelection] ?: Composition.HDR_MODE_KEEP_HDR
          onHdrModeChanged(newMode)
        },
      )
    }
  }
}
