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

package androidx.media3.docsamples.cast

import android.content.Context
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/** Snippets for customize-castoptions.md. */
object CustomizeCastOptionsKt {

  // [START custom_options]
  class MyCustomCastOptionsProvider : OptionsProvider {

    override fun getCastOptions(context: Context): CastOptions {
      return CastOptions.Builder()
        .setReceiverApplicationId(APP_ID)
        .setRemoteToLocalEnabled(true)
        .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? {
      return null
    }

    companion object {
      // Add your receiver app ID in <APP_ID>.
      private const val APP_ID = "<APP_ID>"
    }
  }
  // [END custom_options]
}
