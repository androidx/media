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
package androidx.media3.effect.ndk

import android.hardware.HardwareBuffer
import androidx.annotation.RequiresApi
import androidx.media3.common.util.ExperimentalApi

/** JNI methods for HardwareBuffer interaction. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
object HardwareBufferJni {
  init {
    System.loadLibrary("hardwareBufferJNI")
  }

  /** Creates an EGLImage from a [HardwareBuffer]. */
  external fun nativeCreateEglImageFromHardwareBuffer(
    displayHandle: Long,
    hardwareBuffer: HardwareBuffer,
  ): Long

  /**
   * Binds an EGLImage to the specified texture target. Returns whether the binding is successful.
   */
  external fun nativeBindEGLImage(target: Int, eglImageHandle: Long): Boolean

  /** Destroys an EGLImage. Retutns whether the deletion is successful. */
  external fun nativeDestroyEGLImage(displayHandle: Long, imageHandle: Long): Boolean
}
