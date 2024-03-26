/*
 * Copyright 2024 The Android Open Source Project
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
 *
 */

package androidx.media3.transformer.mh;

import static androidx.media3.test.utils.BitmapPixelTestUtil.MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_LUMA;
import static androidx.media3.test.utils.BitmapPixelTestUtil.getBitmapAveragePixelAbsoluteDifferenceFp16;
import static androidx.media3.test.utils.BitmapPixelTestUtil.maybeSaveTestBitmap;
import static androidx.media3.test.utils.BitmapPixelTestUtil.readBitmap;
import static androidx.media3.test.utils.TestUtil.retrieveTrackFormat;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_1080P_5_SECOND_HLG10_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.ULTRA_HDR_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.extractBitmapsFromVideo;
import static androidx.media3.transformer.AndroidTestUtil.recordTestSkipped;
import static androidx.media3.transformer.SequenceEffectTestUtil.NO_EFFECT;
import static androidx.media3.transformer.SequenceEffectTestUtil.oneFrameFromImage;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import androidx.annotation.RequiresApi;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.transformer.AndroidTestUtil;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.ExportTestResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.TransformerAndroidTestRunner;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.IOException;
import java.util.List;
import org.json.JSONException;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Tests for Ultra HDR support in Transformer that should only run in mobile harness. */
@RunWith(AndroidJUnit4.class)
public final class TransformerMhUltraHdrPixelTest {

  private static final String PNG_ASSET_BASE_PATH =
      "test-generated-goldens/TransformerUltraHdrPixelTest";

  @Rule public final TestName testName = new TestName();
  private final Context context = ApplicationProvider.getApplicationContext();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void exportUltraHdrImage_withUltraHdrEnabledOnSupportedDevice_succeeds() throws Exception {
    assumeDeviceSupportsUltraHdrEditing();
    Composition composition =
        createUltraHdrComposition(oneFrameFromImage(ULTRA_HDR_URI_STRING, NO_EFFECT));

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, new Transformer.Builder(context).build())
            .build()
            .run(testId, composition);

    assertThat(result.filePath).isNotNull();
    ColorInfo colorInfo =
        retrieveTrackFormat(context, result.filePath, C.TRACK_TYPE_VIDEO).colorInfo;
    assertThat(colorInfo.colorSpace).isEqualTo(C.COLOR_SPACE_BT2020);
    assertThat(colorInfo.colorTransfer).isEqualTo(C.COLOR_TRANSFER_HLG);
    assertFp16BitmapsMatchExpectedAndSave(
        extractBitmapsFromVideo(context, result.filePath, Config.RGBA_F16), testId);
  }

  @RequiresApi(29) // getBitmapAveragePixelAbsoluteDifferenceFp16()
  public static void assertFp16BitmapsMatchExpectedAndSave(
      List<Bitmap> actualBitmaps, String testId) throws IOException {
    for (int i = 0; i < actualBitmaps.size(); i++) {
      maybeSaveTestBitmap(
          testId, /* bitmapLabel= */ String.valueOf(i), actualBitmaps.get(i), /* path= */ null);
    }

    for (int i = 0; i < actualBitmaps.size(); i++) {
      String subTestId = testId + "_" + i;
      String expectedPath = Util.formatInvariant("%s/%s.png", PNG_ASSET_BASE_PATH, subTestId);
      Bitmap expectedBitmap = readBitmap(expectedPath);

      float averagePixelAbsoluteDifference =
          getBitmapAveragePixelAbsoluteDifferenceFp16(expectedBitmap, actualBitmaps.get(i));
      assertWithMessage("For expected bitmap " + expectedPath)
          .that(averagePixelAbsoluteDifference)
          .isAtMost(MAXIMUM_AVERAGE_PIXEL_ABSOLUTE_DIFFERENCE_LUMA);
    }
  }

  private void assumeDeviceSupportsUltraHdrEditing()
      throws JSONException, IOException, DecoderQueryException {
    if (Util.SDK_INT < 34) {
      recordTestSkipped(
          getApplicationContext(), testId, "Ultra HDR is not supported on this API level.");
      throw new AssumptionViolatedException("Ultra HDR is not supported on this API level.");
    }
    AndroidTestUtil.assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_1080P_5_SECOND_HLG10_FORMAT,
        /* outputFormat= */ null);
  }

  private static Composition createUltraHdrComposition(
      EditedMediaItem editedMediaItem, EditedMediaItem... editedMediaItems) {
    Composition.Builder builder =
        new Composition.Builder(new EditedMediaItemSequence(editedMediaItem, editedMediaItems))
            .experimentalSetRetainHdrFromUltraHdrImage(true);
    return builder.build();
  }
}
