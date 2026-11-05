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
import androidx.annotation.RestrictTo
import androidx.annotation.RestrictTo.Scope.LIBRARY_GROUP
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.video.HardwareBufferNativeHelpers
import androidx.media3.common.video.ImagePlanesFrame.Plane
import java.nio.ByteBuffer

/** Wrapper for [HardwareBuffer] interaction and native [ByteBuffer] JNI methods. */
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

  /**
   * Converts a YUY2 [ByteBuffer] into 8-bit YUV 4:2:0 [planes][Plane] (Y, U, V).
   *
   * Reads 4-byte `[Y0, U, Y1, V]` macropixels (two pixels sharing horizontal chroma) from
   * [yuy2Buffer] starting at its [position][ByteBuffer.position] (`ceil(width / 2) * 4` bytes per
   * row; when [width] is odd, the final macropixel's `Y1` byte is ignored). Writes luma to every
   * row and averages adjacent even and odd rows for 4:2:0 chroma (`(even + odd + 1) >> 1`, or the
   * final row alone when [height] is odd), supporting planar (I420, YV12) and semi-planar (NV12,
   * NV21) [outputPlanes] without modifying buffer positions or limits. For a `4x2` frame:
   * ```
   * Input yuy2Buffer (8 bytes/row):
   *   Row 0: [Y0, U0, Y1, V0, Y2, U1, Y3, V1]
   *   Row 1: [Y4, U2, Y5, V2, Y6, U3, Y7, V3]
   *
   * Output planes:
   *   Y (4x2): [Y0, Y1, Y2, Y3]    U (2x1): [(U0+U2+1)>>1, (U1+U3+1)>>1]
   *            [Y4, Y5, Y6, Y7]    V (2x1): [(V0+V2+1)>>1, (V1+V3+1)>>1]
   * ```
   *
   * All buffers must be [direct][ByteBuffer.isDirect]. The default implementation throws
   * [UnsupportedOperationException].
   */
  // TODO(b/531653682): Remove LIBRARY_GROUP restriction when ImagePlanesFrame is published.
  @RestrictTo(LIBRARY_GROUP)
  fun convertYuy2ToYuv420Planes(
    yuy2Buffer: ByteBuffer,
    width: Int,
    height: Int,
    outputPlanes: List<@JvmSuppressWildcards Plane>,
  ) {
    throw UnsupportedOperationException()
  }
}
