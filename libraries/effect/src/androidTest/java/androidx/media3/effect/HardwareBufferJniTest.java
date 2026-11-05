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
package androidx.media3.effect;

import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceArgb8888;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.test.utils.BitmapPixelTestUtil;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Tests for {@link HardwareBufferJni}. */
@RunWith(AndroidJUnit4.class)
public final class HardwareBufferJniTest {

  private static final String FILE_PATH = "media/png/first_frame_1920x1080.png";
  private static final int WIDTH = 100;
  private static final int HEIGHT = 100;
  private static final byte IGNORED = (byte) 0xFF;
  private static final byte PADDING = (byte) 0x7F;
  private static final byte PREFILLED = (byte) 0x5A;

  @Rule public final TestName testName = new TestName();

  @Test
  @SdkSuppress(minSdkVersion = 26)
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
  public void nativeCopyBitmapToHardwareBuffer_rgba1010102_matchesInputBitmap() throws Exception {
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

  // TODO: b/545085046 - Investigate supporting RGBA_FP16 on API 30-32.
  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void nativeCopyBitmapToHardwareBuffer_f16_matchesInputBitmap() throws Exception {
    Bitmap inputBitmap =
        BitmapPixelTestUtil.readBitmap(FILE_PATH)
            .copy(Bitmap.Config.RGBA_F16, /* isMutable= */ false);
    try (HardwareBuffer hardwareBuffer =
        HardwareBuffer.create(
            inputBitmap.getWidth(),
            inputBitmap.getHeight(),
            HardwareBuffer.RGBA_FP16,
            /* layers= */ 1,
            HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(
              HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(
                  inputBitmap, hardwareBuffer))
          .isTrue();
      Bitmap hardwareBitmap =
          Bitmap.wrapHardwareBuffer(hardwareBuffer, ColorSpace.get(ColorSpace.Named.SRGB));
      assertThat(hardwareBitmap).isNotNull();
      Bitmap outputBitmap = hardwareBitmap.copy(Bitmap.Config.RGBA_F16, /* isMutable= */ false);
      assertThat(
              getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                  inputBitmap, outputBitmap, testName.getMethodName()))
          .isEqualTo(0);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
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
  @SdkSuppress(minSdkVersion = 26)
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
  @SdkSuppress(minSdkVersion = 26)
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
  @SdkSuppress(minSdkVersion = 26)
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
  @SdkSuppress(minSdkVersion = 28, maxSdkVersion = 32)
  public void nativeCopyHardwareBufferToHardwareBuffer_rgba1010102BelowApi33_returnsTrue()
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
  public void nativeCopyHardwareBufferToHardwareBuffer_rgba1010102_matchesInputBuffer()
      throws Exception {
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

  // TODO: b/545085046 - Investigate supporting RGBA_FP16 on API 30-32.
  @Test
  @SdkSuppress(minSdkVersion = 33)
  public void nativeCopyHardwareBufferToHardwareBuffer_f16_matchesInputBuffer() throws Exception {
    Bitmap inputBitmap =
        BitmapPixelTestUtil.readBitmap(FILE_PATH)
            .copy(Bitmap.Config.RGBA_F16, /* isMutable= */ false);
    try (HardwareBuffer srcHb =
            HardwareBuffer.create(
                inputBitmap.getWidth(),
                inputBitmap.getHeight(),
                HardwareBuffer.RGBA_FP16,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_CPU_READ_OFTEN);
        HardwareBuffer dstHb =
            HardwareBuffer.create(
                inputBitmap.getWidth(),
                inputBitmap.getHeight(),
                HardwareBuffer.RGBA_FP16,
                /* layers= */ 1,
                HardwareBuffer.USAGE_CPU_WRITE_OFTEN | HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)) {
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyBitmapToHardwareBuffer(inputBitmap, srcHb))
          .isTrue();
      assertThat(HardwareBufferJni.INSTANCE.nativeCopyHardwareBufferToHardwareBuffer(srcHb, dstHb))
          .isTrue();

      Bitmap hardwareBitmap =
          Bitmap.wrapHardwareBuffer(dstHb, ColorSpace.get(ColorSpace.Named.SRGB));
      assertThat(hardwareBitmap).isNotNull();
      Bitmap outputBitmap = hardwareBitmap.copy(Bitmap.Config.RGBA_F16, /* isMutable= */ false);
      assertThat(
              getBitmapAveragePixelAbsoluteDifferenceArgb8888(
                  inputBitmap, outputBitmap, testName.getMethodName()))
          .isEqualTo(0);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 26)
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
  @SdkSuppress(minSdkVersion = 26)
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
  @SdkSuppress(minSdkVersion = 26)
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

  @Test
  public void nativeIsNv21_uBufferBeforeVBuffer_returnsFalse() {
    ByteBuffer interleavedChroma = ByteBuffer.allocateDirect(/* capacity= */ 16);
    ByteBuffer uBuffer = interleavedChroma.slice();
    interleavedChroma.position(1);
    ByteBuffer vBuffer = interleavedChroma.slice();

    assertThat(HardwareBufferJni.INSTANCE.nativeIsNv21(uBuffer, vBuffer)).isFalse();
  }

  @Test
  public void nativeIsNv21_vBufferBeforeUBuffer_returnsTrue() {
    ByteBuffer interleavedChroma = ByteBuffer.allocateDirect(/* capacity= */ 16);
    ByteBuffer vBuffer = interleavedChroma.slice();
    interleavedChroma.position(1);
    ByteBuffer uBuffer = interleavedChroma.slice();

    assertThat(HardwareBufferJni.INSTANCE.nativeIsNv21(uBuffer, vBuffer)).isTrue();
  }

  @Test
  public void convertYuy2ToYuv420Planes_evenDimensions_convertsLumaAndSubsampledChroma() {
    // Input packedYuy2Buffer (4x4 image, 8 bytes per row in YUY2 order):
    //           Y0  U0  Y1  V0  Y2  U1  Y3  V1
    //   Row 0: [ 1, 10,  2, 50,  3, 30,  4, 70]
    //   Row 1: [ 5, 20,  6, 60,  7, 40,  8, 80]
    //   Row 2: [ 9, 12, 10, 52, 11, 32, 12, 72]
    //   Row 3: [13, 22, 14, 62, 15, 42, 16, 82]
    //
    // Expected output planes (each chroma row averages an even/odd input row pair):
    //   Y (4x4):             U (2x2):                           V (2x2):
    //     [ 1,  2,  3,  4]     [15, 35] ((10+20)/2, (30+40)/2)    [55, 75] ((50+60)/2, (70+80)/2)
    //     [ 5,  6,  7,  8]     [17, 37] ((12+22)/2, (32+42)/2)    [57, 77] ((52+62)/2, (72+82)/2)
    //     [ 9, 10, 11, 12]
    //     [13, 14, 15, 16]
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            // Row 0
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 30, /* y1= */ 4, /* v= */ 70),
            // Row 1
            createYuy2Macropixel(/* y0= */ 5, /* u= */ 20, /* y1= */ 6, /* v= */ 60),
            createYuy2Macropixel(/* y0= */ 7, /* u= */ 40, /* y1= */ 8, /* v= */ 80),
            // Row 2
            createYuy2Macropixel(/* y0= */ 9, /* u= */ 12, /* y1= */ 10, /* v= */ 52),
            createYuy2Macropixel(/* y0= */ 11, /* u= */ 32, /* y1= */ 12, /* v= */ 72),
            // Row 3
            createYuy2Macropixel(/* y0= */ 13, /* u= */ 22, /* y1= */ 14, /* v= */ 62),
            createYuy2Macropixel(/* y0= */ 15, /* u= */ 42, /* y1= */ 16, /* v= */ 82));
    ImmutableList<Plane> planes = createPlanarPlanes(/* width= */ 4, /* height= */ 4);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 4, /* height= */ 4, planes);

    assertThat(toByteArray(planes.get(0).getBuffer()))
        .isEqualTo(
            new byte[] {
              1, 2, 3, 4,
              5, 6, 7, 8,
              9, 10, 11, 12,
              13, 14, 15, 16
            });
    assertThat(toByteArray(planes.get(1).getBuffer())).isEqualTo(new byte[] {15, 35, 17, 37});
    assertThat(toByteArray(planes.get(2).getBuffer())).isEqualTo(new byte[] {55, 75, 57, 77});
  }

  @Test
  public void convertYuy2ToYuv420Planes_oddChromaSum_roundsVerticalAverageUp() {
    // 2x2 image: (10 + 21) / 2 = 15.5 -> 16 for U, and (50 + 61) / 2 = 55.5 -> 56 for V.
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 21, /* y1= */ 4, /* v= */ 61));
    ImmutableList<Plane> planes = createPlanarPlanes(/* width= */ 2, /* height= */ 2);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 2, /* height= */ 2, planes);

    assertThat(toByteArray(planes.get(1).getBuffer())).isEqualTo(new byte[] {16});
    assertThat(toByteArray(planes.get(2).getBuffer())).isEqualTo(new byte[] {56});
  }

  @Test
  public void convertYuy2ToYuv420Planes_oddDimensions_convertsLumaAndSubsampledChroma() {
    // Input packedYuy2Buffer (3x3 image, ceil(3 / 2) = 2 macropixels = 8 bytes per row):
    //           Y0  U0  Y1  V0  Y2  U1  Y_pad  V1
    //   Row 0: [ 1, 10,  2, 50,  3, 30,     _, 70]
    //   Row 1: [ 4, 20,  5, 60,  6, 40,     _, 80]
    //   Row 2: [ 7, 12,  8, 52,  9, 32,     _, 72]
    //
    // Expected output planes (trailing Y_pad ignored so Y is 3x3; chroma rounded up to 2x2 with
    // Rows 0 & 1 averaged and unpaired final Row 2 used as-is):
    //   Y (3x3):         U (2x2):                           V (2x2):
    //     [1, 2, 3]        [15, 35] ((10+20)/2, (30+40)/2)    [55, 75] ((50+60)/2, (70+80)/2)
    //     [4, 5, 6]        [12, 32] (Row 2 unpaired)          [52, 72] (Row 2 unpaired)
    //     [7, 8, 9]
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            // Row 0
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 30, /* y1= */ IGNORED, /* v= */ 70),
            // Row 1
            createYuy2Macropixel(/* y0= */ 4, /* u= */ 20, /* y1= */ 5, /* v= */ 60),
            createYuy2Macropixel(/* y0= */ 6, /* u= */ 40, /* y1= */ IGNORED, /* v= */ 80),
            // Row 2
            createYuy2Macropixel(/* y0= */ 7, /* u= */ 12, /* y1= */ 8, /* v= */ 52),
            createYuy2Macropixel(/* y0= */ 9, /* u= */ 32, /* y1= */ IGNORED, /* v= */ 72));
    ImmutableList<Plane> planes = createPlanarPlanes(/* width= */ 3, /* height= */ 3);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 3, /* height= */ 3, planes);

    assertThat(toByteArray(planes.get(0).getBuffer()))
        .isEqualTo(
            new byte[] {
              1, 2, 3,
              4, 5, 6,
              7, 8, 9
            });
    assertThat(toByteArray(planes.get(1).getBuffer())).isEqualTo(new byte[] {15, 35, 12, 32});
    assertThat(toByteArray(planes.get(2).getBuffer())).isEqualTo(new byte[] {55, 75, 52, 72});
  }

  @Test
  public void convertYuy2ToYuv420Planes_semiPlanarNv12_convertsInterleavedChroma() {
    // Input packedYuy2Buffer (4x4 image, 8 bytes per row in YUY2 order):
    //           Y0  U0  Y1  V0  Y2  U1  Y3  V1
    //   Row 0: [ 1, 10,  2, 50,  3, 30,  4, 70]
    //   Row 1: [ 5, 20,  6, 60,  7, 40,  8, 80]
    //   Row 2: [ 9, 12, 10, 52, 11, 32, 12, 72]
    //   Row 3: [13, 22, 14, 62, 15, 42, 16, 82]
    //
    // Expected semi-planar (NV12) planes (Rows 0 & 1 -> U=[15, 35], V=[55, 75];
    // Rows 2 & 3 -> U=[17, 37], V=[57, 77]; U at offset 0, V at offset 1):
    //   Y (4x4):             Interleaved UV backing buffer (2 rows x 4 bytes, pixelStride = 2):
    //     [ 1,  2,  3,  4]     [15, 55, 35, 75]  (U0, V0, U1, V1)
    //     [ 5,  6,  7,  8]     [17, 57, 37, 77]  (U2, V2, U3, V3)
    //     [ 9, 10, 11, 12]
    //     [13, 14, 15, 16]
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            // Row 0
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 30, /* y1= */ 4, /* v= */ 70),
            // Row 1
            createYuy2Macropixel(/* y0= */ 5, /* u= */ 20, /* y1= */ 6, /* v= */ 60),
            createYuy2Macropixel(/* y0= */ 7, /* u= */ 40, /* y1= */ 8, /* v= */ 80),
            // Row 2
            createYuy2Macropixel(/* y0= */ 9, /* u= */ 12, /* y1= */ 10, /* v= */ 52),
            createYuy2Macropixel(/* y0= */ 11, /* u= */ 32, /* y1= */ 12, /* v= */ 72),
            // Row 3
            createYuy2Macropixel(/* y0= */ 13, /* u= */ 22, /* y1= */ 14, /* v= */ 62),
            createYuy2Macropixel(/* y0= */ 15, /* u= */ 42, /* y1= */ 16, /* v= */ 82));
    Plane yPlane =
        new DefaultPlane(ByteBuffer.allocateDirect(16), /* rowStride= */ 4, /* pixelStride= */ 1);
    ByteBuffer interleavedUvBuffer = ByteBuffer.allocateDirect(8);
    interleavedUvBuffer.limit(7);
    ByteBuffer uBuffer = interleavedUvBuffer.slice();
    interleavedUvBuffer.limit(8);
    interleavedUvBuffer.position(1);
    ByteBuffer vBuffer = interleavedUvBuffer.slice();
    interleavedUvBuffer.position(0);
    Plane uPlane = new DefaultPlane(uBuffer, /* rowStride= */ 4, /* pixelStride= */ 2);
    Plane vPlane = new DefaultPlane(vBuffer, /* rowStride= */ 4, /* pixelStride= */ 2);
    ImmutableList<Plane> planes = ImmutableList.of(yPlane, uPlane, vPlane);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 4, /* height= */ 4, planes);

    assertThat(toByteArray(yPlane.getBuffer()))
        .isEqualTo(
            new byte[] {
              1, 2, 3, 4,
              5, 6, 7, 8,
              9, 10, 11, 12,
              13, 14, 15, 16
            });
    assertThat(toByteArray(interleavedUvBuffer))
        .isEqualTo(
            new byte[] {
              15, 55, 35, 75,
              17, 57, 37, 77
            });
  }

  @Test
  public void convertYuy2ToYuv420Planes_oddDimensionsNv21_convertsInterleavedChroma() {
    // Input packedYuy2Buffer (3x3 image, ceil(3 / 2) * 4 = 8 bytes per row in YUY2 order):
    //           Y0  U0  Y1  V0  Y2  U1  Y_pad  V1
    //   Row 0: [ 1, 10,  2, 50,  3, 30,     _, 70]
    //   Row 1: [ 4, 20,  5, 60,  6, 40,     _, 80]
    //   Row 2: [ 7, 12,  8, 52,  9, 32,     _, 72]
    //
    // Expected semi-planar (NV21) planes (Rows 0 & 1 -> U=[15, 35], V=[55, 75];
    // unpaired Row 2 -> U=[12, 32], V=[52, 72]; V at offset 0, U at offset 1):
    //   Y (3x3):         Interleaved VU backing buffer (2 rows x 4 bytes, pixelStride = 2):
    //     [1, 2, 3]        [55, 15, 75, 35]  (V0, U0, V1, U1)
    //     [4, 5, 6]        [52, 12, 72, 32]  (V2, U2, V3, U3)
    //     [7, 8, 9]
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            // Row 0
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 30, /* y1= */ IGNORED, /* v= */ 70),
            // Row 1
            createYuy2Macropixel(/* y0= */ 4, /* u= */ 20, /* y1= */ 5, /* v= */ 60),
            createYuy2Macropixel(/* y0= */ 6, /* u= */ 40, /* y1= */ IGNORED, /* v= */ 80),
            // Row 2
            createYuy2Macropixel(/* y0= */ 7, /* u= */ 12, /* y1= */ 8, /* v= */ 52),
            createYuy2Macropixel(/* y0= */ 9, /* u= */ 32, /* y1= */ IGNORED, /* v= */ 72));
    Plane yPlane =
        new DefaultPlane(ByteBuffer.allocateDirect(9), /* rowStride= */ 3, /* pixelStride= */ 1);
    ByteBuffer interleavedVuBuffer = ByteBuffer.allocateDirect(8);
    interleavedVuBuffer.limit(7);
    ByteBuffer vBuffer = interleavedVuBuffer.slice();
    interleavedVuBuffer.limit(8);
    interleavedVuBuffer.position(1);
    ByteBuffer uBuffer = interleavedVuBuffer.slice();
    interleavedVuBuffer.position(0);
    Plane uPlane = new DefaultPlane(uBuffer, /* rowStride= */ 4, /* pixelStride= */ 2);
    Plane vPlane = new DefaultPlane(vBuffer, /* rowStride= */ 4, /* pixelStride= */ 2);
    ImmutableList<Plane> planes = ImmutableList.of(yPlane, uPlane, vPlane);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 3, /* height= */ 3, planes);

    assertThat(toByteArray(yPlane.getBuffer()))
        .isEqualTo(
            new byte[] {
              1, 2, 3,
              4, 5, 6,
              7, 8, 9
            });
    assertThat(toByteArray(interleavedVuBuffer))
        .isEqualTo(
            new byte[] {
              55, 15, 75, 35,
              52, 12, 72, 32
            });
  }

  @Test
  public void convertYuy2ToYuv420Planes_paddedRowStrides_convertsRowsAndPreservesPadding() {
    // Input packedYuy2Buffer (4x4 image, 8 bytes per row in YUY2 order):
    //           Y0  U0  Y1  V0  Y2  U1  Y3  V1
    //   Row 0: [ 1, 10,  2, 50,  3, 30,  4, 70]
    //   Row 1: [ 5, 20,  6, 60,  7, 40,  8, 80]
    //   Row 2: [ 9, 12, 10, 52, 11, 32, 12, 72]
    //   Row 3: [13, 22, 14, 62, 15, 42, 16, 82]
    //
    // Expected output planes (pre-filled with P = PADDING; 3 padding bytes per non-final row
    // preserved):
    //   Y (4x4, rowStride = 7, 25 bytes):      U (2x2, rowStride = 5, 7 bytes):
    //     [ 1,  2,  3,  4,  P,  P,  P]           [15, 35,  P,  P,  P]  ((10+20)/2, (30+40)/2)
    //     [ 5,  6,  7,  8,  P,  P,  P]           [17, 37]              ((12+22)/2, (32+42)/2)
    //     [ 9, 10, 11, 12,  P,  P,  P]         V (2x2, rowStride = 5, 7 bytes):
    //     [13, 14, 15, 16]                       [55, 75,  P,  P,  P]  ((50+60)/2, (70+80)/2)
    //                                            [57, 77]              ((52+62)/2, (72+82)/2)
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            // Row 0
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 30, /* y1= */ 4, /* v= */ 70),
            // Row 1
            createYuy2Macropixel(/* y0= */ 5, /* u= */ 20, /* y1= */ 6, /* v= */ 60),
            createYuy2Macropixel(/* y0= */ 7, /* u= */ 40, /* y1= */ 8, /* v= */ 80),
            // Row 2
            createYuy2Macropixel(/* y0= */ 9, /* u= */ 12, /* y1= */ 10, /* v= */ 52),
            createYuy2Macropixel(/* y0= */ 11, /* u= */ 32, /* y1= */ 12, /* v= */ 72),
            // Row 3
            createYuy2Macropixel(/* y0= */ 13, /* u= */ 22, /* y1= */ 14, /* v= */ 62),
            createYuy2Macropixel(/* y0= */ 15, /* u= */ 42, /* y1= */ 16, /* v= */ 82));
    Plane yPlane =
        new DefaultPlane(
            createPrefilledByteBuffer(/* size= */ 25, PADDING),
            /* rowStride= */ 7,
            /* pixelStride= */ 1);
    Plane uPlane =
        new DefaultPlane(
            createPrefilledByteBuffer(/* size= */ 7, PADDING),
            /* rowStride= */ 5,
            /* pixelStride= */ 1);
    Plane vPlane =
        new DefaultPlane(
            createPrefilledByteBuffer(/* size= */ 7, PADDING),
            /* rowStride= */ 5,
            /* pixelStride= */ 1);
    ImmutableList<Plane> planes = ImmutableList.of(yPlane, uPlane, vPlane);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 4, /* height= */ 4, planes);

    assertThat(toByteArray(yPlane.getBuffer()))
        .isEqualTo(
            new byte[] {
              1, 2, 3, 4, PADDING, PADDING, PADDING,
              5, 6, 7, 8, PADDING, PADDING, PADDING,
              9, 10, 11, 12, PADDING, PADDING, PADDING,
              13, 14, 15, 16
            });
    assertThat(toByteArray(uPlane.getBuffer()))
        .isEqualTo(new byte[] {15, 35, PADDING, PADDING, PADDING, 17, 37});
    assertThat(toByteArray(vPlane.getBuffer()))
        .isEqualTo(new byte[] {55, 75, PADDING, PADDING, PADDING, 57, 77});
  }

  @Test
  public void convertYuy2ToYuv420Planes_nonZeroPositions_writesFromAndPreservesPositions() {
    // Input packedYuy2Buffer (4x2 image starting at position = 4 after 4 pre-filled bytes X):
    //   Prefix: [ X,  X,  X,  X]
    //            Y0  U0  Y1  V0  Y2  U1  Y3  V1
    //   Row 0:  [ 1, 10,  2, 50,  3, 30,  4, 70]
    //   Row 1:  [ 5, 20,  6, 60,  7, 40,  8, 80]
    //
    // Expected output plane buffers (prefix bytes X = PREFILLED preserved, positions unchanged):
    //   Y (11 bytes, position = 3):  [X, X, X, 1, 2, 3, 4, 5, 6, 7, 8]  (3 prefix + 4x2 Y)
    //   U ( 4 bytes, position = 2):  [X, X, 15, 35]  (2 prefix + [(10+20)/2, (30+40)/2])
    //   V ( 7 bytes, position = 5):  [X, X, X, X, X, 55, 75]  (5 prefix + [(50+60)/2, (70+80)/2])
    ByteBuffer packedYuy2Buffer =
        createByteBuffer(
            // 4-byte prefix before packedYuy2Buffer's initial position
            new byte[] {PREFILLED, PREFILLED, PREFILLED, PREFILLED},
            // Row 0
            createYuy2Macropixel(/* y0= */ 1, /* u= */ 10, /* y1= */ 2, /* v= */ 50),
            createYuy2Macropixel(/* y0= */ 3, /* u= */ 30, /* y1= */ 4, /* v= */ 70),
            // Row 1
            createYuy2Macropixel(/* y0= */ 5, /* u= */ 20, /* y1= */ 6, /* v= */ 60),
            createYuy2Macropixel(/* y0= */ 7, /* u= */ 40, /* y1= */ 8, /* v= */ 80));
    packedYuy2Buffer.position(4);
    ByteBuffer yBuffer = createPrefilledByteBuffer(/* size= */ 11, PREFILLED);
    yBuffer.position(3);
    ByteBuffer uBuffer = createPrefilledByteBuffer(/* size= */ 4, PREFILLED);
    uBuffer.position(2);
    ByteBuffer vBuffer = createPrefilledByteBuffer(/* size= */ 7, PREFILLED);
    vBuffer.position(5);
    Plane yPlane = new DefaultPlane(yBuffer, /* rowStride= */ 4, /* pixelStride= */ 1);
    Plane uPlane = new DefaultPlane(uBuffer, /* rowStride= */ 2, /* pixelStride= */ 1);
    Plane vPlane = new DefaultPlane(vBuffer, /* rowStride= */ 2, /* pixelStride= */ 1);
    ImmutableList<Plane> planes = ImmutableList.of(yPlane, uPlane, vPlane);

    HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
        packedYuy2Buffer, /* width= */ 4, /* height= */ 2, planes);

    assertThat(packedYuy2Buffer.position()).isEqualTo(4);
    assertThat(yBuffer.position()).isEqualTo(3);
    assertThat(uBuffer.position()).isEqualTo(2);
    assertThat(vBuffer.position()).isEqualTo(5);
    assertThat(toByteArray(yBuffer))
        .isEqualTo(new byte[] {PREFILLED, PREFILLED, PREFILLED, 1, 2, 3, 4, 5, 6, 7, 8});
    assertThat(toByteArray(uBuffer)).isEqualTo(new byte[] {PREFILLED, PREFILLED, 15, 35});
    assertThat(toByteArray(vBuffer))
        .isEqualTo(new byte[] {PREFILLED, PREFILLED, PREFILLED, PREFILLED, PREFILLED, 55, 75});
  }

  @Test
  public void convertYuy2ToYuv420Planes_nonDirectYuy2Buffer_throwsIllegalArgumentException() {
    ByteBuffer heapYuy2Buffer = ByteBuffer.allocate(8);
    ImmutableList<Plane> planes = createPlanarPlanes(/* width= */ 2, /* height= */ 2);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
                heapYuy2Buffer, /* width= */ 2, /* height= */ 2, planes));
  }

  @Test
  public void convertYuy2ToYuv420Planes_undersizedYuy2Buffer_throwsIllegalArgumentException() {
    ByteBuffer undersizedYuy2Buffer = ByteBuffer.allocateDirect(7);
    ImmutableList<Plane> planes = createPlanarPlanes(/* width= */ 2, /* height= */ 2);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
                undersizedYuy2Buffer, /* width= */ 2, /* height= */ 2, planes));
  }

  @Test
  public void convertYuy2ToYuv420Planes_invalidPlanes_throwsIllegalArgumentException() {
    ByteBuffer packedYuy2Buffer = ByteBuffer.allocateDirect(8);
    ImmutableList<Plane> twoPlanes =
        createPlanarPlanes(/* width= */ 2, /* height= */ 2).subList(0, 2);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            HardwareBufferJni.INSTANCE.convertYuy2ToYuv420Planes(
                packedYuy2Buffer, /* width= */ 2, /* height= */ 2, twoPlanes));
  }

  private static byte[] createYuy2Macropixel(int y0, int u, int y1, int v) {
    return new byte[] {(byte) y0, (byte) u, (byte) y1, (byte) v};
  }

  private static ImmutableList<Plane> createPlanarPlanes(int width, int height) {
    int chromaWidth = (width + 1) / 2;
    int chromaHeight = (height + 1) / 2;
    return ImmutableList.of(
        new DefaultPlane(
            ByteBuffer.allocateDirect(width * height),
            /* rowStride= */ width,
            /* pixelStride= */ 1),
        new DefaultPlane(
            ByteBuffer.allocateDirect(chromaWidth * chromaHeight),
            /* rowStride= */ chromaWidth,
            /* pixelStride= */ 1),
        new DefaultPlane(
            ByteBuffer.allocateDirect(chromaWidth * chromaHeight),
            /* rowStride= */ chromaWidth,
            /* pixelStride= */ 1));
  }

  private static ByteBuffer createPrefilledByteBuffer(int size, byte fillValue) {
    byte[] bytes = new byte[size];
    Arrays.fill(bytes, fillValue);
    return createByteBuffer(bytes);
  }

  private static ByteBuffer createByteBuffer(byte[]... rows) {
    int totalBytes = 0;
    for (byte[] row : rows) {
      totalBytes += row.length;
    }
    ByteBuffer buffer = ByteBuffer.allocateDirect(totalBytes);
    for (byte[] row : rows) {
      buffer.put(row);
    }
    buffer.flip();
    return buffer;
  }

  private static byte[] toByteArray(ByteBuffer buffer) {
    ByteBuffer copy = buffer.duplicate();
    copy.clear();
    byte[] bytes = new byte[copy.remaining()];
    copy.get(bytes);
    return bytes;
  }
}
