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
package androidx.media3.transformer.mh;

import static android.os.Build.VERSION.SDK_INT;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_720P_4_SECOND_HDR10;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_BT2020_SDR;
import static androidx.media3.test.utils.AssetInfo.MP4_ASSET_COLOR_TEST_1080P_HLG10;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceDoesNotSupportHdrColorTransfer;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceSupportsHdrColorTransfer;
import static androidx.media3.test.utils.HdrCapabilitiesUtil.assumeDeviceSupportsOpenGlToneMapping;
import static androidx.media3.transformer.AndroidTestUtil.TestVideoGraphFactory.runAsyncTaskAndWait;
import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.VideoGraph;
import androidx.media3.common.util.GlUtil;
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper;
import androidx.media3.exoplayer.video.VideoFrameReleaseControl;
import androidx.media3.exoplayer.video.VideoSink;
import androidx.media3.transformer.AndroidTestUtil.TestVideoGraphFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** Instrumentation tests for {@link PlaybackVideoGraphWrapper}. */
@RunWith(AndroidJUnit4.class)
public class PlaybackVideoGraphWrapperTest {

  private static final int TEST_TIMEOUT_SECOND = 1;

  @Rule public final TestName testName = new TestName();
  private String testId;

  @Before
  public void setUpTestId() {
    testId = testName.getMethodName();
  }

  @Test
  public void initialize_sdrInput_retainsSdr() throws Exception {
    Format inputFormat = MP4_ASSET_BT2020_SDR.videoFormat;
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    runAsyncTaskAndWait(() -> sink.initialize(inputFormat), TEST_TIMEOUT_SECOND);

    assertThat(testVideoGraphFactory.getOutputColorInfo()).isEqualTo(inputFormat.colorInfo);
  }

  @Test
  public void initialize_hdr10InputUnsupported_toneMapsToSdr() throws Exception {
    Format inputFormat = MP4_ASSET_720P_4_SECOND_HDR10.videoFormat;
    assumeDeviceSupportsOpenGlToneMapping(testId, inputFormat);
    assumeDeviceDoesNotSupportHdrColorTransfer(testId, inputFormat);
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    runAsyncTaskAndWait(() -> sink.initialize(inputFormat), TEST_TIMEOUT_SECOND);

    assertThat(testVideoGraphFactory.getOutputColorInfo()).isEqualTo(ColorInfo.SDR_BT709_LIMITED);
  }

  @Test
  public void initialize_hlgInputUnsupported_toneMapsToSdr() throws Exception {
    Format inputFormat = MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat;
    assumeDeviceSupportsOpenGlToneMapping(testId, inputFormat);
    assumeDeviceDoesNotSupportHdrColorTransfer(testId, inputFormat);
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    runAsyncTaskAndWait(() -> sink.initialize(inputFormat), TEST_TIMEOUT_SECOND);

    ColorInfo expectedColorInfo;
    // HLG is converted to PQ on API 33.
    if (SDK_INT < 34 && GlUtil.isBt2020PqExtensionSupported()) {
      expectedColorInfo =
          inputFormat.colorInfo.buildUpon().setColorTransfer(C.COLOR_TRANSFER_ST2084).build();
    } else {
      expectedColorInfo = ColorInfo.SDR_BT709_LIMITED;
    }
    assertThat(testVideoGraphFactory.getOutputColorInfo()).isEqualTo(expectedColorInfo);
  }

  @Test
  public void initialize_hdr10InputSupported_retainsHdr() throws Exception {
    Format inputFormat = MP4_ASSET_720P_4_SECOND_HDR10.videoFormat;
    assumeDeviceSupportsHdrColorTransfer(testId, inputFormat);
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    runAsyncTaskAndWait(() -> sink.initialize(inputFormat), TEST_TIMEOUT_SECOND);

    assertThat(testVideoGraphFactory.getOutputColorInfo()).isEqualTo(inputFormat.colorInfo);
  }

  @Test
  public void initialize_hlgInputSupported_retainsHdr() throws Exception {
    Format inputFormat = MP4_ASSET_COLOR_TEST_1080P_HLG10.videoFormat;
    assumeDeviceSupportsHdrColorTransfer(testId, inputFormat);
    TestVideoGraphFactory testVideoGraphFactory = new TestVideoGraphFactory();
    PlaybackVideoGraphWrapper playbackVideoGraphWrapper =
        createPlaybackVideoGraphWrapper(testVideoGraphFactory);
    VideoSink sink = playbackVideoGraphWrapper.getSink(/* inputIndex= */ 0);

    runAsyncTaskAndWait(() -> sink.initialize(inputFormat), TEST_TIMEOUT_SECOND);

    assertThat(testVideoGraphFactory.getOutputColorInfo()).isEqualTo(inputFormat.colorInfo);
  }

  private static PlaybackVideoGraphWrapper createPlaybackVideoGraphWrapper(
      VideoGraph.Factory videoGraphFactory) {
    Context context = ApplicationProvider.getApplicationContext();
    return new PlaybackVideoGraphWrapper.Builder(context, createVideoFrameReleaseControl())
        .setVideoGraphFactory(videoGraphFactory)
        .build();
  }

  private static VideoFrameReleaseControl createVideoFrameReleaseControl() {
    Context context = ApplicationProvider.getApplicationContext();
    VideoFrameReleaseControl.FrameTimingEvaluator frameTimingEvaluator =
        new VideoFrameReleaseControl.FrameTimingEvaluator() {
          @Override
          public boolean shouldForceReleaseFrame(long earlyUs, long elapsedSinceLastReleaseUs) {
            return false;
          }

          @Override
          public boolean shouldDropFrame(
              long earlyUs, long elapsedRealtimeUs, boolean isLastFrame) {
            return false;
          }

          @Override
          public boolean shouldIgnoreFrame(
              long earlyUs,
              long positionUs,
              long elapsedRealtimeUs,
              boolean isLastFrame,
              boolean treatDroppedBuffersAsSkipped) {
            return false;
          }
        };
    return new VideoFrameReleaseControl(
        context, frameTimingEvaluator, /* allowedJoiningTimeMs= */ 0);
  }
}
