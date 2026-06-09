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
package androidx.media3.session.artwork;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import androidx.media3.session.MediaSession;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link BitmapProcessor}. */
@RunWith(AndroidJUnit4.class)
public final class BitmapProcessorTest {

  private final Context context = ApplicationProvider.getApplicationContext();

  @Test
  public void scaleDownIfExceedsLimit_smallBitmap_returnsOriginalBytes() throws Exception {
    byte[] inputBytes = createTestBitmapBytes(100, 100, /* hasAlpha= */ false);

    // 100x100 is within 500 max bound.
    byte[] outputBytes =
        BitmapProcessor.scaleDownIfExceedsLimit(inputBytes, /* maxDimension= */ 500);

    assertThat(outputBytes).isSameInstanceAs(inputBytes);
  }

  @Test
  public void scaleDownIfExceedsLimit_largeWideBitmap_scalesDownProportionally() throws Exception {
    // 2000x1000 exceeds 1000 max bound. Should scale down to 1000x500.
    byte[] inputBytes = createTestBitmapBytes(2000, 1000, /* hasAlpha= */ false);

    byte[] outputBytes =
        BitmapProcessor.scaleDownIfExceedsLimit(inputBytes, /* maxDimension= */ 1000);

    assertThat(outputBytes).isNotSameInstanceAs(inputBytes);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.length, options);
    assertThat(options.outWidth).isEqualTo(1000);
    assertThat(options.outHeight).isEqualTo(500);
  }

  @Test
  public void scaleDownIfExceedsLimit_largeTallBitmap_scalesDownProportionally() throws Exception {
    // 1000x2000 exceeds 1000 max bound. Should scale down to 500x1000.
    byte[] inputBytes = createTestBitmapBytes(1000, 2000, /* hasAlpha= */ false);

    byte[] outputBytes =
        BitmapProcessor.scaleDownIfExceedsLimit(inputBytes, /* maxDimension= */ 1000);

    assertThat(outputBytes).isNotSameInstanceAs(inputBytes);
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.length, options);
    assertThat(options.outWidth).isEqualTo(500);
    assertThat(options.outHeight).isEqualTo(1000);
  }

  @Test
  public void scaleDownIfExceedsLimit_transparentBitmap_preservesAlphaAndUsesPng()
      throws Exception {
    byte[] inputBytes = createTestBitmapBytes(1500, 1500, /* hasAlpha= */ true);

    byte[] outputBytes =
        BitmapProcessor.scaleDownIfExceedsLimit(inputBytes, /* maxDimension= */ 1000);

    // Verify it was compressed as PNG by checking 8 bytes.
    // See https://www.w3.org/TR/png-3/#3PNGsignature
    assertThat(outputBytes[0]).isEqualTo((byte) 0x89);
    assertThat(outputBytes[1]).isEqualTo((byte) 0x50); // P
    assertThat(outputBytes[2]).isEqualTo((byte) 0x4E); // N
    assertThat(outputBytes[3]).isEqualTo((byte) 0x47); // G
    assertThat(outputBytes[4]).isEqualTo((byte) 0x0D); // DOS carriage return
    assertThat(outputBytes[5]).isEqualTo((byte) 0x0A); //     and line feed
    assertThat(outputBytes[6]).isEqualTo((byte) 0x1A); // DOS End-of-File
    assertThat(outputBytes[7]).isEqualTo((byte) 0x0A); // unix-style line end

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap decoded = BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.length, options);
    assertThat(decoded.hasAlpha()).isTrue();
    assertThat(decoded.getWidth()).isEqualTo(1000);
    assertThat(decoded.getHeight()).isEqualTo(1000);
  }

  @Test
  public void scaleDownIfExceedsLimit_opaqueBitmap_usesJpeg() throws Exception {
    byte[] inputBytes = createTestBitmapBytes(1500, 1500, /* hasAlpha= */ false);

    byte[] outputBytes =
        BitmapProcessor.scaleDownIfExceedsLimit(inputBytes, /* maxDimension= */ 1000);

    Bitmap decoded = BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.length);
    assertThat(decoded.getWidth()).isEqualTo(1000);
    assertThat(decoded.getHeight()).isEqualTo(1000);
    // Verify it was compressed as JPEG (JPEG starts with FF D8)
    // https://en.wikipedia.org/wiki/JPEG_File_Interchange_Format
    assertThat(outputBytes[0]).isEqualTo((byte) 0xFF);
    assertThat(outputBytes[1]).isEqualTo((byte) 0xD8);
    // Verify End Of Image (EOI)
    assertThat(outputBytes[outputBytes.length - 2]).isEqualTo((byte) 0xFF);
    assertThat(outputBytes[outputBytes.length - 1]).isEqualTo((byte) 0xD9);
  }

  @Test
  public void scaleDownIfExceedsLimit_emptyBytes_throwsException() {
    byte[] emptyBytes = new byte[0];

    assertThrows(
        BitmapProcessor.BitmapProcessingException.class,
        () -> BitmapProcessor.scaleDownIfExceedsLimit(emptyBytes, /* maxDimension= */ 1000));
  }

  @Test
  public void scaleDownIfExceedsLimit_nullBytes_throwsException() {
    assertThrows(
        BitmapProcessor.BitmapProcessingException.class,
        () -> BitmapProcessor.scaleDownIfExceedsLimit(/* data= */ null, /* maxDimension= */ 1000));
  }

  @Test
  public void scaleDownIfExceedsLimit_corruptedLargeBitmap_throwsException() {
    byte[] largeBytes = createTestBitmapBytes(1500, 1500, /* hasAlpha= */ false);
    // Retain the header (first 100 bytes) and truncate the rest.
    byte[] corruptedBytes = Arrays.copyOf(largeBytes, 100);

    assertThrows(
        BitmapProcessor.BitmapProcessingException.class,
        () -> BitmapProcessor.scaleDownIfExceedsLimit(corruptedBytes, /* maxDimension= */ 1000));
  }

  @Test
  public void scaleDownIfExceedsLimit_completelyCorruptedBitmap_throwsException() {
    byte[] corruptedBytes = new byte[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

    assertThrows(
        BitmapProcessor.BitmapProcessingException.class,
        () -> BitmapProcessor.scaleDownIfExceedsLimit(corruptedBytes, /* maxDimension= */ 1000));
  }

  @Test
  public void scaleDownIfExceedsLimit_withContext_usesLimitFromMediaSession() throws Exception {
    int expectedLimit = MediaSession.getBitmapDimensionLimit(context);
    int inputDimension = expectedLimit * 2;
    byte[] inputBytes =
        createTestBitmapBytes(inputDimension, inputDimension, /* hasAlpha= */ false);

    byte[] outputBytes = BitmapProcessor.scaleDownIfExceedsLimit(context, inputBytes);

    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(outputBytes, 0, outputBytes.length, options);
    assertThat(options.outWidth).isEqualTo(expectedLimit);
    assertThat(options.outHeight).isEqualTo(expectedLimit);
  }

  @Test
  public void calculateInSampleSize_dimensionsWithinRequest_returnsOne() {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.outWidth = 1500;
    options.outHeight = 1500;

    int inSampleSize = BitmapProcessor.calculateInSampleSize(options, 2048, 2048);

    assertThat(inSampleSize).isEqualTo(1);
  }

  @Test
  public void calculateInSampleSize_dimensionsLargerThanRequest_calculatesCorrectly() {
    BitmapFactory.Options options = new BitmapFactory.Options();
    // halfWidth = 4500, halfHeight = 4500.
    // inSampleSize = 1: 4500 >= 2048 && 4500 >= 2048 -> true, sampleSize = 2.
    // inSampleSize = 2: 2250 >= 2048 && 2250 >= 2048 -> true, sampleSize = 4.
    // inSampleSize = 4: 1125 >= 2048 && 1125 >= 2048 -> false, terminates.
    options.outWidth = 9000;
    options.outHeight = 9000;

    int inSampleSize = BitmapProcessor.calculateInSampleSize(options, 2048, 2048);

    assertThat(inSampleSize).isEqualTo(4);
  }

  private static byte[] createTestBitmapBytes(int width, int height, boolean hasAlpha) {
    Bitmap bitmap =
        Bitmap.createBitmap(
            width, height, hasAlpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565);
    if (hasAlpha) {
      bitmap.eraseColor(Color.TRANSPARENT);
      bitmap.setPixel(0, 0, Color.RED);
    } else {
      bitmap.eraseColor(Color.RED);
    }
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    bitmap.compress(
        hasAlpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG,
        /* quality= */ 100,
        outputStream);
    byte[] data = outputStream.toByteArray();
    bitmap.recycle();
    return data;
  }
}
