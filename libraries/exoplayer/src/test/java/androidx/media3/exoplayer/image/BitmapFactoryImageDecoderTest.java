/*
 * Copyright 2023 The Android Open Source Project
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
package androidx.media3.exoplayer.image;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.robolectric.annotation.GraphicsMode.Mode.NATIVE;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.nio.ByteBuffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Unit tests for {@link BitmapFactoryImageDecoder}. */
@RunWith(AndroidJUnit4.class)
@GraphicsMode(value = NATIVE)
public class BitmapFactoryImageDecoderTest {

  private static final String PNG_TEST_IMAGE_PATH = "media/png/non-motion-photo-shortened.png";
  private static final String JPEG_TEST_IMAGE_PATH = "media/jpeg/non-motion-photo-shortened.jpg";

  private static final Format PNG_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.IMAGE_PNG).build();
  private static final Format JPEG_FORMAT =
      new Format.Builder().setSampleMimeType(MimeTypes.IMAGE_JPEG).build();

  private BitmapFactoryImageDecoder decoder;
  private DecoderInputBuffer inputBuffer;
  private ImageOutputBuffer outputBuffer;

  @Before
  public void setUp() {
    decoder =
        new BitmapFactoryImageDecoder.Factory((Context) ApplicationProvider.getApplicationContext())
            .createImageDecoder();
    inputBuffer = decoder.createInputBuffer();
    outputBuffer = decoder.createOutputBuffer();
  }

  @After
  public void tearDown() {
    decoder.release();
  }

  @Test
  public void decode_png_loadsCorrectData() throws Exception {
    byte[] imageData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), PNG_TEST_IMAGE_PATH);

    Bitmap bitmap = decode(PNG_FORMAT, imageData);

    assertThat(
            bitmap.sameAs(
                BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length)))
        .isTrue();
  }

  @Test
  @Config(qualifiers = "w320dp-h470dp")
  public void decode_downscalesToScreenSize() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] imageData = TestUtil.getByteArray(context, JPEG_TEST_IMAGE_PATH);

    Bitmap bitmap = decode(JPEG_FORMAT, imageData);

    // The downscaling only operates on powers of 2, so we just check it's larger than the screen's
    // largest dimension and smaller than double that.
    assertThat(bitmap.getHeight()).isGreaterThan(470);
    assertThat(bitmap.getHeight()).isLessThan(940);
  }

  @Test
  @Config(qualifiers = "w320dp-h470dp")
  public void decode_downscalesToScreenSize_considersTileCount() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Format format =
        JPEG_FORMAT.buildUpon().setTileCountHorizontal(2).setTileCountVertical(3).build();
    byte[] imageData = TestUtil.getByteArray(context, JPEG_TEST_IMAGE_PATH);

    Bitmap bitmap = decode(format, imageData);

    // The downscaling only operates on powers of 2, so we just check it's larger than the screen's
    // largest dimension multiplied by the tile count, and smaller than double that.
    assertThat(bitmap.getHeight()).isGreaterThan(470 * 3);
    assertThat(bitmap.getHeight()).isLessThan(940 * 3);
  }

  @Test
  @Config(qualifiers = "w320dp-h470dp")
  public void decode_withExplicitMaxSize_ignoresScreenSize() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    byte[] imageData = TestUtil.getByteArray(context, JPEG_TEST_IMAGE_PATH);

    BitmapFactoryImageDecoder decoder =
        new BitmapFactoryImageDecoder.Factory((Context) ApplicationProvider.getApplicationContext())
            .setMaxOutputSize(2048)
            .createImageDecoder();
    inputBuffer.data = ByteBuffer.wrap(imageData);
    assertThat(decoder.decode(inputBuffer, outputBuffer, /* reset= */ false)).isNull();
    Bitmap bitmap = outputBuffer.bitmap;

    // The downscaling only operates on powers of 2, so we just check it's smaller than the
    // requested max, and larger than half that.
    assertThat(bitmap.getHeight()).isGreaterThan(1024);
    assertThat(bitmap.getHeight()).isLessThan(2048);
  }

  @Test
  // Configure a device with a screen large enough to display the test JPEG without downscaling,
  // so the Bitmap.sameAs test below passes.
  @Config(qualifiers = "w4500dp-h3200dp")
  public void decode_jpegWithExifRotation_loadsCorrectData() throws Exception {
    byte[] imageData =
        TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), JPEG_TEST_IMAGE_PATH);
    Bitmap bitmapWithoutRotation =
        BitmapFactory.decodeByteArray(imageData, /* offset= */ 0, imageData.length);
    Matrix rotationMatrix = new Matrix();
    rotationMatrix.postRotate(/* degrees= */ 90);
    Bitmap expectedBitmap =
        Bitmap.createBitmap(
            bitmapWithoutRotation,
            /* x= */ 0,
            /* y= */ 0,
            bitmapWithoutRotation.getWidth(),
            bitmapWithoutRotation.getHeight(),
            rotationMatrix,
            /* filter= */ false);

    Bitmap actualBitmap = decode(JPEG_FORMAT, imageData);

    assertThat(actualBitmap.sameAs(expectedBitmap)).isTrue();
  }

  @Test
  public void decodeBitmap_withInvalidData_throws() throws ImageDecoderException {
    assertThrows(
        ImageDecoderException.class, () -> decode(new Format.Builder().build(), new byte[1]));
  }

  private Bitmap decode(Format format, byte[] data) throws Exception {
    inputBuffer.format = format;
    inputBuffer.data = ByteBuffer.wrap(data);
    Exception e = decoder.decode(inputBuffer, outputBuffer, /* reset= */ false);
    if (e != null) {
      throw e;
    }
    return checkNotNull(outputBuffer.bitmap);
  }
}
