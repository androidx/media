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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.util.Base64
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.LottieOverlay
import com.airbnb.lottie.ImageAssetDelegate
import com.airbnb.lottie.LottieComposition
import com.airbnb.lottie.LottieImageAsset
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A demo implementation of [LottieOverlay.LottieProvider].
 *
 * <p>This provider caches bitmaps in memory and loads fonts from the assets directory. It assumes a
 * specific asset structure:
 * - Images are expected to be in the `assets/images/` directory. The `id` in the Lottie JSON should
 *   match the image's filename (e.g., "image.png").
 * - Fonts are expected to be in the `assets/fonts/` directory. The font in the Lottie JSON is used
 *   to construct the filename (e.g., "Custom Font" becomes "CustomFont.ttf").
 *
 * @param context The application context.
 */
@UnstableApi
internal class DemoLottieProvider(
  private val context: Context,
  private val composition: LottieComposition,
) : LottieOverlay.LottieProvider, ImageAssetDelegate {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val bitmapCache = ConcurrentHashMap<String, Bitmap>()

  init {
    if (composition.images.isNotEmpty()) {
      scope.launch {
        composition.images.values.forEach { asset ->
          if (!bitmapCache.containsKey(asset.id)) {
            loadAndScaleBitmap(asset)?.let { bitmapCache[asset.id] = it }
          }
        }
      }
    }
  }

  override fun getLottieComposition(): LottieComposition {
    return composition
  }

  /**
   * Returns an [ImageAssetDelegate] to handle image loading.
   *
   * [getLottieComposition] must be called before this method.
   */
  override fun getImageAssetDelegate(): ImageAssetDelegate {
    return this
  }

  /**
   * Creates a map of font names to [Typeface] objects for the loaded composition.
   *
   * <p>This method iterates through the fonts defined in the Lottie JSON. It expects to find
   * corresponding font files in the `assets/fonts/` directory. The filename is derived from the
   * font's name by removing all spaces and appending ".ttf" (e.g., "Custom Font" becomes
   * "CustomFont.ttf").
   *
   * <p>If a font cannot be loaded from the assets, this method will fall back to creating a system
   * font that matches the family name and style as closely as possible.
   */
  override fun getFontMap(): Map<String, Typeface> {
    val fontMap = mutableMapOf<String, Typeface>()
    for (font in composition.fonts.keys) {
      var typeface: Typeface

      try {
        val fontFileName = font.replace(" ", "") + ".ttf"
        typeface = Typeface.createFromAsset(context.assets, "$FONT_PATH/$fontFileName")
      } catch (e: Exception) {
        Log.e(TAG, "Failed to load font $font, using System fallback instead", e)
        typeface = Typeface.create(font, Typeface.NORMAL)
      }
      fontMap[font] = typeface
    }
    return fontMap
  }

  /**
   * Fetches a bitmap for the given Lottie asset.
   *
   * <p>This method employs a read-through caching strategy. It first checks for a cached bitmap in
   * memory. If a bitmap is not found, it attempts to load, scale, and then cache it before
   * returning the result.
   */
  override fun fetchBitmap(asset: LottieImageAsset): Bitmap? {
    return bitmapCache[asset.id]
      ?: loadAndScaleBitmap(asset)?.also { bitmapCache[asset.id] = it }
      ?: createBitmap(1, 1).also {
        Log.w(TAG, "Asset '${asset.fileName}' failed to load. Using empty placeholder.")
      }
  }

  override fun release() {
    scope.cancel()
    bitmapCache.clear()
  }

  private fun loadAndScaleBitmap(asset: LottieImageAsset): Bitmap? {
    val originalBitmap = getOriginalBitmap(asset) ?: return null
    if (originalBitmap.width == asset.width && originalBitmap.height == asset.height) {
      return originalBitmap
    }
    return originalBitmap.scale(asset.width, asset.height)
  }

  /**
   * Retrieves the original, unscaled bitmap from its source.
   *
   * <p>This function handles multiple asset sources. It first checks if the bitmap is already
   * embedded in the asset, then attempts to decode a Base64 data URI if present, and finally falls
   * back to loading the bitmap from the `assets` directory.
   */
  private fun getOriginalBitmap(asset: LottieImageAsset): Bitmap? {
    if (asset.bitmap != null) return asset.bitmap
    if (asset.fileName.startsWith("data:") && asset.fileName.contains("base64,")) {
      try {
        val base64String = asset.fileName.substring(asset.fileName.indexOf(',') + 1)
        val data = Base64.decode(base64String, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(data, 0, data.size)
      } catch (e: Exception) {
        Log.e(TAG, "Failed to decode Base64 string.", e)
        return null
      }
    }
    try {
      val imagePath =
        if (asset.fileName.startsWith(IMAGES_PATH)) asset.fileName
        else "$IMAGES_PATH/${asset.fileName}"
      val inputStream = context.assets.open(imagePath)
      return BitmapFactory.decodeStream(inputStream)
    } catch (e: IOException) {
      Log.e(TAG, "Failed to load asset ${asset.fileName}", e)
      return null
    }
  }

  companion object {
    private const val TAG = "DefaultLottieAssetProvider"
    private const val IMAGES_PATH = "images"
    private const val FONT_PATH = "fonts"
  }
}
