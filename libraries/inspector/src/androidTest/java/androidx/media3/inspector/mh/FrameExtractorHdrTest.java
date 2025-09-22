/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.inspector.mh;

import static android.graphics.Bitmap.Config.RGBA_1010102;
import static android.graphics.Bitmap.Config.RGBA_F16;
import static android.graphics.ColorSpace.Named.BT2020_HLG;
import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_COLOR_TEST_1080P_HLG10;
import static androidx.media3.test.utils.AssetInfo.MP4_TRIM_OPTIMIZATION_270;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceDoesNotSupportHdrColorTransfer;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceSupportsHdrColorTransfer;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceSupportsOpenGlToneMapping;
import static androidx.media3.test.utils.TestUtil.assertBitmapsAreSimilar;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.newOutputStream;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import androidx.media3.common.MediaItem;
import androidx.media3.inspector.FrameExtractor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** End-to-end HDR instrumentation test for {@link FrameExtractor}. */
@RunWith(AndroidJUnit4.class)
public class FrameExtractorHdrTest {
  // This file is generated on a Pixel 7, because the emulator isn't able to decode HLG to generate
  // this file.
  private static final String TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH =
      "test-generated-goldens/sample_mp4_first_frame/electrical_colors/tone_map_hlg_to_sdr.png";
  // File names in test-generated-goldens/FrameExtractorTest end with the presentation time of the
  // extracted frame in seconds and milliseconds (_0.000 for 0s ; _1.567 for 1.567 seconds).
  private static final String GOLDEN_ASSET_FOLDER_PATH =
      "test-generated-goldens/FrameExtractorTest/";
  private static final long TIMEOUT_SECONDS = 10;
  private static final float PSNR_THRESHOLD = 25f;

  @Rule public final TestName testName = new TestName();

  private final Context context = ApplicationProvider.getApplicationContext();

  private String testId;
  private @MonotonicNonNull FrameExtractor frameExtractor;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @After
  public void tearDown() {
    if (frameExtractor != null) {
      frameExtractor.release();
    }
  }

