/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.test.exoplayer.playback.gts;

import static androidx.media3.test.exoplayer.playback.gts.GtsTestUtil.shouldSkipWidevineTest;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import android.content.pm.PackageManager;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil.DecoderQueryException;
import androidx.media3.test.utils.ActionSchedule;
import androidx.media3.test.utils.HostActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests DASH playbacks using {@link ExoPlayer}. */
@RunWith(AndroidJUnit4.class)
public final class DashStreamingTest {

  private static final String TAG = "DashStreamingTest";

  private static final ActionSchedule SEEKING_SCHEDULE =
      new ActionSchedule.Builder(TAG)
          .waitForPlaybackState(Player.STATE_READY)
          .delay(10000)
          .seekAndWait(15000)
          .delay(10000)
          .seek(30000)
          .seek(31000)
          .seek(32000)
          .seek(33000)
          .seekAndWait(34000)
          .delay(1000)
          .pause()
          .delay(1000)
          .play()
          .delay(1000)
          .pause()
          .seekAndWait(120000)
          .delay(1000)
          .play()
          .build();
  private static final ActionSchedule RENDERER_DISABLING_SCHEDULE =
      new ActionSchedule.Builder(TAG)
          .waitForPlaybackState(Player.STATE_READY)
          // Wait 10 seconds, disable the video renderer, wait another 10 seconds and enable it
          // again.
          .delay(10000)
          .disableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .delay(10000)
          .enableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          // Ditto for the audio renderer.
          .delay(10000)
          .disableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .delay(10000)
          .enableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          // Wait 10 seconds, then disable and enable the video renderer 5 times in quick
          // succession.
          .delay(10000)
          .disableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.VIDEO_RENDERER_INDEX)
          // Ditto for the audio renderer.
          .delay(10000)
          .disableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .disableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          .enableRenderer(DashTestRunner.AUDIO_RENDERER_INDEX)
          // Wait 10 seconds, detach the surface, wait another 10 seconds and attach it again.
          .delay(10000)
          .clearVideoSurface()
          .delay(10000)
          .setVideoSurface()
          // Wait 10 seconds, then seek to near end.
          .delay(10000)
          .seek(120000)
          .build();

  @Rule public ActivityTestRule<HostActivity> testRule = new ActivityTestRule<>(HostActivity.class);

  private DashTestRunner testRunner;

  @Before
  public void setUp() {
    testRunner = new DashTestRunner(TAG, testRule.getActivity());
  }

  @After
  public void tearDown() {
    testRunner = null;
  }

  // H264 CDD.

  @Test
  public void h264Fixed() throws Exception {
    testRunner
        .setStreamName("test_h264_fixed")
        .setManifestUrl(DashTestData.H264_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H264_CDD_FIXED)
        .run();
  }

  @Test
  public void h264Adaptive() throws Exception {
    if (shouldSkipAdaptiveTest(MimeTypes.VIDEO_H264)) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_h264_adaptive")
        .setManifestUrl(DashTestData.H264_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(true)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H264_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void h264AdaptiveWithSeeking() throws Exception {
    if (shouldSkipAdaptiveTest(MimeTypes.VIDEO_H264)) {
      // Pass.
      return;
    }
    final String streamName = "test_h264_adaptive_with_seeking";
    testRunner
        .setStreamName(streamName)
        .setManifestUrl(DashTestData.H264_MANIFEST)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(SEEKING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H264_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void h264AdaptiveWithRendererDisabling() throws Exception {
    if (shouldSkipAdaptiveTest(MimeTypes.VIDEO_H264)) {
      // Pass.
      return;
    }
    final String streamName = "test_h264_adaptive_with_renderer_disabling";
    testRunner
        .setStreamName(streamName)
        .setManifestUrl(DashTestData.H264_MANIFEST)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(RENDERER_DISABLING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H264_CDD_ADAPTIVE)
        .run();
  }

  // H265 CDD.

  @Test
  public void h265FixedV23() throws Exception {
    if (Util.SDK_INT < 23 || isPc()) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_h265_fixed")
        .setManifestUrl(DashTestData.H265_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H265_CDD_FIXED)
        .run();
  }

  @Test
  public void h265AdaptiveV24() throws Exception {
    if (Util.SDK_INT < 24 || isPc()) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_h265_adaptive")
        .setManifestUrl(DashTestData.H265_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(true)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H265_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void h265AdaptiveWithSeekingV24() throws Exception {
    if (Util.SDK_INT < 24 || isPc()) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_h265_adaptive_with_seeking")
        .setManifestUrl(DashTestData.H265_MANIFEST)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(SEEKING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H265_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void h265AdaptiveWithRendererDisablingV24() throws Exception {
    if (Util.SDK_INT < 24 || isPc()) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_h265_adaptive_with_renderer_disabling")
        .setManifestUrl(DashTestData.H265_MANIFEST)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(RENDERER_DISABLING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID, DashTestData.H265_CDD_ADAPTIVE)
        .run();
  }

  // VP9 (CDD).

  @Test
  public void vp9Fixed360pV23() throws Exception {
    if (Util.SDK_INT < 23) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_vp9_fixed_360p")
        .setManifestUrl(DashTestData.VP9_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.VP9_VORBIS_AUDIO_REPRESENTATION_ID, DashTestData.VP9_CDD_FIXED)
        .run();
  }

  @Test
  public void vp9AdaptiveV24() throws Exception {
    if (Util.SDK_INT < 24) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_vp9_adaptive")
        .setManifestUrl(DashTestData.VP9_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(true)
        .setAudioVideoFormats(
            DashTestData.VP9_VORBIS_AUDIO_REPRESENTATION_ID, DashTestData.VP9_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void vp9AdaptiveWithSeekingV24() throws Exception {
    if (Util.SDK_INT < 24) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_vp9_adaptive_with_seeking")
        .setManifestUrl(DashTestData.VP9_MANIFEST)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(SEEKING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.VP9_VORBIS_AUDIO_REPRESENTATION_ID, DashTestData.VP9_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void vp9AdaptiveWithRendererDisablingV24() throws Exception {
    if (Util.SDK_INT < 24) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_vp9_adaptive_with_renderer_disabling")
        .setManifestUrl(DashTestData.VP9_MANIFEST)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(RENDERER_DISABLING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.VP9_VORBIS_AUDIO_REPRESENTATION_ID, DashTestData.VP9_CDD_ADAPTIVE)
        .run();
  }

  // H264: Other frame-rates for output buffer count assertions.

  // 23.976 fps.
  @Test
  public void test23FpsH264FixedV23() throws Exception {
    if (Util.SDK_INT < 23) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_23fps_h264_fixed")
        .setManifestUrl(DashTestData.H264_23_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.H264_BASELINE_480P_23FPS_VIDEO_REPRESENTATION_ID)
        .run();
  }

  // 24 fps.
  @Test
  public void test24FpsH264FixedV23() throws Exception {
    if (Util.SDK_INT < 23) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_24fps_h264_fixed")
        .setManifestUrl(DashTestData.H264_24_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.H264_BASELINE_480P_24FPS_VIDEO_REPRESENTATION_ID)
        .run();
  }

  // 29.97 fps.
  @Test
  public void test29FpsH264FixedV23() throws Exception {
    if (Util.SDK_INT < 23) {
      // Pass.
      return;
    }
    testRunner
        .setStreamName("test_29fps_h264_fixed")
        .setManifestUrl(DashTestData.H264_29_MANIFEST)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.H264_BASELINE_480P_29FPS_VIDEO_REPRESENTATION_ID)
        .run();
  }

  // Widevine encrypted media tests.
  // H264 CDD.

  @Test
  public void widevineH264FixedV18() throws Exception {
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_h264_fixed")
        .setManifestUrl(DashTestData.WIDEVINE_H264_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID, DashTestData.WIDEVINE_H264_CDD_FIXED)
        .run();
  }

  @Test
  public void widevineH264AdaptiveV18() throws Exception {
    assumeFalse(shouldSkipAdaptiveTest(MimeTypes.VIDEO_H264));
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_h264_adaptive")
        .setManifestUrl(DashTestData.WIDEVINE_H264_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(true)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H264_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void widevineH264AdaptiveWithSeekingV18() throws Exception {
    assumeFalse(shouldSkipAdaptiveTest(MimeTypes.VIDEO_H264));
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_h264_adaptive_with_seeking")
        .setManifestUrl(DashTestData.WIDEVINE_H264_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(SEEKING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H264_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void widevineH264AdaptiveWithRendererDisablingV18() throws Exception {
    assumeFalse(shouldSkipAdaptiveTest(MimeTypes.VIDEO_H264));
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_h264_adaptive_with_renderer_disabling")
        .setManifestUrl(DashTestData.WIDEVINE_H264_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(RENDERER_DISABLING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H264_CDD_ADAPTIVE)
        .run();
  }

  // H265 CDD.

  @Test
  public void widevineH265FixedV23() throws Exception {
    assumeTrue(Util.SDK_INT >= 23);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));
    assumeFalse(isPc());

    testRunner
        .setStreamName("test_widevine_h265_fixed")
        .setManifestUrl(DashTestData.WIDEVINE_H265_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H265, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID, DashTestData.WIDEVINE_H265_CDD_FIXED)
        .run();
  }

  @Test
  public void widevineH265AdaptiveV24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));
    assumeFalse(isPc());

    testRunner
        .setStreamName("test_widevine_h265_adaptive")
        .setManifestUrl(DashTestData.WIDEVINE_H265_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H265, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(true)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H265_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void widevineH265AdaptiveWithSeekingV24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));
    assumeFalse(isPc());

    testRunner
        .setStreamName("test_widevine_h265_adaptive_with_seeking")
        .setManifestUrl(DashTestData.WIDEVINE_H265_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H265, true)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(SEEKING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H265_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void widevineH265AdaptiveWithRendererDisablingV24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));
    assumeFalse(isPc());

    testRunner
        .setStreamName("test_widevine_h265_adaptive_with_renderer_disabling")
        .setManifestUrl(DashTestData.WIDEVINE_H265_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H265, true)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(RENDERER_DISABLING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H265_CDD_ADAPTIVE)
        .run();
  }

  // VP9 (CDD).

  @Test
  public void widevineVp9Fixed360pV23() throws Exception {
    assumeTrue(Util.SDK_INT >= 23);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_vp9_fixed_360p")
        .setManifestUrl(DashTestData.WIDEVINE_VP9_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_VP9, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_VP9_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_VP9_CDD_FIXED)
        .run();
  }

  @Test
  public void widevineVp9AdaptiveV24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_vp9_adaptive")
        .setManifestUrl(DashTestData.WIDEVINE_VP9_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_VP9, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(true)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_VP9_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_VP9_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void widevineVp9AdaptiveWithSeekingV24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_vp9_adaptive_with_seeking")
        .setManifestUrl(DashTestData.WIDEVINE_VP9_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_VP9, true)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(SEEKING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_VP9_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_VP9_CDD_ADAPTIVE)
        .run();
  }

  @Test
  public void widevineVp9AdaptiveWithRendererDisablingV24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_vp9_adaptive_with_renderer_disabling")
        .setManifestUrl(DashTestData.WIDEVINE_VP9_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_VP9, true)
        .setFullPlaybackNoSeeking(false)
        .setCanIncludeAdditionalVideoFormats(true)
        .setActionSchedule(RENDERER_DISABLING_SCHEDULE)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_VP9_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_VP9_CDD_ADAPTIVE)
        .run();
  }

  // H264: Other frame-rates for output buffer count assertions.

  // 23.976 fps.
  @Test
  public void widevine23FpsH264FixedV23() throws Exception {
    assumeTrue(Util.SDK_INT >= 23);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_23fps_h264_fixed")
        .setManifestUrl(DashTestData.WIDEVINE_H264_23_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H264_BASELINE_480P_23FPS_VIDEO_REPRESENTATION_ID)
        .run();
  }

  // 24 fps.
  @Test
  public void widevine24FpsH264FixedV23() throws Exception {
    assumeTrue(Util.SDK_INT >= 23);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_24fps_h264_fixed")
        .setManifestUrl(DashTestData.WIDEVINE_H264_24_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H264_BASELINE_480P_24FPS_VIDEO_REPRESENTATION_ID)
        .run();
  }

  // 29.97 fps.
  @Test
  public void widevine29FpsH264FixedV23() throws Exception {
    assumeTrue(Util.SDK_INT >= 23);
    assumeFalse(shouldSkipWidevineTest(testRule.getActivity()));

    testRunner
        .setStreamName("test_widevine_29fps_h264_fixed")
        .setManifestUrl(DashTestData.WIDEVINE_H264_29_MANIFEST)
        .setWidevineInfo(MimeTypes.VIDEO_H264, true)
        .setFullPlaybackNoSeeking(true)
        .setCanIncludeAdditionalVideoFormats(false)
        .setAudioVideoFormats(
            DashTestData.WIDEVINE_AAC_AUDIO_REPRESENTATION_ID,
            DashTestData.WIDEVINE_H264_BASELINE_480P_29FPS_VIDEO_REPRESENTATION_ID)
        .run();
  }

  // Decoder info.

  @Test
  public void decoderInfoH264() throws Exception {
    MediaCodecInfo decoderInfo =
        MediaCodecUtil.getDecoderInfo(
            MimeTypes.VIDEO_H264, /* secure= */ false, /* tunneling= */ false);
    assertThat(decoderInfo).isNotNull();
    assertThat(decoderInfo.adaptive).isTrue();
  }

  @Test
  public void decoderInfoH265V24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);
    assumeFalse(isPc());

    assertThat(
            MediaCodecUtil.getDecoderInfo(
                    MimeTypes.VIDEO_H265, /* secure= */ false, /* tunneling= */ false)
                .adaptive)
        .isTrue();
  }

  @Test
  public void decoderInfoVP9V24() throws Exception {
    assumeTrue(Util.SDK_INT >= 24);

    assertThat(
            MediaCodecUtil.getDecoderInfo(
                    MimeTypes.VIDEO_VP9, /* secure= */ false, /* tunneling= */ false)
                .adaptive)
        .isTrue();
  }

  // Internal.

  private boolean isPc() {
    // See [internal b/162990153].
    return testRule.getActivity().getPackageManager().hasSystemFeature(PackageManager.FEATURE_PC);
  }

  private static boolean shouldSkipAdaptiveTest(String mimeType) throws DecoderQueryException {
    MediaCodecInfo decoderInfo =
        MediaCodecUtil.getDecoderInfo(mimeType, /* secure= */ false, /* tunneling= */ false);
    return decoderInfo == null || !decoderInfo.adaptive;
  }
}
