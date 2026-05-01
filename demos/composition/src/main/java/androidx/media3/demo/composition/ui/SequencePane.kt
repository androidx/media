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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.demo.composition.CompositionPreviewViewModel.Companion.MEDIA_TYPES
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.data.Gap
import androidx.media3.demo.composition.data.Item
import androidx.media3.demo.composition.data.Media
import androidx.media3.demo.composition.ui.theme.spacing
import androidx.media3.demo.composition.ui.theme.textPadding

@Composable
internal fun SequencePane(
  sequenceIndex: Int,
  trackTypes: Set<Int>,
  selectedItems: List<Item>,
  availableItems: List<Item>,
  availableEffects: List<String>,
  isEnabled: Boolean,
  onTrackTypeChanged: (Int, Set<Int>) -> Unit,
  onAddItem: (Int, Int) -> Unit,
  onRemoveItem: (Int, Int) -> Unit,
  onUpdateEffects: (sequenceIndex: Int, itemIndex: Int, effects: Set<String>) -> Unit,
  onAddLocalItem: (Int, Uri) -> Unit,
  onRemoveSequence: (Int) -> Unit,
  onAddGap: (Int, Long) -> Unit,
) {
  var selectedMediaItemIndex by remember { mutableStateOf<Int?>(null) }
  var showEditMediaItemsDialog by remember { mutableStateOf(false) }
  var showGapDurationDialog by remember { mutableStateOf(false) }

  val filePickerLauncher =
    rememberLauncherForActivityResult(contract = ActivityResultContracts.OpenDocument()) { uri: Uri?
      ->
      uri?.let { onAddLocalItem(sequenceIndex, it) }
    }

  if (showEditMediaItemsDialog) {
    VideoSequenceDialog(
      onDismissRequest = { showEditMediaItemsDialog = false },
      itemOptions = availableItems,
      addSelectedVideo = { itemIndex -> onAddItem(sequenceIndex, itemIndex) },
    )
  }

  if (showGapDurationDialog) {
    GapDurationDialog(
      onDismissRequest = { showGapDurationDialog = false },
      onConfirm = { durationUs ->
        onAddGap(sequenceIndex, durationUs)
        showGapDurationDialog = false
      },
    )
  }

  selectedMediaItemIndex?.let { itemIndex ->
    val item = selectedItems[itemIndex]
    if (item is Media) {
      EffectSelectionDialog(
        onDismissRequest = { selectedMediaItemIndex = null },
        effectOptions = availableEffects,
        currentSelections = item.selectedEffects,
        onEffectsSelected = { newEffects -> onUpdateEffects(sequenceIndex, itemIndex, newEffects) },
      )
    } else {
      selectedMediaItemIndex = null
    }
  }

  Column(
    modifier =
      Modifier.padding(vertical = 4.dp)
        .border(2.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(16.dp))
        .padding(MaterialTheme.spacing.small)
  ) {
    // Track Settings
    Row(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = stringResource(R.string.sequence_label, sequenceIndex + 1),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.weight(1f),
      )
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = trackTypes.contains(C.TRACK_TYPE_AUDIO),
          onCheckedChange = { checked ->
            val newTypes =
              if (checked) trackTypes + C.TRACK_TYPE_AUDIO else trackTypes - C.TRACK_TYPE_AUDIO
            onTrackTypeChanged(sequenceIndex, newTypes)
          },
          enabled = isEnabled && !(trackTypes.contains(C.TRACK_TYPE_AUDIO) && trackTypes.size == 1),
        )
        Text(text = stringResource(R.string.audio))
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = trackTypes.contains(C.TRACK_TYPE_VIDEO),
          onCheckedChange = { checked ->
            val newTypes =
              if (checked) trackTypes + C.TRACK_TYPE_VIDEO else trackTypes - C.TRACK_TYPE_VIDEO
            onTrackTypeChanged(sequenceIndex, newTypes)
          },
          enabled = isEnabled && !(trackTypes.contains(C.TRACK_TYPE_VIDEO) && trackTypes.size == 1),
        )
        Text(text = stringResource(R.string.video))
      }
    }

    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)

    // Media Items List
    Text(
      text = stringResource(R.string.add_effects_hint),
      fontSize = 12.sp,
      fontStyle = FontStyle.Italic,
      modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 4.dp),
    )

    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
      itemsIndexed(selectedItems) { index, item ->
        Row(
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
          modifier =
            Modifier.fillMaxWidth().clickable(enabled = isEnabled && item is Media) {
              selectedMediaItemIndex = index
            },
        ) {
          Column(modifier = Modifier.textPadding().weight(1f)) {
            when (item) {
              is Gap -> {
                Text(
                  text =
                    stringResource(
                      R.string.item_index_label,
                      index + 1,
                      stringResource(R.string.gap_label),
                    )
                )
                Text(
                  text = stringResource(R.string.duration_label, item.durationUs / 1_000_000f),
                  fontSize = 12.sp,
                  fontStyle = FontStyle.Italic,
                  color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
              }
              is Media -> {
                Text(text = stringResource(R.string.item_index_label, index + 1, item.title))
                val effectsText =
                  item.selectedEffects.joinToString().ifEmpty { stringResource(R.string.none) }
                Text(
                  text = stringResource(R.string.effect_label, effectsText),
                  fontSize = 12.sp,
                  fontStyle = FontStyle.Italic,
                  color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                )
              }
            }
          }
          IconButton({ onRemoveItem(sequenceIndex, index) }, enabled = isEnabled) {
            Icon(
              painterResource(R.drawable.delete),
              contentDescription = stringResource(R.string.remove_item, index + 1),
            )
          }
        }
      }
    }

    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.secondary)

    Column(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ElevatedButton(
          onClick = { showEditMediaItemsDialog = true },
          enabled = isEnabled,
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        ) {
          Text(text = stringResource(R.string.add_remote_file))
        }
        ElevatedButton(
          onClick = { filePickerLauncher.launch(MEDIA_TYPES) },
          enabled = isEnabled,
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        ) {
          Text(text = stringResource(R.string.add_local_file))
        }
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ElevatedButton(
          onClick = { showGapDurationDialog = true },
          enabled = isEnabled,
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        ) {
          Text(text = stringResource(R.string.add_gap))
        }
        ElevatedButton(
          onClick = { onRemoveSequence(sequenceIndex) },
          enabled = isEnabled,
          modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        ) {
          Text(text = stringResource(R.string.delete_sequence))
        }
      }
    }
  }
}
