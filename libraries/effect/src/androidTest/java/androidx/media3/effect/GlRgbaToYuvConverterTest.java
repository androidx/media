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

import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static androidx.media3.test.utils.BitmapPixelTestUtil.createArgb8888BitmapFromGlTexture;
import static androidx.media3.test.utils.BitmapPixelTestUtil.flipBitmapVertically;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.TestUtil.PSNR_THRESHOLD;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.GlUtil.GlException;
import androidx.media3.common.video.DefaultImagePlanesFrame;
import androidx.media3.common.video.DefaultImagePlanesFrame.DefaultPlane;
import androidx.media3.common.video.ImagePlanesFrame;
import androidx.media3.common.video.ImagePlanesFrame.Plane;
import androidx.media3.test.utils.FakeFrameProcessor;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link GlRgbaToYuvConverter}. */
@RunWith(AndroidJUnit4.class)
public final class GlRgbaToYuvConverterTest {

  private static final String TEST_BITMAP_PATH = "media/png/first_frame_1920x1080.png";

  @Rule public final GlTestRule glTestRule = new GlTestRule();
  @Rule public final TestName testName = new TestName();

  private final Context context = getApplicationContext();
  private final List<Integer> createdTexIds = new ArrayList<>();

  private @MonotonicNonNull GlRgbaToYuvConverter glRgbaToYuvConverter;

  @After
  public void tearDown() throws Exception {
    runAllAndAccumulateExceptions(
        () -> {
          for (int texId : createdTexIds) {
            GlUtil.deleteTexture(texId);
          }
        },
        () -> {
          if (glRgbaToYuvConverter != null) {
            glRgbaToYuvConverter.release();
          }
        });
  }

  @Test
  public void convertToYuv420_sdrBitmap_matchesInputBitmap() throws Exception {
    Bitmap testBitmap = readBitmap(TEST_BITMAP_PATH);
    int inputTexId = createInputTexture(testBitmap);
    ImmutableList<Plane> outputPlanes =
        createPlanarYuv420Planes(testBitmap.getWidth(), testBitmap.getHeight());
    glRgbaToYuvConverter =
        new GlRgbaToYuvConverter(
            context, HardwareBufferJni.INSTANCE, testBitmap.getWidth(), testBitmap.getHeight());

    glRgbaToYuvConverter.convertToYuv420(inputTexId, outputPlanes);

    Bitmap actualBitmap =
        createArgb8888BitmapFromYuv420Planes(
            outputPlanes, testBitmap.getWidth(), testBitmap.getHeight());
    maybeSaveTestBitmap(
        testName.getMethodName(), /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    assertBitmapsAreSimilar(testBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void convertToYuv420_oddDimensions_matchesInputBitmap() throws Exception {
    Bitmap fullBitmap = readBitmap(TEST_BITMAP_PATH);
    Bitmap oddBitmap =
        Bitmap.createBitmap(
            fullBitmap,
            /* x= */ 0,
            /* y= */ 0,
            fullBitmap.getWidth() - 1,
            fullBitmap.getHeight() - 1);
    int inputTexId = createInputTexture(oddBitmap);
    ImmutableList<Plane> outputPlanes =
        createPlanarYuv420Planes(oddBitmap.getWidth(), oddBitmap.getHeight());
    glRgbaToYuvConverter =
        new GlRgbaToYuvConverter(
            context, HardwareBufferJni.INSTANCE, oddBitmap.getWidth(), oddBitmap.getHeight());

    glRgbaToYuvConverter.convertToYuv420(inputTexId, outputPlanes);

    Bitmap actualBitmap =
        createArgb8888BitmapFromYuv420Planes(
            outputPlanes, oddBitmap.getWidth(), oddBitmap.getHeight());
    maybeSaveTestBitmap(
        testName.getMethodName(), /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);
    assertBitmapsAreSimilar(oddBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void convertToYuv420_repeatedCalls_matchesEachInputBitmap() throws Exception {
    Bitmap firstBitmap = readBitmap(TEST_BITMAP_PATH);
    Bitmap secondBitmap = flipBitmapVertically(firstBitmap);
    int firstTexId = createInputTexture(firstBitmap);
    int secondTexId = createInputTexture(secondBitmap);
    ImmutableList<Plane> firstOutputPlanes =
        createPlanarYuv420Planes(firstBitmap.getWidth(), firstBitmap.getHeight());
    ImmutableList<Plane> secondOutputPlanes =
        createPlanarYuv420Planes(secondBitmap.getWidth(), secondBitmap.getHeight());
    glRgbaToYuvConverter =
        new GlRgbaToYuvConverter(
            context, HardwareBufferJni.INSTANCE, firstBitmap.getWidth(), firstBitmap.getHeight());

    glRgbaToYuvConverter.convertToYuv420(firstTexId, firstOutputPlanes);
    glRgbaToYuvConverter.convertToYuv420(secondTexId, secondOutputPlanes);

    Bitmap actualFirstBitmap =
        createArgb8888BitmapFromYuv420Planes(
            firstOutputPlanes, firstBitmap.getWidth(), firstBitmap.getHeight());
    Bitmap actualSecondBitmap =
        createArgb8888BitmapFromYuv420Planes(
            secondOutputPlanes, secondBitmap.getWidth(), secondBitmap.getHeight());
    maybeSaveTestBitmap(
        testName.getMethodName(),
        /* bitmapLabel= */ "actualFirst",
        actualFirstBitmap,
        /* path= */ null);
    maybeSaveTestBitmap(
        testName.getMethodName(),
        /* bitmapLabel= */ "actualSecond",
        actualSecondBitmap,
        /* path= */ null);
    assertBitmapsAreSimilar(firstBitmap, actualFirstBitmap, PSNR_THRESHOLD);
    assertBitmapsAreSimilar(secondBitmap, actualSecondBitmap, PSNR_THRESHOLD);
  }

  private int createInputTexture(Bitmap bitmap) throws GlException {
    int texId = GlUtil.createTexture(bitmap);
    createdTexIds.add(texId);
    return texId;
  }

  private Bitmap createArgb8888BitmapFromYuv420Planes(List<Plane> yuvPlanes, int width, int height)
      throws Exception {
    ImagePlanesToGlTextureConverter yuvToGlConverter =
        new ImagePlanesToGlTextureConverter(
            context,
            HardwareBufferJni.INSTANCE,
            ColorInfo.SDR_BT709_LIMITED,
            /* errorConsumer= */ e -> {
              throw new AssertionError(e);
            });
    try {
      Format yuvFormat =
          new Format.Builder()
              .setWidth(width)
              .setHeight(height)
              .setPixelFormat(ImageFormat.YUV_420_888)
              .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
              .build();
      ImagePlanesFrame yuvFrame = new DefaultImagePlanesFrame.Builder(yuvPlanes, yuvFormat).build();
      GlTextureFrame decodedFrame =
          checkNotNull(
              yuvToGlConverter.convert(
                  yuvFrame,
                  /* glExecutor= */ directExecutor(),
                  /* listenerExecutor= */ directExecutor(),
                  new FakeFrameProcessor.Listener()));
      try {
        return createArgb8888BitmapFromGlTexture(decodedFrame.glTextureInfo);
      } finally {
        decodedFrame.release(/* releaseFence= */ null);
      }
    } finally {
      yuvToGlConverter.close();
    }
  }

  private static ImmutableList<Plane> createPlanarYuv420Planes(int width, int height) {
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
}
