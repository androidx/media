/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.inspector.mh.analysis;

import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_1080P_5_SECOND_HLG10;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_H264_1080P_10SEC_VIDEO;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_H264_4K_10SEC_VIDEO;
import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.Presentation;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.inspector.FrameExtractor;
import androidx.media3.inspector.FrameExtractor.Frame;
import androidx.media3.test.utils.AssetInfo;
import androidx.media3.test.utils.TestSummaryLogger;
import androidx.test.core.app.ApplicationProvider;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.ArrayList;
import java.util.List;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

/** Instrumentation tests for analyzing frame extractor performance. */
@RunWith(Parameterized.class)
@Ignore(
    "Analysis tests are not used to verify correctness and miss checks for unsupported devices."
        + " Analysis tests take a long time to run - skip them by default when running all"
        + " tests from Android Studio.")
public class FrameExtractorPerformanceAnalysisTest {
  private static final ImmutableList<AssetInfo> INPUT_ASSETS =
      ImmutableList.of(
          MP4_ASSET_H264_4K_10SEC_VIDEO,
          MP4_ASSET_H264_1080P_10SEC_VIDEO,
          MP4_ASSET_1080P_5_SECOND_HLG10);

  private static final long TIMEOUT_SECONDS = 20;
  private static final long VIDEO_DURATION_MS = 5_000;
  private static final long SEEK_DELTA_MS = 420;
  private static final int FRAMES_TO_EXTRACT = 10;

  @Rule public final TestName testName = new TestName();

  @Parameter(0)
  public TestConfig testConfig;

  @Parameters(name = "{0}")
  public static ImmutableList<TestConfig> parameters() {
    ImmutableList.Builder<TestConfig> parametersBuilder = new ImmutableList.Builder<>();
    FrameExtractor.Configuration.Builder configurationBuilder =
        new FrameExtractor.Configuration.Builder();
    for (int i = 0; i < INPUT_ASSETS.size(); i++) {
      for (SeekParameters seekParameters :
          new SeekParameters[] {SeekParameters.EXACT, SeekParameters.CLOSEST_SYNC}) {
        configurationBuilder.setSeekParameters(seekParameters);
        for (MediaCodecSelector mediaCodecSelector :
            new MediaCodecSelector[] {
              MediaCodecSelector.DEFAULT, MediaCodecSelector.PREFER_SOFTWARE
            }) {
          configurationBuilder.setMediaCodecSelector(mediaCodecSelector);
          for (boolean extractHdrFrames : new boolean[] {true, false}) {
            configurationBuilder.setExtractHdrFrames(extractHdrFrames);
            parametersBuilder.add(
                new TestConfig(INPUT_ASSETS.get(i).uri, configurationBuilder.build()));
          }
        }
      }
    }
    return parametersBuilder.build();
  }

  private final Context context = ApplicationProvider.getApplicationContext();
  private @MonotonicNonNull FrameExtractor frameExtractor;

  @After
  public void tearDown() {
    if (frameExtractor != null) {
      frameExtractor.release();
    }
  }

  @Test
  public void analyzeFrameExtractorPerformance() throws Exception {
    frameExtractor = new FrameExtractor(context, testConfig.configuration);
    frameExtractor.setMediaItem(MediaItem.fromUri(testConfig.uri), ImmutableList.of());

    List<ListenableFuture<Frame>> frameFutures = new ArrayList<>();
    long startTimeMs = System.currentTimeMillis();
    long positionMs = 0;
    for (int i = 0; i < FRAMES_TO_EXTRACT; ++i) {
      frameFutures.add(frameExtractor.getFrame(positionMs));
      positionMs = (positionMs + SEEK_DELTA_MS) % VIDEO_DURATION_MS;
    }
    for (ListenableFuture<Frame> frameFuture : frameFutures) {
      frameFuture.get(TIMEOUT_SECONDS, SECONDS).bitmap.recycle();
    }
    long elapsedTimeMs = System.currentTimeMillis() - startTimeMs;

    JSONObject resultJson = testConfig.toJsonObject();
    resultJson.put("elapsed_time_ms", elapsedTimeMs);
    resultJson.put("frames_extracted", frameFutures.size());
    TestSummaryLogger.writeTestSummaryToFile(
        ApplicationProvider.getApplicationContext(),
        /* testId= */ testName.getMethodName(),
        resultJson);
  }

  @Test
  public void analyzeFrameExtractorPerformance_fitIn640x640() throws Exception {
    frameExtractor = new FrameExtractor(context, testConfig.configuration);
    frameExtractor.setMediaItem(
        MediaItem.fromUri(testConfig.uri),
        ImmutableList.of(
            Presentation.createForWidthAndHeight(
                /* width= */ 640, /* height= */ 640, Presentation.LAYOUT_SCALE_TO_FIT)));

    List<ListenableFuture<Frame>> frameFutures = new ArrayList<>();
    long startTimeMs = System.currentTimeMillis();
    long positionMs = 0;
    for (int i = 0; i < FRAMES_TO_EXTRACT; ++i) {
      frameFutures.add(frameExtractor.getFrame(positionMs));
      positionMs = (positionMs + SEEK_DELTA_MS) % VIDEO_DURATION_MS;
    }
    for (ListenableFuture<Frame> frameFuture : frameFutures) {
      frameFuture.get(TIMEOUT_SECONDS, SECONDS).bitmap.recycle();
    }
    long elapsedTimeMs = System.currentTimeMillis() - startTimeMs;

    JSONObject resultJson = testConfig.toJsonObject();
    resultJson.put("elapsed_time_ms", elapsedTimeMs);
    resultJson.put("frames_extracted", frameFutures.size());
    TestSummaryLogger.writeTestSummaryToFile(
        ApplicationProvider.getApplicationContext(),
        /* testId= */ testName.getMethodName(),
        resultJson);
  }

  private static class TestConfig {
    public final String uri;
    public final FrameExtractor.Configuration configuration;

    public TestConfig(String uri, FrameExtractor.Configuration configuration) {
      this.uri = uri;
      this.configuration = configuration;
    }

    @Override
    public String toString() {
      return String.format(
          "%s_SeekParam_%s_Codec_%s_Hdr_%b",
          Uri.parse(uri).getLastPathSegment(),
          seekParametersString(),
          mediaCodecSelectorString(),
          configuration.extractHdrFrames);
    }

    public JSONObject toJsonObject() throws JSONException {
      JSONObject resultJson = new JSONObject();
      resultJson.put("file", Uri.parse(uri).getLastPathSegment());
      resultJson.put("seek_parameters", seekParametersString());
      resultJson.put("codec_selector", mediaCodecSelectorString());
      resultJson.put("extract_hdr", configuration.extractHdrFrames);
      return resultJson;
    }

    private String seekParametersString() {
      if (configuration.seekParameters.equals(SeekParameters.EXACT)) {
        return "EXACT";
      } else if (configuration.seekParameters.equals(SeekParameters.CLOSEST_SYNC)) {
        return "CLOSEST_SYNC";
      } else {
        return String.format(
            "toleranceBefore_%d_after_%d",
            configuration.seekParameters.toleranceBeforeUs,
            configuration.seekParameters.toleranceAfterUs);
      }
    }

    private String mediaCodecSelectorString() {
      if (configuration.mediaCodecSelector.equals(MediaCodecSelector.DEFAULT)) {
        return "DEFAULT";
      } else if (configuration.mediaCodecSelector.equals(MediaCodecSelector.PREFER_SOFTWARE)) {
        return "PREFER_SOFTWARE";
      }
      return "";
    }
  }
}
