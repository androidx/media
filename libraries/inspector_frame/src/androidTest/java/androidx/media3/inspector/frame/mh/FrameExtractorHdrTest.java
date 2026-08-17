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
package androidx.media3.inspector.frame.mh;

import static android.graphics.Bitmap.Config.RGBA_1010102;
import static android.graphics.Bitmap.Config.RGBA_F16;
import static android.graphics.ColorSpace.Named.BT2020_HLG;
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

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.Gainmap;
import androidx.media3.common.MediaItem;
import androidx.media3.inspector.frame.FrameExtractor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
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
  // This file is used to verify the UltraHDR pipeline and is generated as follows:
  // 1. Extract SDR frame using our frame extractor.
  // 2. Extract HDR frame using ffmpeg from the test video.
  // 3. Generate UltraHDR frame using libultrahdr's ultrahdr_app as a JPEG (UltraHDR is standardized
  //    as a JPEG extension embedding the gainmap inside Multi-Picture Format metadata segments).
  private static final String ULTRA_HDR_JPG_ASSET_PATH =
      GOLDEN_ASSET_FOLDER_PATH + "ultra_hdr-color-test_0.000.jpg";
  private static final long TIMEOUT_SECONDS = 10;
  private static final float PSNR_THRESHOLD = 25f;

  @Rule public final TestName testName = new TestName();

  private final Context context = ApplicationProvider.getApplicationContext();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void extractFrame_oneFrameHlg_returnsToneMappedFrame() throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .build()) {

      ListenableFuture<FrameExtractor.Frame> frameFuture =
          frameExtractor.getFrame(/* positionMs= */ 0);
      FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
      Bitmap actualBitmap = frame.bitmap;
      Bitmap expectedBitmap = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
      maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);

      assertThat(frame.presentationTimeMs).isEqualTo(0);
      assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 34) // HLG Bitmaps are only supported on API 34+.
  public void extractFrame_oneFrameHlgWithHdrOutput_returnsHlgFrame() throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceSupportsHdrColorTransfer(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .setExtractHdrFrames(true)
            .build()) {

      ListenableFuture<FrameExtractor.Frame> frameFuture =
          frameExtractor.getFrame(/* positionMs= */ 0);
      FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
      Bitmap actualBitmap = frame.bitmap;
      Bitmap expectedBitmap =
          readBitmap(/* assetString= */ GOLDEN_ASSET_FOLDER_PATH + "hlg10-color-test_0.000.png");
      maybeSaveTestBitmap(
          testId, /* bitmapLabel= */ "actualBitmap", actualBitmap, /* path= */ null);

      assertThat(frame.presentationTimeMs).isEqualTo(0);
      assertThat(actualBitmap.getConfig()).isAnyOf(RGBA_1010102, RGBA_F16);
      assertThat(actualBitmap.getColorSpace()).isEqualTo(ColorSpace.get(BT2020_HLG));
      assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 34) // UltraHDR Bitmaps are only supported on API 34+.
  public void extractFrame_oneFrameHlgWithUltraHdrOutput_returnsExpectedUltraHdrFrame()
      throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceSupportsHdrColorTransfer(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .setEnableUltraHdr(true)
            .build()) {

      ListenableFuture<FrameExtractor.Frame> frameFuture =
          frameExtractor.getFrame(/* positionMs= */ 0);
      FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
      Bitmap actualBitmap = frame.bitmap;
      Bitmap expectedBitmap = readBitmap(ULTRA_HDR_JPG_ASSET_PATH);
      Bitmap expectedToneMappedBitmap = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
      maybeSaveTestBitmap(
          testId, /* bitmapLabel= */ "actualBitmap", actualBitmap, /* path= */ null);

      assertThat(frame.presentationTimeMs).isEqualTo(0);
      assertThat(actualBitmap.getConfig()).isEqualTo(Bitmap.Config.ARGB_8888);
      assertThat(actualBitmap.hasGainmap()).isTrue();
      assertThat(expectedBitmap.hasGainmap()).isTrue();

      // Compare SDR base
      assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
      assertBitmapsAreSimilar(expectedToneMappedBitmap, actualBitmap, PSNR_THRESHOLD);

      // Compare Gainmap metadata
      Gainmap actualGainmap = actualBitmap.getGainmap();
      assertThat(actualGainmap).isNotNull();
      Gainmap expectedGainmap = expectedBitmap.getGainmap();
      assertThat(expectedGainmap).isNotNull();
      float metadataTolerance = 0.01f;
      for (int i = 0; i < 3; i++) {
        assertThat(actualGainmap.getRatioMin()[i])
            .isWithin(metadataTolerance)
            .of(expectedGainmap.getRatioMin()[i]);
        assertThat(actualGainmap.getRatioMax()[i])
            .isWithin(metadataTolerance)
            .of(expectedGainmap.getRatioMax()[i]);
        assertThat(actualGainmap.getGamma()[i])
            .isWithin(metadataTolerance)
            .of(expectedGainmap.getGamma()[i]);
      }
      assertThat(actualGainmap.getDisplayRatioForFullHdr())
          .isWithin(metadataTolerance)
          .of(expectedGainmap.getDisplayRatioForFullHdr());
      assertThat(actualGainmap.getMinDisplayRatioForHdrTransition())
          .isWithin(metadataTolerance)
          .of(expectedGainmap.getMinDisplayRatioForHdrTransition());

      // Compare Gainmap contents
      Bitmap actualGainmapBitmap = actualGainmap.getGainmapContents();
      Bitmap expectedGainmapBitmap = expectedGainmap.getGainmapContents();
      assertBitmapsAreSimilar(expectedGainmapBitmap, actualGainmapBitmap, PSNR_THRESHOLD);
    }
  }

  @Test
  @SdkSuppress(minSdkVersion = 34) // HLG Bitmaps are only supported on API 34+.
  public void extractFrameAndSaveToJpeg_oneFrameHlgWithHdrOutput_succeeds() throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceSupportsHdrColorTransfer(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    Path temporaryFilePath = new File(context.getExternalCacheDir(), testId + ".jpg").toPath();
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .setExtractHdrFrames(true)
            .build()) {

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
  }

  @Test
  @SdkSuppress(minSdkVersion = 34) // HLG Bitmaps are only supported on API 34+.
  public void extractFrame_oneFrameHlgWithHdrDisplayUnsupported_returnsSdrFrame() throws Exception {
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    assumeDeviceDoesNotSupportHdrColorTransfer(
        testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .setExtractHdrFrames(true)
            .build()) {

      ListenableFuture<FrameExtractor.Frame> frameFuture =
          frameExtractor.getFrame(/* positionMs= */ 0);
      FrameExtractor.Frame frame = frameFuture.get(TIMEOUT_SECONDS, SECONDS);
      Bitmap actualBitmap = frame.bitmap;
      Bitmap expectedBitmap = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
      maybeSaveTestBitmap(testId, /* bitmapLabel= */ "actual", actualBitmap, /* path= */ null);

      assertThat(frame.presentationTimeMs).isEqualTo(0);
      assertBitmapsAreSimilar(expectedBitmap, actualBitmap, PSNR_THRESHOLD);
    }
  }

  @Test
  public void
      extractFrame_changeMediaItemFromHdrToSdrWithToneMapping_extractsFrameFromTheCorrectItem()
          throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);

    FrameExtractor.Frame frameFirstItem;
    FrameExtractor.Frame frameSecondItem;
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .build()) {
      ListenableFuture<FrameExtractor.Frame> frameFutureFirstItem =
          frameExtractor.getFrame(/* positionMs= */ 0);
      frameFirstItem = frameFutureFirstItem.get(TIMEOUT_SECONDS, SECONDS);

      try (FrameExtractor nestedFrameExtractor =
          new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_TRIM_OPTIMIZATION_270.uri))
              .build()) {
        ListenableFuture<FrameExtractor.Frame> frameFutureSecondItem =
            nestedFrameExtractor.getFrame(/* positionMs= */ 0);
        frameSecondItem = frameFutureSecondItem.get(TIMEOUT_SECONDS, SECONDS);
      }
    }

    Bitmap actualBitmapFirstItem = frameFirstItem.bitmap;
    Bitmap expectedBitmapFirstItem = readBitmap(TONE_MAP_HLG_TO_SDR_PNG_ASSET_PATH);
    maybeSaveTestBitmap(
        testId, /* bitmapLabel= */ "firstItem", actualBitmapFirstItem, /* path= */ null);
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

  @Test
  @SdkSuppress(minSdkVersion = 34) // HLG Bitmaps are only supported on API 34+.
  public void extractFrame_changeMediaItemFromHdrToSdrWithHdrOutput_succeeds() throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping. This check verifies
    // that HDR input can be sampled by the GL pipeline.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);

    FrameExtractor.Frame frameFirstItem;
    FrameExtractor.Frame frameSecondItem;
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .setExtractHdrFrames(true)
            .build()) {
      ListenableFuture<FrameExtractor.Frame> frameFutureFirstItem =
          frameExtractor.getFrame(/* positionMs= */ 0);
      frameFirstItem = frameFutureFirstItem.get(TIMEOUT_SECONDS, SECONDS);

      try (FrameExtractor nestedFrameExtractor =
          new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_TRIM_OPTIMIZATION_270.uri))
              .setExtractHdrFrames(true)
              .build()) {
        ListenableFuture<FrameExtractor.Frame> frameFutureSecondItem =
            nestedFrameExtractor.getFrame(/* positionMs= */ 0);
        frameSecondItem = frameFutureSecondItem.get(TIMEOUT_SECONDS, SECONDS);
      }
    }

    assertThat(frameFirstItem.presentationTimeMs).isEqualTo(0);
    assertThat(frameSecondItem.presentationTimeMs).isEqualTo(0);
  }

  @Test
  @SdkSuppress(minSdkVersion = 34) // HLG Bitmaps are only supported on API 34+.
  public void extractFrame_ultraHdr_changeMediaItemFromHdrToSdrWithHdrOutput_succeeds()
      throws Exception {
    // TODO: b/438478509 - rename assumeDeviceSupportsOpenGlToneMapping. This check verifies
    // that HDR input can be sampled by the GL pipeline.
    assumeDeviceSupportsOpenGlToneMapping(testId, MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat);

    FrameExtractor.Frame frameFirstItem;
    FrameExtractor.Frame frameSecondItem;
    try (FrameExtractor frameExtractor =
        new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_ASSET_COLOR_TEST_1080P_HLG10.uri))
            .setEnableUltraHdr(true)
            .build()) {
      ListenableFuture<FrameExtractor.Frame> frameFutureFirstItem =
          frameExtractor.getFrame(/* positionMs= */ 0);
      frameFirstItem = frameFutureFirstItem.get(TIMEOUT_SECONDS, SECONDS);

      try (FrameExtractor nestedFrameExtractor =
          new FrameExtractor.Builder(context, MediaItem.fromUri(MP4_TRIM_OPTIMIZATION_270.uri))
              .setEnableUltraHdr(true)
              .build()) {
        ListenableFuture<FrameExtractor.Frame> frameFutureSecondItem =
            nestedFrameExtractor.getFrame(/* positionMs= */ 0);
        frameSecondItem = frameFutureSecondItem.get(TIMEOUT_SECONDS, SECONDS);
      }
    }

    assertThat(frameFirstItem.presentationTimeMs).isEqualTo(0);
    assertThat(frameSecondItem.presentationTimeMs).isEqualTo(0);
  }
}
