/*
 * Copyright (C) 2017 The Android Open Source Project
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
package androidx.media3.exoplayer.dash;

import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.fail;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaItem.LiveConfiguration;
import androidx.media3.common.ParserException;
import androidx.media3.common.StreamKey;
import androidx.media3.common.Timeline;
import androidx.media3.common.Timeline.Window;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.ByteArrayDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.FileDataSource;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.ParsingLoadable;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit test for {@link DashMediaSource}. */
@RunWith(AndroidJUnit4.class)
public final class DashMediaSourceTest {

  private MockWebServer mockWebServer;
  private int enqueueCounter;
  private int assertedRequestCounter;

  @Before
  public void setUp() {
    mockWebServer = new MockWebServer();
    enqueueCounter = 0;
    assertedRequestCounter = 0;
  }

  @After
  public void tearDown() throws IOException {
    assertThat(assertedRequestCounter).isEqualTo(enqueueCounter);
    mockWebServer.shutdown();
  }

  private static final String SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION =
      "media/mpd/sample_mpd_live_without_live_configuration";
  private static final String
      SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS =
          "media/mpd/sample_mpd_live_with_suggested_presentation_delay_2s_min_buffer_time_500ms";
  private static final String SAMPLE_MPD_LIVE_WITH_COMPLETE_SERVICE_DESCRIPTION =
      "media/mpd/sample_mpd_live_with_complete_service_description";
  private static final String SAMPLE_MPD_LIVE_WITH_OFFSET_INSIDE_WINDOW =
      "media/mpd/sample_mpd_live_with_offset_inside_window";
  private static final String SAMPLE_MPD_LIVE_WITH_TIME_SHIFT_BUFFER_16_SECS =
      "media/mpd/sample_mpd_live_with_time_shift_buffer_16_secs";
  private static final String SAMPLE_MPD_LIVE_WITH_TIME_SHIFT_BUFFER_60_SECS =
      "media/mpd/sample_mpd_live_with_time_shift_buffer_60_secs";
  private static final String SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_SHORT =
      "media/mpd/sample_mpd_live_with_offset_too_short";
  private static final String SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_LONG =
      "media/mpd/sample_mpd_live_with_offset_too_long";
  private static final String SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE =
      "media/mpd/sample_mpd_multiple_locations_relative";
  private static final String SAMPLE_MPD_UPDATED_NO_LOCATIONS =
      "media/mpd/sample_mpd_updated_no_locations";

