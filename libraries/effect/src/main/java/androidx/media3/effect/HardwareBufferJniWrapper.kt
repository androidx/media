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
package androidx.media3.effect

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import androidx.annotation.RequiresApi
import androidx.media3.common.util.ExperimentalApi

/** Wrapper for [HardwareBuffer] JNI methods. */
@RequiresApi(26)
@ExperimentalApi // TODO: b/474075198: Remove once FrameConsumer API is stable.
interface HardwareBufferJniWrapper {
  /** Creates an EGLImage from a [HardwareBuffer]. */
  fun nativeCreateEglImageFromHardwareBuffer(
    displayHandle: Long,
    hardwareBuffer: HardwareBuffer,
  ): Long

  /**
   * Binds an EGLImage to the specified texture target. Returns whether the binding is successful.
   */
  fun nativeBindEGLImage(target: Int, eglImageHandle: Long): Boolean

  /** Destroys an EGLImage. Returns whether the deletion is successful. */
  fun nativeDestroyEGLImage(displayHandle: Long, imageHandle: Long): Boolean

  /**
   * Copies a [Bitmap] to a [HardwareBuffer]. Returns whether the copy is successful.
   *
   * The destination [HardwareBuffer] must have [HardwareBuffer.USAGE_CPU_WRITE_OFTEN] usage.
   *
   * The source [Bitmap.Config] must match the destination [HardwareBuffer.getFormat], and be either
   * [Bitmap.Config.ARGB_8888] and [HardwareBuffer.RGBA_8888] or [Bitmap.Config.RGBA_1010102] and
   * [HardwareBuffer.RGBA_1010102].
   */
  fun nativeCopyBitmapToHardwareBuffer(bitmap: Bitmap, hb: HardwareBuffer): Boolean

  /**
   * Copies the contents of a source [HardwareBuffer] to a destination [HardwareBuffer]. Returns
   * whether the copy is successful.
   *
   * The source [HardwareBuffer] must have [HardwareBuffer.USAGE_CPU_READ_OFTEN] usage, and the
   * destination [HardwareBuffer] must have [HardwareBuffer.USAGE_CPU_WRITE_OFTEN] usage.
   *
   * The formats of the source and destination buffers must match, and be either
   * [HardwareBuffer.RGBA_8888] or [HardwareBuffer.RGBA_1010102].
   */
  fun nativeCopyHardwareBufferToHardwareBuffer(
    srcHb: HardwareBuffer,
    dstHb: HardwareBuffer,
  ): Boolean
}
