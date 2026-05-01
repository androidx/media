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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MUXER_OPTIONS
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.SAME_AS_INPUT_OPTION
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.data.ExportState
import androidx.media3.demo.composition.data.OutputSettingsState
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding

@OptIn(UnstableApi::class)
@Composable
internal fun ExportOptions(
  outputSettings: OutputSettingsState,
  exportState: ExportState,
  isDebugTracingEnabled: Boolean,
  onAudioMimeTypeChanged: (String) -> Unit,
  onVideoMimeTypeChanged: (String) -> Unit,
  onMuxerOptionChanged: (String) -> Unit,
  onDebugTracingChanged: (Boolean) -> Unit,
  onExport: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var isAudioTypeExpanded by remember { mutableStateOf(false) }
  var isVideoTypeExpanded by remember { mutableStateOf(false) }
  Column(
    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
    modifier = modifier,
  ) {
    Text(text = stringResource(R.string.export_settings), fontWeight = FontWeight.Bold)
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = stringResource(R.string.output_audio_mime_type),
        modifier = Modifier.textPadding(),
      )
      DropDownSpinner(
        isDropDownOpen = isAudioTypeExpanded,
        selectedOption = outputSettings.audioMimeType,
        dropDownOptions =
          listOf(
            SAME_AS_INPUT_OPTION,
            MimeTypes.AUDIO_AAC,
            MimeTypes.AUDIO_AMR_NB,
            MimeTypes.AUDIO_AMR_WB,
          ),
        changeDropDownOpen = { expanded -> isAudioTypeExpanded = expanded },
        changeSelectedOption = onAudioMimeTypeChanged,
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(
        text = stringResource(R.string.output_video_mime_type),
        modifier = Modifier.textPadding(),
      )
      DropDownSpinner(
        isDropDownOpen = isVideoTypeExpanded,
        selectedOption = outputSettings.videoMimeType,
        dropDownOptions =
          listOf(
            SAME_AS_INPUT_OPTION,
            MimeTypes.VIDEO_H263,
            MimeTypes.VIDEO_H264,
            MimeTypes.VIDEO_H265,
            MimeTypes.VIDEO_MP4V,
            MimeTypes.VIDEO_AV1,
            MimeTypes.VIDEO_APV,
            MimeTypes.VIDEO_DOLBY_VISION,
          ),
        changeDropDownOpen = { expanded -> isVideoTypeExpanded = expanded },
        changeSelectedOption = onVideoMimeTypeChanged,
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Text(text = stringResource(R.string.enable_debug_tracing), modifier = Modifier.textPadding())
      Switch(
        checked = isDebugTracingEnabled,
        onCheckedChange = { checked -> onDebugTracingChanged(checked) },
      )
    }
    Column(Modifier.selectableGroup()) {
      MUXER_OPTIONS.forEach { text ->
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier =
            Modifier.selectable(
                selected = text == outputSettings.muxerOption,
                onClick = { onMuxerOptionChanged(text) },
                role = Role.RadioButton,
              )
              .fillMaxWidth(),
        ) {
          Text(text = text, modifier = Modifier.textPadding())
          RadioButton(selected = (text == outputSettings.muxerOption), onClick = null)
        }
      }
    }
    HorizontalDivider(
      thickness = 2.dp,
      modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
    )
    Row(modifier = Modifier.fillMaxWidth().padding(MaterialTheme.spacing.small, 0.dp)) {
      OutlinedButton({ onCancel() }) { Text(text = stringResource(R.string.cancel)) }
      Spacer(Modifier.weight(1f))
      Button(onClick = onExport, enabled = !exportState.isExporting) {
        Text(text = stringResource(R.string.export))
      }
    }
    if (exportState.isExporting || exportState.exportResultInfo != null) {
      HorizontalDivider(
        thickness = 2.dp,
        modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
      )
    }
    if (exportState.isExporting) {
      LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
    }
    exportState.exportResultInfo?.let { Text(text = it) }
  }
}
