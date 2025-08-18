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
package androidx.media3.demo.composition.effect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import androidx.annotation.RawRes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Log
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieCompositionFactory
import com.airbnb.lottie.LottieDrawable

/**
 * A [CanvasOverlay] that renders a Lottie animation.
 *
 * <p>This overlay uses the Lottie library to draw an animation loaded from a raw resource. The
 * animation's size, position, speed, and looping behavior can be configured using the [Builder].
 *
 * @property context The application context.
 * @property animationConfig The configuration for the Lottie animation.
 */
@UnstableApi
internal class LottieOverlay
private constructor(
  private val context: Context,
  private val animationConfig: Config,
  private val assetProvider: LottieAssetProvider?,
) : CanvasOverlay(/* useInputFrameSize= */ false) {

  private val lottieDrawable: LottieDrawable = LottieDrawable()
  private var frameworkOverlaySettings: OverlaySettings = StaticOverlaySettings.Builder().build()
  private var timeToProgressFactor: Float = 0f

  override fun configure(videoSize: Size) {
    super.configure(videoSize)

    val composition =
      try {
        LottieCompositionFactory.fromRawResSync(context, animationConfig.animationRes).value
      } catch (e: Exception) {
        Log.e(TAG, "Lottie composition failed to load synchronously.", e)
        null
      }

    if (composition == null) {
      setCanvasSize(0, 0)
      return
    }

    lottieDrawable.composition = composition

    assetProvider?.let { provider ->
      provider.setComposition(composition)
      lottieDrawable.setImageAssetDelegate(provider.getImageAssetDelegate())
      lottieDrawable.setFontMap(provider.getFontMap())
    }

    lottieDrawable.repeatCount = LottieDrawable.INFINITE
    lottieDrawable.invalidateSelf()
    tryToSetCanvasSize(composition, videoSize)
  }

  override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
    return frameworkOverlaySettings
  }

  // Synchronized to ensure the Lottie drawable's state is not modified by another thread (e.g. in
  // 'release()') during a draw operation.
  @Synchronized
  override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
    canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

    val progress = (presentationTimeUs * timeToProgressFactor).mod(1.0f)

    lottieDrawable.setBounds(0, 0, canvas.width, canvas.height)
    lottieDrawable.progress = progress
    lottieDrawable.alpha = (animationConfig.opacity * 255).toInt()
    lottieDrawable.draw(canvas)
  }

  // Synchronized to ensure that updates to the overlay's configuration properties (canvas size,
  // overlay settings, time factor) are atomic.
  @Synchronized
  private fun tryToSetCanvasSize(composition: LottieComposition, videoSize: Size) {
    val lottieBounds = composition.bounds
    val lottieWidth = lottieBounds.width()
    val lottieHeight = lottieBounds.height()
    val lottieAspectRatio = if (lottieHeight > 0) lottieWidth.toFloat() / lottieHeight else 1.0f

    val (targetWidth, targetHeight) =
      when (val scaleMode = animationConfig.scaleMode) {
        is LottieScaleMode.Fill -> videoSize.width to videoSize.height
        is LottieScaleMode.FitToHeight -> {
          val height = videoSize.height
          val width = (height * lottieAspectRatio).toInt()
          width to height
        }
        is LottieScaleMode.FitToWidth -> {
          val width = videoSize.width
          val height = (width / lottieAspectRatio).toInt()
          width to height
        }
        is LottieScaleMode.Custom -> {
          val width = (lottieWidth * scaleMode.scaleX).toInt()
          val height = (lottieHeight * scaleMode.scaleY).toInt()
          width to height
        }
      }

    val finalWidth = targetWidth.coerceAtLeast(1)
    val finalHeight = targetHeight.coerceAtLeast(1)
    setCanvasSize(finalWidth, finalHeight)

    timeToProgressFactor =
      when (val speedSetting = animationConfig.speed) {
        is LottieSpeed.Multiplier -> {
          val lottieDurationUs = (composition.duration * 1000).toLong()
          if (lottieDurationUs > 0) {
            speedSetting.value / lottieDurationUs
          } else 0f
        }
      }

    frameworkOverlaySettings =
      StaticOverlaySettings.Builder()
        .setBackgroundFrameAnchor(
          animationConfig.backgroundFrameAnchorX,
          animationConfig.backgroundFrameAnchorY,
        )
        .setScale(1.0f, 1.0f)
        .build()
  }

  override fun release() {
    super.release()
    assetProvider?.release()
    lottieDrawable.clearComposition()
    timeToProgressFactor = 0f
  }

  class Builder(@RawRes private val animationRes: Int) {
    private var scaleMode: LottieScaleMode = LottieScaleMode.Custom()
    private var backgroundFrameAnchorX: Float = 0.0f
    private var backgroundFrameAnchorY: Float = 0.0f
    private var speed: LottieSpeed = LottieSpeed.Multiplier(1.0f)
    private var opacity: Float = 1.0f

    private var assetProvider: LottieAssetProvider? = null

    fun setScaleMode(scaleMode: LottieScaleMode) = apply { this.scaleMode = scaleMode }

    fun setAnchor(x: Float, y: Float) = apply {
      this.backgroundFrameAnchorX = x
      this.backgroundFrameAnchorY = y
    }

    fun setSpeed(speed: LottieSpeed) = apply { this.speed = speed }

    fun setOpacity(opacity: Float) = apply { this.opacity = opacity.coerceIn(0f, 1f) }

    fun setAssetProvider(assetProvider: LottieAssetProvider) = apply {
      this.assetProvider = assetProvider
    }

    fun build(context: Context): LottieOverlay {
      val config =
        Config(
          animationRes = animationRes,
          scaleMode = scaleMode,
          backgroundFrameAnchorX = backgroundFrameAnchorX,
          backgroundFrameAnchorY = backgroundFrameAnchorY,
          speed = speed,
          opacity = opacity,
        )
      return LottieOverlay(context, config, this.assetProvider)
    }
  }

  data class Config(
    @RawRes val animationRes: Int,
    val scaleMode: LottieScaleMode,
    val backgroundFrameAnchorX: Float,
    val backgroundFrameAnchorY: Float,
    val speed: LottieSpeed,
    val opacity: Float,
  )

  companion object {
    private const val TAG = "LottieOverlay"
  }

  sealed interface LottieSpeed {
    data class Multiplier(val value: Float) : LottieSpeed
  }

  sealed interface LottieScaleMode {

    data object Fill : LottieScaleMode

    data object FitToHeight : LottieScaleMode

    data object FitToWidth : LottieScaleMode

    data class Custom(val scaleX: Float = 1.0f, val scaleY: Float = 1.0f) : LottieScaleMode
  }
}
