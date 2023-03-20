/*
 * Copyright 2022 The Android Open Source Project
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
package androidx.media3.transformer.mh;

import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_1080P_5_SECOND_HLG10;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_1080P_5_SECOND_HLG10_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_720P_4_SECOND_HDR10;
import static androidx.media3.transformer.AndroidTestUtil.MP4_ASSET_720P_4_SECOND_HDR10_FORMAT;
import static androidx.media3.transformer.AndroidTestUtil.recordTestSkipped;
import static androidx.media3.transformer.mh.FileUtil.maybeAssertFileHasColorTransfer;
import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.transformer.AndroidTestUtil;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportTestResult;
import androidx.media3.transformer.TransformationRequest;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.TransformerAndroidTestRunner;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * {@link Transformer} instrumentation test for applying an {@linkplain
 * TransformationRequest#HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL HDR to SDR tone mapping edit}.
 */
@RunWith(AndroidJUnit4.class)
public class ToneMapHdrToSdrUsingOpenGlTest {
  public static final String TAG = "ToneMapHdrToSdrUsingOpenGlTest";

  @Test
  public void export_toneMap_hlg10File_toneMapsOrThrows() throws Exception {
    String testId = "export_glToneMap_hlg10File_toneMapsOrThrows";

    if (Util.SDK_INT < 29) {
      recordTestSkipped(
          ApplicationProvider.getApplicationContext(),
          testId,
          /* reason= */ "OpenGL-based HDR to SDR tone mapping is only supported on API 29+.");
      return;
    }

    if (!GlUtil.isYuvTargetExtensionSupported()) {
      recordTestSkipped(
          getApplicationContext(), testId, /* reason= */ "Device lacks YUV extension support.");
      return;
    }

    if (AndroidTestUtil.skipAndLogIfFormatsUnsupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_1080P_5_SECOND_HLG10_FORMAT,
        /* outputFormat= */ null)) {
      return;
    }

    Context context = ApplicationProvider.getApplicationContext();

    Transformer transformer =
        new Transformer.Builder(context)
            .setTransformationRequest(
                new TransformationRequest.Builder()
                    .setHdrMode(TransformationRequest.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_1080P_5_SECOND_HLG10));
    try {
      ExportTestResult exportTestResult =
          new TransformerAndroidTestRunner.Builder(context, transformer)
              .build()
              .run(testId, mediaItem);
      Log.i(TAG, "Tone mapped.");
      maybeAssertFileHasColorTransfer(exportTestResult.filePath, C.COLOR_TRANSFER_SDR);
    } catch (ExportException exception) {
      Log.e(TAG, "Error during export.", exception);
      if (exception.errorCode != ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
        throw exception;
      }
    }
  }

  @Test
  public void export_toneMap_hdr10File_toneMapsOrThrows() throws Exception {
    String testId = "export_glToneMap_hdr10File_toneMapsOrThrows";

    if (Util.SDK_INT < 29) {
      recordTestSkipped(
          ApplicationProvider.getApplicationContext(),
          testId,
          /* reason= */ "OpenGL-based HDR to SDR tone mapping is only supported on API 29+.");
      return;
    }

    if (!GlUtil.isYuvTargetExtensionSupported()) {
      recordTestSkipped(
          getApplicationContext(), testId, /* reason= */ "Device lacks YUV extension support.");
      return;
    }

    if (AndroidTestUtil.skipAndLogIfFormatsUnsupported(
        getApplicationContext(),
        testId,
        /* inputFormat= */ MP4_ASSET_720P_4_SECOND_HDR10_FORMAT,
        /* outputFormat= */ null)) {
      return;
    }

    Context context = ApplicationProvider.getApplicationContext();

    Transformer transformer =
        new Transformer.Builder(context)
            .setTransformationRequest(
                new TransformationRequest.Builder()
                    .setHdrMode(TransformationRequest.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
                    .build())
            .build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_720P_4_SECOND_HDR10));
    try {
      ExportTestResult exportTestResult =
          new TransformerAndroidTestRunner.Builder(context, transformer)
              .build()
              .run(testId, mediaItem);
      Log.i(TAG, "Tone mapped.");
      maybeAssertFileHasColorTransfer(exportTestResult.filePath, C.COLOR_TRANSFER_SDR);
    } catch (ExportException exception) {
      Log.e(TAG, "Error during export.", exception);
      if (exception.errorCode != ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED) {
        throw exception;
      }
    }
  }
}
