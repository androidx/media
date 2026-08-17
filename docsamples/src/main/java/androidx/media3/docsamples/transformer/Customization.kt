/*
 * Copyright 2026 The Android Open Source Project
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

@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.transformer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Transformer

/** Snippets for customization.md. */
object CustomizationKt {

  @OptIn(UnstableApi::class)
  fun transformerCustomizationCustomEncoderFactory(
    transformerBuilder: Transformer.Builder,
    context: Context,
  ) {
    // [START custom_encoder_factory]
    transformerBuilder.setEncoderFactory(
      DefaultEncoderFactory.Builder(context).setEnableFallback(false).build()
    )
    // [END custom_encoder_factory]
  }
}
