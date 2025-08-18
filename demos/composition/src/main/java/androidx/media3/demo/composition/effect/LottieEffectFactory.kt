/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.composition.effect

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.effect.LottieOverlay.LottieScaleMode
import androidx.media3.demo.composition.effect.LottieOverlay.LottieSpeed
import androidx.media3.effect.OverlayEffect

/**
 * Factory for creating pre-configured Lottie animation effects.
 *
 * <p>This object provides methods to access and create [Effect] instances based on Lottie
 * animations defined in resources.
 */
@UnstableApi
internal object LottieEffectFactory {

  /**
   * Builds all available pre-configured Lottie effects.
   *
   * @param context The application context, needed to resolve string resources and to pass along to
   *   the overlay.
   * @return A [Map] of the effect's display name to its configured [Effect] instance.
   */
  fun buildAvailableEffects(context: Context): Map<String, Effect> {
    return mapOf(
        context.getString(R.string.lottie_effect_name_counter) to
          LottieOverlay.Builder(R.raw.counter).setScaleMode(LottieScaleMode.Fill),
        context.getString(R.string.lottie_effect_name_counter_fast) to
          LottieOverlay.Builder(R.raw.counter)
            .setScaleMode(LottieScaleMode.Fill)
            .setSpeed(LottieSpeed.Multiplier(2f)),
        context.getString(R.string.lottie_effect_name_swoosh) to
          LottieOverlay.Builder(R.raw.swoosh)
            .setScaleMode(LottieScaleMode.FitToWidth)
            .setOpacity(0.75f)
            .setSpeed(LottieSpeed.Multiplier(0.5f)),
        context.getString(R.string.lottie_effect_name_heart) to
          LottieOverlay.Builder(R.raw.heart)
            .setScaleMode(LottieScaleMode.Custom(0.1f, 0.1f))
            .setAnchor(0.5f, 0.5f)
            .setSpeed(LottieSpeed.Multiplier(2.0f)),
        "Lottie: Asset from folder with System Font(monospace, bold)" to
          LottieOverlay.Builder(R.raw.text_with_asset_from_folder)
            .setScaleMode(LottieScaleMode.FitToHeight)
            .setAssetProvider(DemoLottieAssetProvider(context)),
        "Lottie: Assets encoded with Imported Font(Playwrite, regular)" to
          LottieOverlay.Builder(R.raw.text_with_asset_encoded)
            .setScaleMode(LottieScaleMode.FitToHeight)
            .setAssetProvider(DemoLottieAssetProvider(context)),
      )
      .mapValues { entry ->
        val lottieOverlay = entry.value.build(context)
        OverlayEffect(listOf(lottieOverlay))
      }
  }
}
