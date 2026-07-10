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
package androidx.media3.demo.effect

import androidx.compose.ui.graphics.Color
import androidx.media3.demo.effect.ui.COLORS
import com.google.common.collect.ImmutableList

/** UI state for the effect demo application. */
internal data class EffectUiState(
  val effectsEnabled: Boolean = false,
  val effectsChanged: Boolean = false,
  val contrastChecked: Boolean = false,
  val contrastValue: Float = 0f,
  val confettiOverlayChecked: Boolean = false,
  val textOverlayChecked: Boolean = false,
  val clockOverlayChecked: Boolean = false,
  val lottieOverlayChecked: Boolean = false,
  val textOverlayText: String? = null,
  val textOverlayColor: Color = COLORS[0],
  val textOverlayAlpha: Float = 1f,
  val lottieOverlayName: String? = null,
  val lottieEffectsLoaded: Boolean = false,
  val lottieOverlayOptions: ImmutableList<String> = ImmutableList.of(),
  val errorMessage: String? = null,
)
