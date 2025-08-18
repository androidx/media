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

import android.graphics.Typeface
import com.airbnb.lottie.ImageAssetDelegate
import com.airbnb.lottie.LottieComposition

/** Provides assets for a Lottie animation and manages their lifecycle. */
interface LottieAssetProvider {

  /** Releases any resources held by this provider. */
  fun release()

  /**
   * Initializes the provider by setting the composition for subsequent asset requests.
   *
   * This must be called before [getImageAssetDelegate] or [getFontMap].
   */
  fun setComposition(composition: LottieComposition)

  /**
   * Returns an [ImageAssetDelegate] to handle image loading.
   *
   * @throws IllegalStateException if [setComposition] has not been called
   */
  fun getImageAssetDelegate(): ImageAssetDelegate

  /**
   * Returns a map of pre-loaded fonts for the composition.
   *
   * @throws IllegalStateException if [setComposition] has not been called
   */
  fun getFontMap(): Map<String, Typeface>
}
