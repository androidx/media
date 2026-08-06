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
package androidx.media3.demo.compose.editing

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * A reusable video cropping widget.
 *
 * This component displays the video and a crop overlay. It allows the user to resize the crop area
 * by dragging its corners (preserving the aspect ratio) and pan the video inside the crop area.
 *
 * @param state The [VideoCropperState] object holding the state. Created via
 *   [rememberVideoCropperState].
 * @param modifier The modifier to be applied to the cropping widget.
 * @param onCropRectChangeFinished A callback that is invoked when a user interaction (dragging a
 *   corner or panning the video) finishes.
 * @param cropFrameControls An optional overlay to be rendered on top of the crop frame.
 * @param colors The [VideoCropperColors] used to style the cropping widget. Defaults to
 *   [VideoCropperDefaults.colors].
 * @param cropFramePadding The padding values applied to the crop area boundaries, relative to the
 *   layout bounds of the [VideoCropper] composable. Defaults to
 *   [VideoCropperDefaults.CropFramePadding].
 * @param bracketThickness The stroke thickness of the corner brackets. Defaults to
 *   [VideoCropperDefaults.BracketThickness].
 * @param bracketLength The arm length of the corner brackets. Defaults to
 *   [VideoCropperDefaults.BracketLength].
 * @param cropFrameBorderThickness The stroke thickness of the frame border around the crop area.
 *   Defaults to [VideoCropperDefaults.CropFrameBorderThickness].
 * @param cropFrameShape The [RoundedCornerShape] defining the corner rounding radius of the crop
 *   frame and brackets. Defaults to [VideoCropperDefaults.CropFrameShape].
 * @param minCropSize The minimum width and height of the crop frame. Defaults to
 *   [VideoCropperDefaults.MinCropSize].
 */
@Composable
fun VideoCropper(
  state: VideoCropperState,
  modifier: Modifier = Modifier,
  onCropRectChangeFinished: (() -> Unit)? = null,
  cropFrameControls: (@Composable BoxScope.() -> Unit)? = null,
  colors: VideoCropperColors = VideoCropperDefaults.colors(),
  cropFramePadding: PaddingValues = VideoCropperDefaults.CropFramePadding,
  bracketThickness: Dp = VideoCropperDefaults.BracketThickness,
  bracketLength: Dp = VideoCropperDefaults.BracketLength,
  cropFrameBorderThickness: Dp = VideoCropperDefaults.CropFrameBorderThickness,
  cropFrameShape: RoundedCornerShape = VideoCropperDefaults.CropFrameShape,
  minCropSize: Dp = VideoCropperDefaults.MinCropSize,
) {}
