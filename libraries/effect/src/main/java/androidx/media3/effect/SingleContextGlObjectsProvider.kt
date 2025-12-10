/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.effect

import android.opengl.EGLContext
import android.opengl.EGLDisplay
import androidx.annotation.RestrictTo
import androidx.media3.common.GlObjectsProvider

/**
 * A [GlObjectsProvider] that reuses a single [EGLContext] across [createEglContext] calls.
 *
 * @param delegate The underlying provider to create the context and other objects.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
class SingleContextGlObjectsProvider(
  private val delegate: GlObjectsProvider = DefaultGlObjectsProvider()
) : GlObjectsProvider by delegate {

  private var singleEglContext: EGLContext? = null

  override fun createEglContext(
    eglDisplay: EGLDisplay,
    openGlVersion: Int,
    configAttributes: IntArray,
  ): EGLContext =
    singleEglContext
      ?: delegate.createEglContext(eglDisplay, openGlVersion, configAttributes).also {
        singleEglContext = it
      }

  override fun release(eglDisplay: EGLDisplay) {
    if (singleEglContext != null) {
      delegate.release(eglDisplay)
      singleEglContext = null
    }
  }
}