  @Test
  public void iso8601ParserParse() throws IOException {
    DashMediaSource.Iso8601Parser parser = new DashMediaSource.Iso8601Parser();
    // UTC.
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37Z");
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37+00:00");
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37+0000");
    assertParseStringToLong(1512381697000L, parser, "2017-12-04T10:01:37+00");
    // Positive timezone offsets.
    assertParseStringToLong(1512381697000L - 4980000L, parser, "2017-12-04T10:01:37+01:23");
    assertParseStringToLong(1512381697000L - 4980000L, parser, "2017-12-04T10:01:37+0123");
    assertParseStringToLong(1512381697000L - 3600000L, parser, "2017-12-04T10:01:37+01");
    // Negative timezone offsets with minus character.
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37-01:23");
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37-0123");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37-01:00");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37-0100");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37-01");
    // Negative timezone offsets with hyphen character.
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37−01:23");
    assertParseStringToLong(1512381697000L + 4980000L, parser, "2017-12-04T10:01:37−0123");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37−01:00");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37−0100");
    assertParseStringToLong(1512381697000L + 3600000L, parser, "2017-12-04T10:01:37−01");
  }

  @Test
  public void iso8601ParserParseMissingTimezone() throws IOException {
    DashMediaSource.Iso8601Parser parser = new DashMediaSource.Iso8601Parser();
    try {
      assertParseStringToLong(0, parser, "2017-12-04T10:01:37");
      fail();
    } catch (ParserException e) {
      // Expected.
    }
  }

  @Test
  public void replaceManifestUri_doesNotChangeMediaItem() {
    DashMediaSource.Factory factory = new DashMediaSource.Factory(new FileDataSource.Factory());
    MediaItem mediaItem = MediaItem.fromUri("http://www.google.com");
    DashMediaSource mediaSource = factory.createMediaSource(mediaItem);

    mediaSource.replaceManifestUri(Uri.EMPTY);

    assertThat(mediaSource.getMediaItem()).isEqualTo(mediaItem);
  }

  @Test
  public void factorySetFallbackTargetLiveOffsetMs_withMediaLiveTargetOffsetMs_usesMediaOffset() {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(2L).build())
            .build();
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setFallbackTargetLiveOffsetMs(1234L);

    MediaItem dashMediaItem = factory.createMediaSource(mediaItem).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(2L);
  }

  @Test
  public void factorySetFallbackTargetLiveOffsetMs_doesNotChangeMediaItem() {
    DashMediaSource.Factory factory =
        new DashMediaSource.Factory(new FileDataSource.Factory())
            .setFallbackTargetLiveOffsetMs(2000L);

    MediaItem dashMediaItem =
        factory.createMediaSource(MediaItem.fromUri(Uri.EMPTY)).getMediaItem();

    assertThat(dashMediaItem.liveConfiguration.targetOffsetMs).isEqualTo(C.TIME_UNSET);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withoutMediaItemLiveConfiguration_usesUnitSpeed()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs)
        .isEqualTo(DashMediaSource.DEFAULT_FALLBACK_TARGET_LIVE_OFFSET_MS);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(0L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(1f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(1f);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withOnlyMediaItemTargetOffset_usesUnitSpeed()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new LiveConfiguration.Builder().setTargetOffsetMs(10_000L).build())
                    .build());

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(10_000L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(0L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(1f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(1f);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withMediaItemSpeedLimits_usesDefaultFallbackValues()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new LiveConfiguration.Builder().setMinPlaybackSpeed(0.95f).build())
                    .build());

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs)
        .isEqualTo(DashMediaSource.DEFAULT_FALLBACK_TARGET_LIVE_OFFSET_MS);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(0L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(0.95f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(C.RATE_UNSET);
  }

  @Test
  public void
      prepare_withoutLiveConfiguration_withoutMediaItemTargetOffset_usesDefinedFallbackTargetOffset()
          throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new LiveConfiguration.Builder().setMinPlaybackSpeed(0.95f).build())
                    .build());

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(1234L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(0L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(0.95f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(C.RATE_UNSET);
  }

  @Test
  public void prepare_withoutLiveConfiguration_withMediaItemLiveProperties_usesMediaItem()
      throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(876L)
                    .setMinPlaybackSpeed(23f)
                    .setMaxPlaybackSpeed(42f)
                    .setMinOffsetMs(500L)
                    .setMaxOffsetMs(20_000L)
                    .build())
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration).isEqualTo(mediaItem.liveConfiguration);
  }

  @Test
  public void prepare_withSuggestedPresentationDelayAndMinBufferTime_usesManifestValue()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () ->
                    createSampleMpdDataSource(
                        SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(
                        new LiveConfiguration.Builder().setMaxPlaybackSpeed(1.05f).build())
                    .build());

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(2_000L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(500L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(C.RATE_UNSET);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(1.05f);
  }

  @Test
  public void
      prepare_withSuggestedPresentationDelayAndMinBufferTime_withMediaItemLiveProperties_usesMediaItem()
          throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(876L)
                    .setMinPlaybackSpeed(23f)
                    .setMaxPlaybackSpeed(42f)
                    .setMinOffsetMs(600L)
                    .setMaxOffsetMs(999L)
                    .build())
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () ->
                    createSampleMpdDataSource(
                        SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(876L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(600L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(999L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(23f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(42f);
  }

  @Test
  public void prepare_withCompleteServiceDescription_usesManifestValue() throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_COMPLETE_SERVICE_DESCRIPTION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(4_000L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(2_000L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(6_000L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(0.96f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(1.04f);
  }

  @Test
  public void prepare_withCompleteServiceDescription_withMediaItemLiveProperties_usesMediaItem()
      throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(876L)
                    .setMinPlaybackSpeed(23f)
                    .setMaxPlaybackSpeed(42f)
                    .setMinOffsetMs(100L)
                    .setMaxOffsetMs(999L)
                    .build())
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_COMPLETE_SERVICE_DESCRIPTION))
            .setFallbackTargetLiveOffsetMs(1234L)
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.targetOffsetMs).isEqualTo(876L);
    assertThat(liveConfiguration.minOffsetMs).isEqualTo(100L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(999L);
    assertThat(liveConfiguration.minPlaybackSpeed).isEqualTo(23f);
    assertThat(liveConfiguration.maxPlaybackSpeed).isEqualTo(42f);
  }

  @Test
  public void
      prepare_withMinMaxOffsetOverridesOutsideOfLiveWindow_adjustsOverridesToBeWithinWindow()
          throws Exception {
    MediaItem mediaItem =
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(
                new MediaItem.LiveConfiguration.Builder()
                    .setMinOffsetMs(0L)
                    .setMaxOffsetMs(1_000_000_000L)
                    .build())
            .build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () ->
                    createSampleMpdDataSource(
                        SAMPLE_MPD_LIVE_WITH_SUGGESTED_PRESENTATION_DELAY_2S_MIN_BUFFER_TIME_500MS))
            .createMediaSource(mediaItem);

    MediaItem.LiveConfiguration liveConfiguration =
        prepareAndWaitForTimelineRefresh(mediaSource).liveConfiguration;

    assertThat(liveConfiguration.minOffsetMs).isEqualTo(500L);
    assertThat(liveConfiguration.maxOffsetMs).isEqualTo(58_000L);
  }

  @Test
  public void prepare_targetLiveOffsetInWindow_manifestTargetOffsetAndAlignedWindowStartPosition()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_OFFSET_INSIDE_WINDOW))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    // Expect the target live offset as defined in the manifest.
    assertThat(window.liveConfiguration.targetOffsetMs).isEqualTo(3000);
    // Expect the default position at the first segment start before the live edge.
    assertThat(window.getDefaultPositionMs()).isEqualTo(2_000);
  }

  @Test
  public void prepare_targetLiveOffsetTooLong_correctedTargetOffsetAndAlignedWindowStartPosition()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_LONG))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    // Expect the default position at the first segment start below the minimum live start position.
    assertThat(window.getDefaultPositionMs()).isEqualTo(4_000);
    // Expect the target live offset reaching from now time to the minimum live start position.
    assertThat(window.liveConfiguration.targetOffsetMs).isEqualTo(9000);
  }

  @Test
  public void prepare_targetLiveOffsetTooShort_correctedTargetOffsetAndAlignedWindowStartPosition()
      throws Exception {
    // Load manifest with now time far behind the start of the window.
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_OFFSET_TOO_SHORT))
            .createMediaSource(MediaItem.fromUri(Uri.EMPTY));

    Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    // Expect the default position at the start of the last segment.
    assertThat(window.getDefaultPositionMs()).isEqualTo(12_000);
    // Expect the target live offset reaching from now time to the end of the window.
    assertThat(window.liveConfiguration.targetOffsetMs).isEqualTo(60_000 - 16_000);
  }

  @Test
  public void prepare_targetLiveOffsetConstrainedByManifest_resetByRelease() throws Exception {
    LiveConfiguration mediaItemLiveConfiguration =
        new LiveConfiguration.Builder().setTargetOffsetMs(25_000L).build();
    AtomicBoolean hasCreatedFirstDataSource = new AtomicBoolean();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> {
                  if (!hasCreatedFirstDataSource.getAndSet(true)) {
                    // Delivers a manifest with a window duration shorter than the declared offset.
                    return createSampleMpdDataSource(
                        SAMPLE_MPD_LIVE_WITH_TIME_SHIFT_BUFFER_16_SECS);
                  }
                  // Delivers a manifest with a window duration larger than the declared offset.
                  return createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_TIME_SHIFT_BUFFER_60_SECS);
                })
            .createMediaSource(
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(mediaItemLiveConfiguration)
                    .build());
    List<Window> capturedWindows = new ArrayList<>();
    MediaSource.MediaSourceCaller mediaSourceCaller =
        (source, timeline) ->
            capturedWindows.add(timeline.getWindow(/* windowIndex= */ 0, new Window()));

    mediaSource.prepareSource(mediaSourceCaller, PlayerId.UNSET, BandwidthMeter.NO_OP);

    RobolectricUtil.runMainLooperUntil(() -> capturedWindows.size() == 1);
    // Assert that the offset defined by the media item was overridden.
    assertThat(capturedWindows.get(0).liveConfiguration.targetOffsetMs).isEqualTo(9_000L);

    mediaSource.releaseSource(mediaSourceCaller);
    mediaSource.prepareSource(mediaSourceCaller, PlayerId.UNSET, BandwidthMeter.NO_OP);

    RobolectricUtil.runMainLooperUntil(() -> capturedWindows.size() == 2);
    assertThat(capturedWindows.get(1).liveConfiguration.targetOffsetMs).isEqualTo(25_000L);
  }

  @Test
  public void prepare_locationsUpdatedAndCurrentLocationKept_staysOnCurrentLocation()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    String manifestString =
        new String(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE),
            UTF_8);
    byte[] initialManifest = manifestString.getBytes(UTF_8);
    byte[] updatedManifest =
        manifestString
            .replace("location1/manifest.mpd", "../location1/manifest.mpd")
            .replace("location2/manifest.mpd", "../location3/manifest.mpd")
            .getBytes(UTF_8);
    // We expect that the DashMediaSource stays sticky on the working location (location1) when it
    // is still declared in the updated manifest.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"/manifest.mpd", "/location1/manifest.mpd", "/location1/manifest.mpd"},
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(initialManifest)),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 3);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void prepare_locationsUpdatedAndCurrentLocationRemoved_switchesToNewLocation()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    String manifestString =
        new String(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE),
            UTF_8);
    byte[] initialManifest = manifestString.getBytes(UTF_8);
    byte[] updatedManifest =
        manifestString
            .replace("location1/manifest.mpd", "../location3/manifest.mpd")
            .replace("location2/manifest.mpd", "../location4/manifest.mpd")
            .getBytes(UTF_8);
    // We expect that the DashMediaSource switches to the new location (location3) when the
    // currently active location (location1) is removed from the updated manifest.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"/manifest.mpd", "/location1/manifest.mpd", "/location3/manifest.mpd"},
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(initialManifest)),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 3);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void prepare_locationsUpdatedAndAllNewLocationsExcluded_switchesToExcludedLocation()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    String manifestString =
        new String(
            TestUtil.getByteArray(
                ApplicationProvider.getApplicationContext(),
                SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE),
            UTF_8);
    byte[] initialManifest = manifestString.getBytes(UTF_8);
    // The updated manifest has only one location which is location1/manifest.mpd.
    // Since this updated manifest is loaded from location2/manifest.mpd, we must use the relative
    // path "../location1/manifest.mpd" to resolve it to /location1/manifest.mpd.
    byte[] updatedManifest =
        manifestString
            .replace("location1/manifest.mpd", "../location1/manifest.mpd")
            .replace("<Location serviceLocation=\"loc2\">location2/manifest.mpd</Location>", "")
            .getBytes(UTF_8);
    // We expect that the DashMediaSource switches to the new location (location1) when the
    // currently active location (location2) is removed from the updated manifest.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/manifest.mpd",
              "/location1/manifest.mpd",
              "/location2/manifest.mpd",
              "/location1/manifest.mpd"
            },
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(initialManifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 3);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void prepare_manifestUpdatedViaRedirectionWithoutLocationElements_staysOnRedirectedUri()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    byte[] manifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE);
    byte[] updatedManifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_UPDATED_NO_LOCATIONS);
    // We expect that the DashMediaSource stays on the redirected URI if the manifest
    // contains no Location elements. And the redirected URI is the only location option that even
    // there is load error, the DashMediaSource has no way to fallback but remains retrying.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/manifest.mpd",
              "/location1/manifest.mpd",
              "/redirected-location/manifest.mpd",
              "/redirected-location/manifest.mpd",
              "/redirected-location/manifest.mpd"
            },
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)),
            new MockResponse()
                .setResponseCode(302)
                .setHeader(
                    "Location", mockWebServer.url("/redirected-location/manifest.mpd").toString()),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 3);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void prepare_manifestUpdatedNotViaRedirectionAndNoLocationElements_staysOnOriginalUri()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    byte[] manifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE);
    byte[] updatedManifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_UPDATED_NO_LOCATIONS);
    // We expect that the DashMediaSource stays on the original requested URI if the manifest
    // contains no Location elements. And the original URI is the only location option that even
    // there is load error, the DashMediaSource has no way to fallback but remains retrying.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/manifest.mpd",
              "/location1/manifest.mpd",
              "/location1/manifest.mpd",
              "/location1/manifest.mpd"
            },
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(updatedManifest)));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 3);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void prepare_replaceManifestUriCalledDuringLoading_refreshesFromReplacedUriOnly()
      throws Exception {
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    byte[] manifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE);
    // We expect that when the manifest URI is explicitly replaced during an active load (before
    // loading completes), the completed load does not overwrite the manually set URI, and the
    // manually set URI is the only location option, that even there is load error, the
    // DashMediaSource has no way to fallback but remains retrying.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"/manifest.mpd", "/replaced/manifest.mpd", "/replaced/manifest.mpd"},
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    // Call replaceManifestUri immediately after starting preparation (while load is active).
    mediaSource.replaceManifestUri(
        Uri.parse(mockWebServer.url("/replaced/manifest.mpd").toString()));
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 2);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void prepare_manifestLoadFailedWithAnotherLocationAvailable_fallsbackToAvailableLocation()
      throws Exception {
    byte[] manifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE);
    // We expect that the DashMediaSource falls back to the next available location in
    // the manifest when the first manifest load attempt fails.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"/manifest.mpd", "/location1/manifest.mpd", "/location2/manifest.mpd"},
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)));
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 2);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void
      prepare_manifestLoadFallbackWithDefaultPolicyAndAllLocationsExcluded_doesNotFallbackButRetries()
          throws Exception {
    byte[] manifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE);
    // We expect that the DashMediaSource does not exclude the last remaining location but retries
    // loading from it when the DefaultLoadErrorHandlingPolicy is used and all other locations
    // are excluded.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/manifest.mpd",
              "/location1/manifest.mpd",
              "/location2/manifest.mpd",
              "/location2/manifest.mpd"
            },
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)));
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 2);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void
      prepare_manifestLoadFailedWithCustomPolicyAndAllLocationsExcluded_fallsbackToEarliestExclusionExpiringLocation()
          throws Exception {
    byte[] manifest =
        TestUtil.getByteArray(
            ApplicationProvider.getApplicationContext(), SAMPLE_MPD_MULTIPLE_LOCATIONS_RELATIVE);
    // We expect that the DashMediaSource falls back to the location whose exclusion duration
    // expires earliest when a custom LoadErrorHandlingPolicy allows excluding all locations.
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/manifest.mpd",
              "/location1/manifest.mpd",
              "/location2/manifest.mpd",
              "/location1/manifest.mpd"
            },
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(500),
            new MockResponse().setResponseCode(200).setBody(new Buffer().write(manifest)));
    AtomicInteger getFallbackSelectionCount = new AtomicInteger(0);
    LoadErrorHandlingPolicy customPolicy =
        new DefaultLoadErrorHandlingPolicy() {
          @Override
          public FallbackSelection getFallbackSelectionFor(
              FallbackOptions fallbackOptions, LoadErrorInfo loadErrorInfo) {
            int count = getFallbackSelectionCount.incrementAndGet();
            long exclusionDurationMs = count == 1 ? 5000 : 10000;
            return new FallbackSelection(FALLBACK_TYPE_LOCATION, exclusionDurationMs);
          }
        };
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(new DefaultHttpDataSource.Factory())
            .setLoadErrorHandlingPolicy(customPolicy)
            .createMediaSource(
                MediaItem.fromUri(Uri.parse(mockWebServer.url("/manifest.mpd").toString())));
    List<Timeline> capturedTimelines = new ArrayList<>();

    mediaSource.prepareSource(
        (source, timeline) -> capturedTimelines.add(timeline),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> capturedTimelines.size() == 2);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void canUpdateMediaItem_withIrrelevantFieldsChanged_returnsTrue() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* streamIndex= */ 0)))
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
            .setLiveConfiguration(new LiveConfiguration.Builder().setTargetOffsetMs(2000).build())
            .build();
    MediaItem updatedMediaItem =
        TestUtil.buildFullyCustomizedMediaItem()
            .buildUpon()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* streamIndex= */ 0)))
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
            .setLiveConfiguration(new LiveConfiguration.Builder().setTargetOffsetMs(2000).build())
            .build();
    MediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isTrue();
  }

  @Test
  public void canUpdateMediaItem_withNullLocalConfiguration_returnsFalse() {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setMediaId("id").build();
    MediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedUri_returnsFalse() {
    MediaItem initialMediaItem = new MediaItem.Builder().setUri("http://test.test").build();
    MediaItem updatedMediaItem = new MediaItem.Builder().setUri("http://test2.test").build();
    MediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedStreamKeys_returnsFalse() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 1, /* streamIndex= */ 0)))
            .build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setStreamKeys(
                ImmutableList.of(new StreamKey(/* groupIndex= */ 2, /* streamIndex= */ 2)))
            .build();
    MediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void canUpdateMediaItem_withChangedDrmConfiguration_returnsFalse() {
    MediaItem initialMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID).build())
            .build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder()
            .setUri("http://test.test")
            .setDrmConfiguration(new MediaItem.DrmConfiguration.Builder(C.CLEARKEY_UUID).build())
            .build();
    MediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(initialMediaItem);

    boolean canUpdateMediaItem = mediaSource.canUpdateMediaItem(updatedMediaItem);

    assertThat(canUpdateMediaItem).isFalse();
  }

  @Test
  public void updateMediaItem_createsTimelineWithUpdatedItem() throws Exception {
    MediaItem initialMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setTag("tag1").build();
    MediaItem updatedMediaItem =
        new MediaItem.Builder().setUri("http://test.test").setTag("tag2").build();
    MediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITHOUT_LIVE_CONFIGURATION))
            .createMediaSource(initialMediaItem);

    mediaSource.updateMediaItem(updatedMediaItem);
    Timeline.Window window = prepareAndWaitForTimelineRefresh(mediaSource);

    assertThat(window.mediaItem).isEqualTo(updatedMediaItem);
  }

  @Test
  public void updateMediaItem_targetLiveOffsetConstrainedByManifest_resetByUpdatedMediaItem()
      throws Exception {
    // A live configuration with a target offset larger than the window duration.
    LiveConfiguration initialLiveConfiguration =
        new LiveConfiguration.Builder().setTargetOffsetMs(25_000L).build();
    DashMediaSource mediaSource =
        new DashMediaSource.Factory(
                () -> createSampleMpdDataSource(SAMPLE_MPD_LIVE_WITH_TIME_SHIFT_BUFFER_16_SECS))
            .createMediaSource(
                new MediaItem.Builder()
                    .setUri(Uri.EMPTY)
                    .setLiveConfiguration(initialLiveConfiguration)
                    .build());
    List<Window> capturedWindows = new ArrayList<>();
    MediaSource.MediaSourceCaller mediaSourceCaller =
        (source, timeline) ->
            capturedWindows.add(timeline.getWindow(/* windowIndex= */ 0, new Window()));

    mediaSource.prepareSource(mediaSourceCaller, PlayerId.UNSET, BandwidthMeter.NO_OP);

    RobolectricUtil.runMainLooperUntil(() -> capturedWindows.size() == 1);
    // Assert that the offset defined by the media item was overridden.
    assertThat(capturedWindows.get(0).liveConfiguration.targetOffsetMs).isEqualTo(9_000L);

    mediaSource.releaseSource(mediaSourceCaller);
    // Set a new live configuration with a target offset within the window duration.
    mediaSource.updateMediaItem(
        new MediaItem.Builder()
            .setUri(Uri.EMPTY)
            .setLiveConfiguration(new LiveConfiguration.Builder().setTargetOffsetMs(5_000L).build())
            .build());

    mediaSource.prepareSource(mediaSourceCaller, PlayerId.UNSET, BandwidthMeter.NO_OP);

    RobolectricUtil.runMainLooperUntil(() -> capturedWindows.size() == 2);
    assertThat(capturedWindows.get(1).liveConfiguration.targetOffsetMs).isEqualTo(5_000L);
  }

  private static Window prepareAndWaitForTimelineRefresh(MediaSource mediaSource) throws Exception {
    AtomicReference<Timeline.Window> windowReference = new AtomicReference<>();
    mediaSource.prepareSource(
        (source, timeline) ->
            windowReference.set(timeline.getWindow(/* windowIndex= */ 0, new Timeline.Window())),
        PlayerId.UNSET,
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> windowReference.get() != null);
    return windowReference.get();
  }

  private static DataSource createSampleMpdDataSource(String fileName) {
    byte[] manifestData = new byte[0];
    try {
      manifestData = TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), fileName);
    } catch (IOException e) {
      fail(e.getMessage());
    }
    return new ByteArrayDataSource(manifestData);
  }

  private static void assertParseStringToLong(
      long expected, ParsingLoadable.Parser<Long> parser, String data) throws IOException {
    long actual = parser.parse(null, new ByteArrayInputStream(Util.getUtf8Bytes(data)));
    assertThat(actual).isEqualTo(expected);
  }

  private List<HttpUrl> enqueueWebServerResponses(String[] paths, MockResponse... mockResponses) {
    assertThat(paths).hasLength(mockResponses.length);
    for (MockResponse mockResponse : mockResponses) {
      enqueueCounter++;
      mockWebServer.enqueue(mockResponse);
    }
    List<HttpUrl> urls = new ArrayList<>();
    for (String path : paths) {
      urls.add(mockWebServer.url(path));
    }
    return urls;
  }

  private void assertRequestUrlsCalled(List<HttpUrl> httpUrls) throws InterruptedException {
    for (HttpUrl url : httpUrls) {
      assertedRequestCounter++;
      assertThat(url.toString()).endsWith(mockWebServer.takeRequest().getPath());
    }
  }
}
