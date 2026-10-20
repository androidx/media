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
import androidx.media3.common.video.HardwareBufferNativeHelpers
import java.nio.ByteBuffer

/** Wrapper for [HardwareBuffer] interaction and native [ByteBuffer] query JNI methods. */
@ExperimentalApi // TODO: b/474075198: Remove once FrameConsumer API is stable.
interface HardwareBufferJniWrapper : HardwareBufferNativeHelpers {
  /** Creates an EGLImage from a [HardwareBuffer]. */
  @RequiresApi(26)
  fun nativeCreateEglImageFromHardwareBuffer(
    displayHandle: Long,
    hardwareBuffer: HardwareBuffer,
  ): Long

  /**
   * Binds an EGLImage to the specified texture target. Returns whether the binding is successful.
   */
  @RequiresApi(26) fun nativeBindEGLImage(target: Int, eglImageHandle: Long): Boolean

  /** Destroys an EGLImage. Returns whether the deletion is successful. */
  @RequiresApi(26) fun nativeDestroyEGLImage(displayHandle: Long, imageHandle: Long): Boolean

  /**
   * Copies a [Bitmap] to a [HardwareBuffer]. Returns whether the copy is successful.
   *
   * The destination [HardwareBuffer] must have [HardwareBuffer.USAGE_CPU_WRITE_OFTEN] usage.
   *
   * The source [Bitmap.Config] must match the destination [HardwareBuffer.getFormat], and be either
   * [Bitmap.Config.ARGB_8888] and [HardwareBuffer.RGBA_8888] or [Bitmap.Config.RGBA_1010102] and
   * [HardwareBuffer.RGBA_1010102].
   */
  @RequiresApi(26)
  override fun nativeCopyBitmapToHardwareBuffer(bitmap: Bitmap, hb: HardwareBuffer): Boolean

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
  @RequiresApi(26)
  override fun nativeCopyHardwareBufferToHardwareBuffer(
    srcHb: HardwareBuffer,
    dstHb: HardwareBuffer,
  ): Boolean

  /**
   * Returns whether a semi-planar YUV frame's chroma planes are in NV21 order.
   *
   * [uBuffer] and [vBuffer] must be [direct][ByteBuffer.isDirect] [ByteBuffer] views of the same
   * interleaved chroma memory, starting 1 byte apart (with [uBuffer] first for NV12 and [vBuffer]
   * first for NV21). If either buffer is not a direct [ByteBuffer] or the buffers are not views of
   * the same interleaved chroma memory, the return value is undefined.
   *
   * The default implementation throws [UnsupportedOperationException].
   */
  fun nativeIsNv21(uBuffer: ByteBuffer, vBuffer: ByteBuffer): Boolean {
    throw UnsupportedOperationException()
  }
}
