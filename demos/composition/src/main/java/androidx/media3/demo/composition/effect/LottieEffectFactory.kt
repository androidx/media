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
import androidx.media3.effect.LottieOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import com.airbnb.lottie.LottieCompositionFactory

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
          run {
            val composition =
              checkNotNull(LottieCompositionFactory.fromRawResSync(context, R.raw.counter).value)
            val provider = DemoLottieProvider(context, composition)
            LottieOverlay.Builder(provider)
              .setOverlaySettings(StaticOverlaySettings.Builder().setScale(0.5f, 0.5f).build())
          },
        context.getString(R.string.lottie_effect_name_counter_fast) to
          run {
            val composition =
              checkNotNull(LottieCompositionFactory.fromRawResSync(context, R.raw.counter).value)
            val provider = DemoLottieProvider(context, composition)
            LottieOverlay.Builder(provider)
              .setOverlaySettings(StaticOverlaySettings.Builder().setScale(0.5f, 0.5f).build())
              .setSpeed(2f)
          },
        context.getString(R.string.lottie_effect_name_swoosh) to
          run {
            val composition =
              checkNotNull(LottieCompositionFactory.fromRawResSync(context, R.raw.swoosh).value)
            val provider = DemoLottieProvider(context, composition)
            LottieOverlay.Builder(provider)
              .setOverlaySettings(StaticOverlaySettings.Builder().setAlphaScale(0.75f).build())
              .setSpeed(0.5f)
          },
        context.getString(R.string.lottie_effect_name_heart) to
          run {
            val composition =
              checkNotNull(LottieCompositionFactory.fromRawResSync(context, R.raw.heart).value)
            val provider = DemoLottieProvider(context, composition)
            LottieOverlay.Builder(provider)
              .setOverlaySettings(
                StaticOverlaySettings.Builder()
                  .setScale(0.25f, 0.25f)
                  .setBackgroundFrameAnchor(0.5f, 0.5f)
                  .build()
              )
              .setSpeed(2.0f)
          },
        context.getString(R.string.lottie_effect_name_asset_folder) to
          run {
            val composition =
              checkNotNull(
                LottieCompositionFactory.fromRawResSync(context, R.raw.text_with_asset_from_folder)
                  .value
              )
            val provider = DemoLottieProvider(context, composition)
            LottieOverlay.Builder(provider)
              .setOverlaySettings(StaticOverlaySettings.Builder().setScale(0.5f, 0.5f).build())
          },
        context.getString(R.string.lottie_effect_name_asset_encoded) to
          run {
            val composition =
              checkNotNull(
                LottieCompositionFactory.fromRawResSync(context, R.raw.text_with_asset_encoded)
                  .value
              )
            val provider = DemoLottieProvider(context, composition)
            LottieOverlay.Builder(provider)
              .setOverlaySettings(StaticOverlaySettings.Builder().setScale(0.5f, 0.5f).build())
          },
      )
      .mapValues { entry ->
        val lottieOverlay = entry.value.build()
        OverlayEffect(listOf(lottieOverlay))
      }
  }
}