  @Test
  public void extractFrame_oneFrameHlg_returnsToneMappedFrame() throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    frameExtractor =
        new FrameExtractor(context, new FrameExtractor.Configuration.Builder().build());
    frameExtractor.setMediaItem(
        MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri), /* effects= */ ImmutableList.of());

    ListenableFuture<FrameExtractor.Frame> frameFuture =
        frameExtractor.getFrame(/* positionMs= */ 0);
    FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
    Bitmap actualBitmap = frame.bitmap;
    Bitmap expectedBitmap = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);

    assertThat(frame.presentationTimeMs).isEqualTo(0);
    assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void extractFrame_oneFrameHlgWithHdrOutput_returnsHlgFrame() throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceSupportsHdrColorTransfer(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    // HLG Bitmaps are only supported on API 34+.
    assumeTrue(SDK_INT >= 34);
    frameExtractor =
        new FrameExtractor(
            context, new FrameExtractor.Configuration.Builder().setExtractHdrFrames(true).build());
    frameExtractor.setMediaItem(
        MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri), /* effects= */ ImmutableList.of());

    ListenableFuture<FrameExtractor.Frame> frameFuture =
        frameExtractor.getFrame(/* positionMs= */ 0);
    FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
    Bitmap actualBitmap = frame.bitmap;
    Bitmap expectedBitmap =
        readBitmap(/* assetString= */ GOLDEN_ASSET_FOLDER_PATH + "hlg10-color-test_0.000.png");
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actualBitmap", actualBitmap, /* path= */ null);

    assertThat(frame.presentationTimeMs).isEqualTo(0);
    assertThat(actualBitmap.getConfig()).isAnyOf(RGBA_1010102, RGBA_F16);
    assertThat(actualBitmap.getColorSpace()).isEqualTo(ColorSpace.get(BT2020_HLG));
    assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void extractFrameAndSaveToJpeg_oneFrameHlgWithHdrOutput_succeeds() throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceSupportsHdrColorTransfer(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    // HLG Bitmaps are only supported on API 34+.
    assumeTrue(SDK_INT >= 34);
    Path temporaryFilePath = new File(context.getExternalCacheDir(), testId + ".jpg").toPath();
    frameExtractor =
        new FrameExtractor(
            context, new FrameExtractor.Configuration.Builder().setExtractHdrFrames(true).build());
    frameExtractor.setMediaItem(
        MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri), /* effects= */ ImmutableList.of());

    ListenableFuture<FrameExtractor.Frame> frameFuture =
        frameExtractor.getFrame(/* positionMs= */ 0);
    FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
    Bitmap actualBitmap = frame.bitmap;
    try (OutputStream outputStream = newOutputStream(temporaryFilePath)) {
      actualBitmap.compress(Bitmap.CompressFormat.JPEG, /* quality= */ 60, outputStream);
    }
    Bitmap bitmapFromFile;
    try (InputStream inputStream = newInputStream(temporaryFilePath)) {
      bitmapFromFile = BitmapFactory.decodeStream(inputStream);
    }

    assertThat(bitmapFromFile.getWidth())
        .isEqualTo(MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat.width);
    assertThat(bitmapFromFile.getHeight())
        .isEqualTo(MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat.height);
    assertThat(bitmapFromFile.getColorSpace()).isEqualTo(ColorSpace.get(BT2020_HLG));
  }

  @Test
  public void extractFrame_oneFrameHlgWithHdrDisplayUnsupported_returnsSdrFrame() throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceDoesNotSupportHdrColorTransfer(
        testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    // HLG Bitmaps are only supported on API 34+.
    assumeTrue(SDK_INT >= 34);
    frameExtractor =
        new FrameExtractor(
            context, new FrameExtractor.Configuration.Builder().setExtractHdrFrames(true).build());
    frameExtractor.setMediaItem(
        MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri), /* effects= */ ImmutableList.of());

    ListenableFuture<FrameExtractor.Frame> frameFuture =
        frameExtractor.getFrame(/* positionMs= */ 0);
    FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
    Bitmap actualBitmap = frame.bitmap;
    Bitmap expectedBitmap = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
    maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);

    assertThat(frame.presentationTimeMs).isEqualTo(0);
    assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
  }

  @Test
  public void
      extractFrame_changeMediaItemFromHdrToSdrWithToneMapping_extractsFrameFromTheCorrectItem()
          throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    frameExtractor =
        new FrameExtractor(context, new FrameExtractor.Configuration.Builder().build());
    frameExtractor.setMediaItem(
        MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri), /* effects= */ ImmutableList.of());
    ListenableFuture<FrameExtractor.Frame> frameFutureFirstItem =
        frameExtractor.getFrame(/* positionMs= */ 0);
    frameExtractor.setMediaItem(
        MediaItem.fromUri(MP4_TRIM_OPTIMIZATION_270.uri), /* effects= */ ImmutableList.of());
    ListenableFuture<FrameExtractor.Frame> frameFutureSecondItem =
        frameExtractor.getFrame(/* positionMs= */ 0);

    FrameExtractor.Frame frameFirstItem = frameFutureFirstItem.get(TIMEOUT_SECONDS, SECONDS);
    Bitmap actualBitmapFirstItem = frameFirstItem.bitmap;
    Bitmap expectedBitmapFirstItem = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
    maybeSaveTestBitmap(
        testId, /* bitmapLabel= */ "firstItem", actualBitmapFirstItem, /* path= */ null);
    FrameExtractor.Frame frameSecondItem = frameFutureSecondItem.get(TIMEOUT_SECONDS, SECONDS);
    Bitmap actualBitmapSecondItem = frameSecondItem.bitmap;
    Bitmap expectedBitmapSecondItem =
        readBitmap(
            /* assetString= */ GOLDEN_ASSET_FOLDER_PATH
                + "internal_emulator_transformer_output_180_rotated_0.000.png");
    maybeSaveTestBitmap(
        testId, /* bitmapLabel= */ "secondItem", actualBitmapSecondItem, /* path= */ null);

    assertThat(frameFirstItem.presentationTimeMs).isEqualTo(0);
    assertBitmapsAreSimilar(expectedBitmapFirstItem, actualBitmapFirstItem, PSNR_THRESHOLD);
    assertThat(frameSecondItem.presentationTimeMs).isEqualTo(0);
    assertBitmapsAreSimilar(expectedBitmapSecondItem, actualBitmapSecondItem, PSNR_THRESHOLD);
  }
}
