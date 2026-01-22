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

import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import java.util.UUID

/**
 * Represents the single source of truth for the Composition Preview screen's UI state.
 *
 * This class aggregates all other state objects into one comprehensive model, following the
 * Unidirectional Data Flow (UDF) pattern.
 *
 * @param availableLayouts The list of available composition layouts.
 * @param compositionLayout The currently selected composition layout.
 * @param snackbarMessage A message to be shown in the snackbar.
 * @param isDebugTracingEnabled Whether debug tracing for media transformations is enabled.
 * @param mediaState The state for media items and their effects.
 * @param overlayState The state for draggable overlay management.
 * @param outputSettingsState The state for all output settings.
 * @param exportState The state for the current export process.
 */
@OptIn(UnstableApi::class)
data class CompositionPreviewState(
  val availableLayouts: List<String> = emptyList(),
  val compositionLayout: String,
  val snackbarMessage: String? = null,
  val isDebugTracingEnabled: Boolean = false,
  val mediaState: MediaState,
  val overlayState: OverlayState,
  val outputSettingsState: OutputSettingsState,
  val exportState: ExportState,
)

/**
 * Holds the state related to media items and their effects.
 *
 * @param availableItems The list of all possible media items the user can add.
 * @param selectedItems The list of media items currently on the editing timeline.
 * @param availableEffects The list of all available effects the user can apply.
 */
data class MediaState(
  val availableItems: List<Item> = emptyList(),
  val selectedItems: List<Item> = emptyList(),
  val availableEffects: List<String> = emptyList(),
)

/**
 * Represents a single media item, such as a video or image, that can be added to the composition.
 *
 * @param title The display name of the media item.
 * @param uri The URI string pointing to the media content.
 * @param durationUs The duration of the media item in microseconds.
 * @param selectedEffects The set of effect names currently applied to this item.
 */
data class Item(
  val title: String,
  val uri: String,
  val durationUs: Long,
  val selectedEffects: Set<String> = emptySet(),
)

/**
 * Holds the state for overlay management, including available assets and placement status.
 *
 * @param availableOverlays The list of overlay assets the user can choose from.
 * @param committedOverlays The list of overlays that have been placed on the timeline.
 * @param placementState The current state of the overlay placement UI (either Inactive or Placing).
 */
@OptIn(UnstableApi::class)
data class OverlayState(
  val availableOverlays: List<OverlayAsset> = emptyList(),
  val committedOverlays: List<PlacedOverlay> = emptyList(),
  val placementState: PlacementState = PlacementState.Inactive,
)

/**
 * Represents an overlay that has been placed on the timeline, including its bitmap and position. In
 * placement mode, the UI will allow for this bitmap to be moved around. After the placing is done,
 * the [BitmapOverlay] object is updated with the new transform, received from the UI actions.
 *
 * @param id A unique identifier for the placed overlay.
 * @param assetName The name of the overlay asset.
 * @param bitmap The actual bitmap content of the overlay.
 * @param overlay The Media3 [BitmapOverlay] object used by the player/transformer.
 * @param uiTransformOffset The current top-left offset of the overlay in UI pixel coordinates.
 */
@OptIn(UnstableApi::class)
data class PlacedOverlay(
  val id: UUID = UUID.randomUUID(),
  val assetName: String,
  val bitmap: Bitmap,
  var overlay: BitmapOverlay? = null,
  var uiTransformOffset: Offset = Offset.Zero,
)

/**
 * Represents an overlay asset that can be chosen by the user to be placed. This is different from a
 * [PlacedOverlay], as the asset has not been loaded yet and hence the [BitmapOverlay] object has
 * also not been created yet.
 *
 * @param name The display name of the asset.
 * @param assetPath The path to the asset within the application's assets folder.
 */
data class OverlayAsset(val name: String, val assetPath: String)

/** A sealed interface to model the different states of the overlay placement UI. */
@OptIn(UnstableApi::class)
sealed interface PlacementState {
  /** The state when no overlay is being actively placed or moved. */
  data object Inactive : PlacementState

  /**
   * The state when an overlay is being actively placed or moved by the user.
   *
   * @param overlay The [PlacedOverlay] object that is currently being placed.
   * @param currentUiTransformOffset The current top-left position of the overlay during a drag
   *   gesture. We use this instead of the existing [PlacedOverlay.uiTransformOffset], as we only
   *   want to updated the [PlacedOverlay] once after the whole drag process is completed and not on
   *   every drag input.
   */
  data class Placing(val overlay: PlacedOverlay, val currentUiTransformOffset: Offset) :
    PlacementState
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
