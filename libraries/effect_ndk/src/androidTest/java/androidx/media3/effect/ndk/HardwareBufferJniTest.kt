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
package androidx.media3.effect.ndk

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build.VERSION.SDK_INT
import androidx.media3.test.utils.BitmapPixelTestUtil
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Tests for [HardwareBufferJni]. */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4::class)
class HardwareBufferJniTest {

  @get:Rule val testName = TestName()

  @Test
  fun nativeCopyBitmapToHardwareBuffer_isCorrect() {
    val inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png")
    val hardwareBuffer =
      HardwareBuffer.create(
        inputBitmap.width,
        inputBitmap.height,
        /* format= */ HardwareBuffer.RGBA_8888,
        /* layers= */ 1,
        /* usage= */ HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
      )

    try {
      assertThat(HardwareBufferJni.nativeCopyBitmapToHardwareBuffer(inputBitmap, hardwareBuffer))
        .isTrue()
      if (SDK_INT >= 29) {
        val hardwareBitmap =
          Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB))!!
        val outputBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)
        assertThat(
            BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888(
              inputBitmap,
              outputBitmap,
              testName.methodName,
            )
          )
          .isZero()
      }
    } finally {
      hardwareBuffer.close()
    }
  }

  @Test
  fun nativeCopyBitmapToHardwareBuffer_mismatchedDimensions_returnsFalse() {
    val inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png")
    val hardwareBuffer =
      HardwareBuffer.create(
        inputBitmap.width * 2,
        inputBitmap.height / 2,
        /* format= */ HardwareBuffer.RGBA_8888,
        /* layers= */ 1,
        /* usage= */ HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
      )

    try {
      assertThat(HardwareBufferJni.nativeCopyBitmapToHardwareBuffer(inputBitmap, hardwareBuffer))
        .isFalse()
    } finally {
      hardwareBuffer.close()
    }
  }

  @Test
  fun nativeCopyBitmapToHardwareBuffer_incorrectUsage_returnsFalse() {
    val inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png")
    val hardwareBuffer =
      HardwareBuffer.create(
        inputBitmap.width,
        inputBitmap.height,
        /* format= */ HardwareBuffer.RGBA_8888,
        /* layers= */ 1,
        /* usage= */ HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
      )

    try {
      assertThat(HardwareBufferJni.nativeCopyBitmapToHardwareBuffer(inputBitmap, hardwareBuffer))
        .isFalse()
    } finally {
      hardwareBuffer.close()
    }
  }

  @Test
  fun nativeCopyBitmapToHardwareBuffer_incorrectFormat_returnsFalse() {
    val inputBitmap = BitmapPixelTestUtil.readBitmap("first_frame.png")
    val hardwareBuffer =
      HardwareBuffer.create(
        inputBitmap.width,
        inputBitmap.height,
        /* format= */ HardwareBuffer.RGBA_1010102,
        /* layers= */ 1,
        /* usage= */ HardwareBuffer.USAGE_CPU_WRITE_OFTEN or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
      )

    try {
      assertThat(HardwareBufferJni.nativeCopyBitmapToHardwareBuffer(inputBitmap, hardwareBuffer))
        .isFalse()
    } finally {
      hardwareBuffer.close()
    }
  }
}
