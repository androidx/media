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
package androidx.media3.demo.compose

import android.app.Application
import androidx.annotation.OptIn
import androidx.media3.cast.Cast
import androidx.media3.cast.CastParams
import androidx.media3.common.util.UnstableApi

/** The main application class for the Compose demo app. */
class DemoComposeApplication : Application() {
  @OptIn(UnstableApi::class) // Cast is an unstable API.
  override fun onCreate() {
    super.onCreate()
    Cast.getSingletonInstance(this)
      .initialize(CastParams.Builder().setShowSystemOutputSwitcherOnCastButtonClick(true).build())
  }
}
