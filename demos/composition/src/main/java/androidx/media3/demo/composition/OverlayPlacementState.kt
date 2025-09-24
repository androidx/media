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
package androidx.media3.demo.composition

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.media3.effect.BitmapOverlay
import java.util.UUID

/** Represents the UI transformation of an overlay. */
data class UiTransform(val offset: Offset = Offset.Zero)

/** Represents an overlay asset that can be placed. */
data class OverlayAsset(val name: String, val assetPath: String)

/** Represents an overlay that has been placed on the timeline. */
data class PlacedOverlay(
  val id: UUID = UUID.randomUUID(),
  val assetName: String,
  val bitmap: Bitmap,
  var overlay: BitmapOverlay? = null,
  var uiTransform: UiTransform = UiTransform(),
)

/** Represents the state of the overlay placement UI. */
sealed interface PlacementState {
  data object Inactive : PlacementState

  data class Placing(val overlay: PlacedOverlay, val currentUiTransform: UiTransform) :
    PlacementState
}
