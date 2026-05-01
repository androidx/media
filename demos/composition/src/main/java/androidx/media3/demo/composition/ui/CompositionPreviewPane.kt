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

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.composition.CompositionPreviewViewModel
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.data.CompositionPreviewState
import androidx.media3.demo.composition.data.Preset
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
internal fun CompositionPreviewPane(
  onOpenExportOptions: () -> Unit,
  viewModel: CompositionPreviewViewModel,
  uiState: CompositionPreviewState,
  modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()
  var isLayoutDropdownExpanded by remember { mutableStateOf(false) }

  Column(modifier = modifier.fillMaxSize()) {
    Text(
      text =
        stringResource(R.string.preview_composition_title, presetToString(uiState.selectedPreset)),
      fontWeight = FontWeight.Bold,
    )

    @Suppress("UnusedBoxWithConstraintsScope")
    BoxWithConstraints(
      modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
      contentAlignment = Alignment.Center,
    ) {
      // Video Player
      AndroidView(
        factory = { context -> PlayerView(context) },
        update = { playerView ->
          playerView.player = viewModel.compositionPlayer
          playerView.setTimeBarScrubbingEnabled(true)
          playerView.setUseController(true)
          // TODO: b/449957627 - Remove once internal pipeline is migrated to FrameConsumer.
          viewModel.surfaceView = playerView.videoSurfaceView as SurfaceView
        },
        modifier = Modifier.fillMaxSize(),
      )
    }

    HorizontalDivider(
      thickness = 2.dp,
      modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
    )

    Column(
      verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.mini),
      modifier = Modifier.weight(1f).verticalScroll(scrollState),
    ) {
      if (uiState.sequenceTrackTypes.isNotEmpty()) {
        ScrollableTabRow(
          selectedTabIndex = uiState.selectedSequenceIndex,
          edgePadding = 0.dp,
          containerColor = MaterialTheme.colorScheme.surface,
          contentColor = MaterialTheme.colorScheme.primary,
          modifier = Modifier.fillMaxWidth(),
        ) {
          uiState.sequenceTrackTypes.forEachIndexed { index, _ ->
            Tab(
              selected = uiState.selectedSequenceIndex == index,
              onClick = { viewModel.onSequenceSelected(index) },
              text = { Text(text = stringResource(R.string.sequence_label, index + 1)) },
            )
          }
          // Add Sequence button
          Tab(
            selected = false,
            onClick = { viewModel.addSequence() },
            text = {
              Icon(
                painterResource(R.drawable.add),
                contentDescription = stringResource(R.string.add_sequence),
              )
            },
          )
        }
        SequencePane(
          sequenceIndex = uiState.selectedSequenceIndex,
          trackTypes = uiState.sequenceTrackTypes[uiState.selectedSequenceIndex],
          selectedItems =
            uiState.mediaState.selectedItemsBySequence.getOrNull(uiState.selectedSequenceIndex)
              ?: emptyList(),
          availableItems = uiState.mediaState.availableItems,
          availableEffects = uiState.mediaState.availableEffects,
          isEnabled = true,
          onTrackTypeChanged = viewModel::onSequenceTrackTypeChanged,
          onAddItem = viewModel::addItem,
          onRemoveItem = viewModel::removeItem,
          onUpdateEffects = viewModel::updateEffectsForItem,
          onAddLocalItem = viewModel::addLocalItem,
          onRemoveSequence = viewModel::removeSequence,
          onAddGap = viewModel::addGap,
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
      ) {
        Text(text = stringResource(R.string.presets), modifier = Modifier.textPadding())
        DropDownSpinner(
          isDropDownOpen = isLayoutDropdownExpanded,
          selectedOption = uiState.selectedPreset,
          dropDownOptions = viewModel.compositionLayouts,
          changeDropDownOpen = { isLayoutDropdownExpanded = it },
          changeSelectedOption = { newSelection ->
            viewModel.onPresetSelected(newSelection)
            isLayoutDropdownExpanded = false
          },
          labelProvider = { preset -> presetToString(preset) },
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(R.string.frame_consumer_enabled),
          modifier = Modifier.textPadding(),
        )
        Switch(
          checked = uiState.outputSettingsState.frameConsumerEnabled,
          onCheckedChange = { isEnabled -> viewModel.onFrameConsumerEnabledChanged(isEnabled) },
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text(
          text = stringResource(R.string.add_background_audio),
          modifier = Modifier.textPadding(),
        )
        Switch(
          checked = uiState.outputSettingsState.includeBackgroundAudio,
          onCheckedChange = { isEnabled -> viewModel.onIncludeBackgroundAudioChanged(isEnabled) },
        )
      }

      OutputSettings(
        outputSettings = uiState.outputSettingsState,
        onResolutionChanged = viewModel::onOutputResolutionChanged,
        onHdrModeChanged = viewModel::onHdrModeChanged,
      )
    }

    HorizontalDivider(
      thickness = 2.dp,
      modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
    )

    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.small),
      horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
      Button(onClick = { viewModel.setComposition() }) {
        Text(text = stringResource(R.string.set_composition))
      }
      Button(onClick = { viewModel.play() }, enabled = uiState.isCompositionSet) {
        Text(text = stringResource(R.string.play))
      }
      Button(onClick = onOpenExportOptions) {
        Text(text = stringResource(R.string.export_settings))
      }
    }
  }
}

@Composable
private fun presetToString(preset: Preset): String {
  return when (preset) {
    Preset.SEQUENCE -> stringResource(R.string.preset_sequence)
    Preset.GRID -> stringResource(R.string.preset_grid)
    Preset.PIP -> stringResource(R.string.preset_pip)
    Preset.CUSTOM -> stringResource(R.string.preset_custom)
  }
}
