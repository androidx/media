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

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S;
import static androidx.media3.test.utils.FormatSupportAssumptions.assumeFormatsSupported;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultHardwareBufferEffectsPipeline;
import androidx.media3.effect.ndk.NdkTransformerBuilder;
import androidx.media3.transformer.AndroidTestUtil;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportTestResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.TransformerAndroidTestRunner;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Checks transcoding quality. */
@RunWith(Parameterized.class)
public final class TranscodeQualityTest {
  private static final String TAG = "TranscodeQualityTest";

  @Rule public final TestName testName = new TestName();

  @Parameters(name = "{0}")
  public static ImmutableList<Boolean> params() {
    if (SDK_INT >= 34) {
      return ImmutableList.of(false, true);
    }
    return ImmutableList.of(false);
  }

  @Parameter public boolean usePacketConsumer;

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void transcodeAvcToHevc_ssimIsGreaterThan90Percent() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();

    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS.videoFormat,
        /* outputFormat= */ MP4_ASSET_WITH_INCREASING_TIMESTAMPS
            .videoFormat
            .buildUpon()
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .build());
    assumeFalse(
        (SDK_INT < 33 && (Build.MODEL.equals("SM-F711U1") || Build.MODEL.equals("SM-F926U1")))
            || (SDK_INT == 33 && Build.MODEL.equals("LE2121")));
    Transformer.Builder builder =
        usePacketConsumer
            ? NdkTransformerBuilder.create(context)
                .setHardwareBufferEffectsPipeline(new DefaultHardwareBufferEffectsPipeline())
            : new Transformer.Builder(context);
    Transformer transformer = builder.setVideoMimeType(MimeTypes.VIDEO_H265).build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS.uri));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .setRequestCalculateSsim(true)
            .build()
            .run(testId, editedMediaItem);

    maybeSaveResultFile(result);
    if (result.ssim != ExportTestResult.SSIM_UNSET) {
      assertThat(result.ssim).isGreaterThan(0.90);
    }
  }

  @Test
  public void transcodeAvcToAvc320x240_ssimIsGreaterThan90Percent() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();

    // Don't skip based on format support as input and output formats should be within CDD
    // requirements on all supported API versions, except for wearable devices.
    assumeFalse(Util.isWear(context));

    Transformer.Builder builder =
        usePacketConsumer
            ? NdkTransformerBuilder.create(context)
                .setHardwareBufferEffectsPipeline(new DefaultHardwareBufferEffectsPipeline())
            : new Transformer.Builder(context);
    Transformer transformer =
        builder
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .build();
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse(MP4_ASSET_WITH_INCREASING_TIMESTAMPS_320W_240H_15S.uri));
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .setRequestCalculateSsim(true)
            .build()
            .run(testId, editedMediaItem);

    maybeSaveResultFile(result);
    if (result.ssim != ExportTestResult.SSIM_UNSET) {
      assertThat(result.ssim).isGreaterThan(0.90);
    }
  }

  private void maybeSaveResultFile(ExportTestResult exportTestResult) {
    String fileName = testId + ".mp4";
    try {
      // Use reflection here as this is an experimental API that may not work for all users
      Class<?> testStorageClass = Class.forName("androidx.test.services.storage.TestStorage");
      Method method = testStorageClass.getMethod("openOutputFile", String.class);
      Object testStorage = testStorageClass.getDeclaredConstructor().newInstance();
      try (OutputStream outputStream =
          checkNotNull((OutputStream) method.invoke(testStorage, fileName))) {
        Files.copy(Paths.get(exportTestResult.filePath), outputStream);
      }
    } catch (ClassNotFoundException e) {
      // Do nothing
    } catch (Exception e) {
      Log.e(TAG, "Could not write file to test storage: " + fileName, e);
    }
  }
}
