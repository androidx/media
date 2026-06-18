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
package androidx.media3.effect.ndk;

import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static com.google.common.truth.Truth.assertThat;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Tests for {@link HardwareBufferJni}. */
@SdkSuppress(minSdkVersion = 26)
@RunWith(AndroidJUnit4.class)
public final class HardwareBufferJniTest {

  private static final String FILE_PATH = "media/png/first_frame_1920x1080.png";
  private static final int WIDTH = 100;
  private static final int HEIGHT = 100;

  @Rule public final TestName testName = new TestName();

  @Test
  public void nativeCopyBitmapToHardwareBuffer_sdr_matchesInputBitmap() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(FILE_PATH);
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            inputBitmap.getWidth(),
            inputBitmap.getHeight(),
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
                  inputBitmap, hardwareBuffer))
          .isTrue();
      if (Build.VERSION.SDK_INT >= 29) {
        Bitmap hardwareBitmap =
            Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
        assertThat(hardwareBitmap).isNotNull();
        Bitmap outputBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false);
        assertThat(
                getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                    inputBitmap, outputBitmap, testName.getMethodName()))
            .isEqualTo(0);
      }
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void nativeCopyBitmapToHardwareBuffer_hdr_matchesInputBitmap() throws Exception {
    Bitmap inputBitmap =
        BitmapPixelTestUtil.readBitmap(FILE_PATH)
            .copy(Bitmap.Config.RGBA_1010102, /* isMutable= */ false);
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            inputBitmap.getWidth(),
            inputBitmap.getHeight(),
            HardwareBuffer.RGBA_1010102,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
                  inputBitmap, hardwareBuffer))
          .isTrue();
      Bitmap hardwareBitmap =
          Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
      assertThat(hardwareBitmap).isNotNull();
      Bitmap outputBitmap = hardwareBitmap.copy(Bitmap.Config.RGBA_1010102, /* isMutable= */ false);
      assertThat(
              getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                  inputBitmap, outputBitmap, testName.getMethodName()))
          .isEqualTo(0);
    }
  }

  @Test
  public void nativeCopyBitmapToHardwareBuffer_mismatchedDimensions_returnsFalse()
      throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(FILE_PATH);
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            inputBitmap.getWidth() * 2,
            inputBitmap.getHeight() / 2,
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
                  inputBitmap, hardwareBuffer))
          .isFalse();
    }
  }

  @Test
  public void nativeCopyBitmapToHardwareBuffer_incorrectUsage_returnsFalse() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(FILE_PATH);
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            inputBitmap.getWidth(),
            inputBitmap.getHeight(),
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
                  inputBitmap, hardwareBuffer))
          .isFalse();
    }
  }

  @Test
  public void nativeCopyBitmapToHardwareBuffer_incorrectFormat_returnsFalse() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(FILE_PATH);
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            inputBitmap.getWidth(),
            inputBitmap.getHeight(),
            HardwareBuffer.RGBA_1010102,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
                  inputBitmap, hardwareBuffer))
          .isFalse();
    }
  }

  @Test
  public void nativeCopyHardwareBufferToHardwareBuffer_sdr_matchesInputBuffer() throws Exception {
    Bitmap inputBitmap = BitmapPixelTestUtil.readBitmap(FILE_PATH);
    try (HardwareBuffer srcHb =
            HardwareBuffer.create(
                inputBitmap.getWidth(),
                inputBitmap.getHeight(),
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        HardwareBuffer dstHb =
            HardwareBuffer.create(
                inputBitmap.getWidth(),
                inputBitmap.getHeight(),
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(inputBitmap, srcHb))
          .isTrue();
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(srcHb, dstHb))
          .isTrue();

      if (Build.VERSION.SDK_INT >= 29) {
        Bitmap hardwareBitmap =
            Bitmap.wrapHardwareBuffer(dstHb, ColorSpace.get(ColorSpace.Named.SRGB));
        assertThat(hardwareBitmap).isNotNull();
        Bitmap outputBitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, /* isMutable= */ false);
        assertThat(
                getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                    inputBitmap, outputBitmap, testName.getMethodName()))
            .isEqualTo(0);
      }
    }
  }

  @Test
  @SdkSuppress(maxSdkVersion = 32)
  public void nativeCopyHardwareBufferToHardwareBuffer_hdrBelowApi33_returnsTrue()
      throws Exception {
    // The methods to access the HDR bitmap do not exist below API 33, so just assert copying the
    // buffer formats succeeds.
    try (HardwareBuffer srcHb =
            HardwareBuffer.create(
                WIDTH,
                HEIGHT,
                HardwareBuffer.RGBA_1010102,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        HardwareBuffer dstHb =
            HardwareBuffer.create(
                WIDTH,
                HEIGHT,
                HardwareBuffer.RGBA_1010102,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(srcHb, dstHb))
          .isTrue();
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void nativeCopyHardwareBufferToHardwareBuffer_hdr_matchesInputBuffer() throws Exception {
    Bitmap inputBitmap =
        BitmapPixelTestUtil.readBitmap(FILE_PATH)
            .copy(Bitmap.Config.RGBA_1010102, /* isMutable= */ false);
    try (HardwareBuffer srcHb =
            HardwareBuffer.create(
                inputBitmap.getWidth(),
                inputBitmap.getHeight(),
                HardwareBuffer.RGBA_1010102,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
        HardwareBuffer dstHb =
            HardwareBuffer.create(
                inputBitmap.getWidth(),
                inputBitmap.getHeight(),
                HardwareBuffer.RGBA_1010102,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                    | HardwareBuffer.USAGE_CPU_READ_OFTEN
                    | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(inputBitmap, srcHb))
          .isTrue();
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(srcHb, dstHb))
          .isTrue();

      Bitmap hardwareBitmap =
          Bitmap.wrapHardwareBuffer(dstHb, ColorSpace.get(ColorSpace.Named.SRGB));
      assertThat(hardwareBitmap).isNotNull();
      Bitmap outputBitmap = hardwareBitmap.copy(Bitmap.Config.RGBA_1010102, /* isMutable= */ false);
      assertThat(
              getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                  inputBitmap, outputBitmap, testName.getMethodName()))
          .isEqualTo(0);
    }
  }

  @Test
  public void nativeCopyHardwareBufferToHardwareBuffer_sameSourceAndDestination_returnsTrue()
      throws Exception {
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            WIDTH,
            HEIGHT,
            HardwareBuffer.RGBA_8888,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN
                | HardwareBuffer.USAGE_CPU_READ_OFTEN
                | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(
                  hardwareBuffer, hardwareBuffer))
          .isTrue();
    }
  }

  @Test
  public void nativeCopyHardwareBufferToHardwareBuffer_mismatchedDimensions_returnsFalse()
      throws Exception {
    try (HardwareBuffer hb1 =
            HardwareBuffer.create(
                WIDTH,
                HEIGHT,
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_READ_OFTEN);
        HardwareBuffer hb2 =
            HardwareBuffer.create(
                WIDTH * 2,
                HEIGHT,
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN)) {
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(hb1, hb2))
          .isFalse();
    }
  }

  @Test
  public void nativeCopyHardwareBufferToHardwareBuffer_mismatchedFormats_returnsFalse()
      throws Exception {
    try (HardwareBuffer hb1 =
            HardwareBuffer.create(
                WIDTH,
                HEIGHT,
                HardwareBuffer.RGBA_8888,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_READ_OFTEN);
        HardwareBuffer hb2 =
            HardwareBuffer.create(
                WIDTH,
                HEIGHT,
                HardwareBuffer.RGBA_1010102,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN)) {
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(hb1, hb2))
          .isFalse();
    }
  }
}
