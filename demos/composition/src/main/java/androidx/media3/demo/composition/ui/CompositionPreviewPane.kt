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

import android.os.Build.VERSION.SDK_INT
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.composition.CompositionPreviewViewModel
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.data.CompositionPreviewState
import androidx.media3.demo.composition.data.Preset
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding
import androidx.media3.ui.compose.material3.Player
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val CONTROLS_VISIBILITY_TIMEOUT_MS = 3000L

private class JobHolder(var job: Job? = null)

@OptIn(UnstableApi::class)
@Composable
internal fun CompositionPreviewPane(
  onOpenExportOptions: () -> Unit,
  viewModel: CompositionPreviewViewModel,
  uiState: CompositionPreviewState,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val scrollState = rememberScrollState()
  var isLayoutDropdownExpanded by remember { mutableStateOf(false) }

  var showControls by rememberSaveable { mutableStateOf(true) }
  var anyPointerDown by remember { mutableStateOf(false) }
  val hideJobHolder = remember { JobHolder() }

  fun scheduleHideControls() {
    hideJobHolder.job?.cancel()
    if (!anyPointerDown) {
      hideJobHolder.job = scope.launch {
        delay(CONTROLS_VISIBILITY_TIMEOUT_MS)
        showControls = false
      }
    }
  }

  LaunchedEffect(showControls, anyPointerDown) {
    if (showControls && !anyPointerDown) {
      scheduleHideControls()
    } else {
      hideJobHolder.job?.cancel()
    }
  }

  Column(modifier = modifier.fillMaxSize()) {
    Text(
      text =
        stringResource(R.string.preview_composition_title, presetToString(uiState.selectedPreset)),
      fontWeight = FontWeight.Bold,
    )

    Box(
      modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp),
      contentAlignment = Alignment.Center,
    ) {
      // Video Player
      Player(
        player = viewModel.compositionPlayer,
        showControls = showControls,
        modifier =
          Modifier.fillMaxSize()
            .playerGestures(
              onPointerDownChange = { anyPointerDown = it },
              onPointerMove = {
                showControls = true
                scheduleHideControls()
              },
              onToggleControls = { showControls = !showControls },
            ),
      )

      // FPS Tracker Overlay
      uiState.currentFps?.let { fps ->
        if (fps > 0f) {
          Text(
            text = stringResource(R.string.playback_fps, fps),
            color = Color.White,
            modifier =
              Modifier.align(Alignment.TopEnd)
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), shape = RoundedCornerShape(4.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
          )
        }
      }
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
        PrimaryScrollableTabRow(
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
          onUpdateMediaItem = viewModel::updateMediaItem,
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
        Column {
          Text(
            text = stringResource(R.string.frame_processor_enabled),
            modifier = Modifier.textPadding(),
          )
          if (SDK_INT < 28) {
            Text(
              text = stringResource(R.string.api_28_required_frame_processor),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.outline,
              modifier = Modifier.textPadding(),
            )
          }
        }
        Switch(
          checked = uiState.outputSettingsState.frameProcessorEnabled,
          onCheckedChange = { isEnabled -> viewModel.onFrameProcessorEnabledChanged(isEnabled) },
          enabled = SDK_INT >= 28,
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
        onFrameAggregationFpsChanged = viewModel::onFrameAggregationFpsChanged,
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
    Preset.AV_SPLIT_REPRO -> stringResource(R.string.preset_av_split_repro)
    Preset.CUSTOM -> stringResource(R.string.preset_custom)
  }
}

private fun Modifier.playerGestures(
  onPointerDownChange: (Boolean) -> Unit,
  onPointerMove: () -> Unit,
  onToggleControls: () -> Unit,
): Modifier =
  this.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent()
          val isAnyPressed = event.changes.any { it.pressed }
          onPointerDownChange(isAnyPressed)
          if (event.type == PointerEventType.Move) {
            onPointerMove()
          }
        }
      }
    }
    .pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) }
