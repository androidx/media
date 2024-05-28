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
package androidx.media3.transformer.mh;

import static androidx.media3.common.MimeTypes.VIDEO_H264;
import static androidx.media3.transformer.AndroidTestUtil.MP4_LONG_ASSET_WITH_INCREASING_TIMESTAMPS_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.ULTRA_HDR_URI_STRING;
import static androidx.media3.transformer.AndroidTestUtil.assumeFormatsSupported;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Util;
import androidx.media3.effect.DefaultVideoFrameProcessor;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.AndroidTestUtil;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportTestResult;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.TransformerAndroidTestRunner;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Checks transcoding speed. */
@RunWith(AndroidJUnit4.class)
public class TranscodeSpeedTest {
  @Rule public final TestName testName = new TestName();

  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void export1920x1080_to1080p_completesWithAtLeast20Fps() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ AndroidTestUtil.MP4_LONG_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT,
        /* outputFormat= */ AndroidTestUtil.MP4_LONG_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT);
    Transformer transformer =
        new Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(new AndroidTestUtil.ForceEncodeEncoderFactory(context))
            .build();
    MediaItem mediaItem =
        MediaItem.fromUri(Uri.parse(MP4_LONG_ASSET_WITH_INCREASING_TIMESTAMPS_URI_STRING))
            .buildUpon()
            .setClippingConfiguration(
                new MediaItem.ClippingConfiguration.Builder().setEndPositionMs(15_000).build())
            .build();
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    assertThat(result.throughputFps).isAtLeast(20);
  }

  @Test
  public void exportImage_to720p_completesWithHighThroughput() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    Format outputFormat =
        new Format.Builder()
            .setSampleMimeType(VIDEO_H264)
            .setFrameRate(30.00f)
            .setCodecs("avc1.42C028")
            .setWidth(1280)
            .setHeight(720)
            .build();
    assumeFormatsSupported(
        context,
        testId,
        /* inputFormat= */ AndroidTestUtil.MP4_LONG_ASSET_WITH_INCREASING_TIMESTAMPS_FORMAT,
        outputFormat);
    DefaultVideoFrameProcessor.Factory videoFrameProcessorFactory =
        new DefaultVideoFrameProcessor.Factory.Builder()
            .setExperimentalRepeatInputBitmapWithoutResampling(true)
            .build();
    Transformer transformer =
        new Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setVideoFrameProcessorFactory(videoFrameProcessorFactory)
            .build();
    boolean isHighPerformance =
        Ascii.toLowerCase(Util.MODEL).contains("pixel")
            && (Ascii.toLowerCase(Util.MODEL).contains("6")
                || Ascii.toLowerCase(Util.MODEL).contains("7")
                || Ascii.toLowerCase(Util.MODEL).contains("8")
                || Ascii.toLowerCase(Util.MODEL).contains("fold")
                || Ascii.toLowerCase(Util.MODEL).contains("tablet"));
    if (Util.SDK_INT == 33 && Ascii.toLowerCase(Util.MODEL).contains("pixel 6")) {
      // Pixel 6 is usually quick, unless it's on API 33.
      isHighPerformance = false;
    }
    // This test uses ULTRA_HDR_URI_STRING because it's high resolution.
    // Ultra HDR gainmap is ignored.
    EditedMediaItem editedMediaItem =
        new EditedMediaItem.Builder(MediaItem.fromUri(ULTRA_HDR_URI_STRING))
            .setFrameRate(30)
            .setDurationUs(isHighPerformance ? 45_000_000 : 15_000_000)
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(
                        Presentation.createForWidthAndHeight(
                            720, 1280, Presentation.LAYOUT_SCALE_TO_FIT))))
            .build();

    ExportTestResult result =
        new TransformerAndroidTestRunner.Builder(context, transformer)
            .build()
            .run(testId, editedMediaItem);

    // This test depends on device GPU performance. Sampling high-resolution textures
    // is expensive. If an extra shader program runs on each frame, devices with slow GPU
    // such as moto e5 play will drop to 5 fps.
    // Devices with a fast GPU and encoder will drop under 300 fps.
    assertThat(result.throughputFps).isAtLeast(isHighPerformance ? 400 : 20);
  }
}
