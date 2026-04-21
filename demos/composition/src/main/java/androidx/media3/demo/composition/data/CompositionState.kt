/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.demo.composition.data

import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Size
import androidx.media3.common.util.UnstableApi

/**
 * Represents the single source of truth for the Composition Preview screen's UI state.
 *
 * This class aggregates all other state objects into one comprehensive model, following the
 * Unidirectional Data Flow (UDF) pattern.
 *
 * @param sequenceTrackTypes The list of track types for each sequence.
 * @param snackbarMessage A message to be shown in the snackbar.
 * @param isDebugTracingEnabled Whether debug tracing for media transformations is enabled.
 * @param mediaState The state for media items and their effects.
 * @param outputSettingsState The state for all output settings.
 * @param exportState The state for the current export process.
 * @param isCompositionSet Whether the composition is set.
 * @param selectedPreset a key representing the selected preset.
 * @param selectedSequenceIndex The selected sequence index.
 */
@OptIn(UnstableApi::class)
data class CompositionPreviewState(
  val sequenceTrackTypes: List<Set<Int>> = emptyList(),
  val snackbarMessage: String? = null,
  val isDebugTracingEnabled: Boolean = false,
  val mediaState: MediaState,
  val outputSettingsState: OutputSettingsState,
  val exportState: ExportState,
  val isCompositionSet: Boolean = false,
  val selectedPreset: Preset,
  val selectedSequenceIndex: Int = 0,
)

/**
 * Holds the state related to media items and their effects.
 *
 * @param availableItems The list of all possible media items the user can add.
 * @param selectedItemsBySequence The list of media items currently on the editing timeline
 *   organized by sequence.
 * @param availableEffects The list of all available effects the user can apply.
 */
data class MediaState(
  val availableItems: List<Item> = emptyList(),
  val selectedItemsBySequence: List<List<Item>> = emptyList(),
  val availableEffects: List<String> = emptyList(),
)

/**
 * Represents either a gap or media, that can be added to the composition.
 *
 * @param durationUs The duration of the item in microseconds.
 */
sealed interface Item {
  val durationUs: Long

  fun copy(): Item
}

/**
 * Represents a single media item, such as a video or image, that can be added to the composition.
 *
 * @param durationUs The duration of the media item in microseconds.
 * @param title The display name of the media item.
 * @param uri The URI string pointing to the media content.
 * @param selectedEffects The set of effect names currently applied to this item.
 */
data class Media(
  override val durationUs: Long,
  val title: String,
  val uri: String,
  val selectedEffects: Set<String> = emptySet(),
) : Item {
  override fun copy(): Item =
    copy(durationUs = durationUs, title = title, uri = uri, selectedEffects = selectedEffects)
}

/**
 * Represents a gap media item, that can be added to the composition.
 *
 * @param durationUs The duration of the gap in microseconds.
 */
data class Gap(override val durationUs: Long) : Item {
  override fun copy(): Item = copy(durationUs = durationUs)
}

/**
 * Holds the state for all user-configurable output and export settings.
 *
 * @param includeBackgroundAudio Whether to include a background audio track.
 * @param resolutionHeight The target output resolution height in pixels.
 * @param hdrMode The selected HDR handling mode.
 * @param audioMimeType The target audio MIME type for the export.
 * @param videoMimeType The target video MIME type for the export.
 * @param muxerOption The selected muxer implementation for the export.
 * @param renderSize The actual size of the render surface in UI pixels.
 */
@OptIn(UnstableApi::class)
data class OutputSettingsState(
  val frameConsumerEnabled: Boolean = false,
  val includeBackgroundAudio: Boolean = false,
  val resolutionHeight: String,
  val hdrMode: Int,
  val audioMimeType: String,
  val videoMimeType: String,
  val muxerOption: String,
  val renderSize: Size = Size.Zero,
)

/**
 * Holds the state of the current export process.
 *
 * @param isExporting True if an export is currently in progress.
 * @param exportResultInfo A message describing the result of the export.
 */
data class ExportState(val isExporting: Boolean = false, val exportResultInfo: String? = null)

/** The available presets. */
enum class Preset {
  SEQUENCE,
  GRID,
  PIP,
  CUSTOM,
}
