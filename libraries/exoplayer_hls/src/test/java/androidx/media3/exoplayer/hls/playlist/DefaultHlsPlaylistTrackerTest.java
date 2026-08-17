/*
 * Copyright 2020 The Android Open Source Project
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
package androidx.media3.exoplayer.hls.playlist;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.source.MediaLoadData;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.BandwidthMeter;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.util.ReleasableExecutor;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
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
import org.mockito.ArgumentCaptor;

/** Unit test for {@link DefaultHlsPlaylistTracker}. */
@RunWith(AndroidJUnit4.class)
public class DefaultHlsPlaylistTrackerTest {

  private static final String SAMPLE_M3U8_LIVE_MULTIVARIANT =
      "media/m3u8/live_low_latency_multivariant";
  private static final String SAMPLE_M3U8_LIVE_MULTIVARIANT_MEDIA_URI_WITH_PARAM =
      "media/m3u8/live_low_latency_multivariant_media_uri_with_param";
  private static final String SAMPLE_M3U8_LIVE_MULTIVARIANT_WITH_AUDIO_RENDITIONS =
      "media/m3u8/live_low_latency_multivariant_with_audio_renditions";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL =
      "media/m3u8/live_low_latency_media_can_skip_until";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_FULL_RELOAD_AFTER_ERROR =
      "media/m3u8/live_low_latency_media_can_skip_until_full_reload_after_error";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_DATERANGES =
      "media/m3u8/live_low_latency_media_can_skip_dateranges";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_SKIPPED =
      "media/m3u8/live_low_latency_media_can_skip_skipped";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_SKIPPED_MEDIA_SEQUENCE_NO_OVERLAPPING =
          "media/m3u8/live_low_latency_media_can_skip_skipped_media_sequence_no_overlapping";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP =
      "media/m3u8/live_low_latency_media_can_not_skip";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP_NEXT =
      "media/m3u8/live_low_latency_media_can_not_skip_next";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD =
      "media/m3u8/live_low_latency_media_can_block_reload";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_NEXT =
      "media/m3u8/live_low_latency_media_can_block_reload_next";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY =
      "media/m3u8/live_low_latency_media_can_block_reload_low_latency";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_NEXT =
      "media/m3u8/live_low_latency_media_can_block_reload_low_latency_next";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT =
      "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_AUDIO =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_audio";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_NEXT =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_next";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_NEXT2 =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_next2";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_AUDIO_NEXT =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_audio_next";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_AUDIO_NEXT2 =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_audio_next2";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_preload";
  private static final String
      SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD_NEXT =
          "media/m3u8/live_low_latency_media_can_block_reload_low_latency_full_segment_preload_next";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_AND_BLOCK_RELOAD =
      "media/m3u8/live_low_latency_media_can_skip_until_and_block_reload";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_AND_BLOCK_RELOAD_NEXT =
      "media/m3u8/live_low_latency_media_can_skip_until_and_block_reload_next";
  private static final String SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_AND_BLOCK_RELOAD_NEXT_SKIPPED =
      "media/m3u8/live_low_latency_media_can_skip_until_and_block_reload_next_skipped";
  private static final String SAMPLE_M3U8_MULTIVARIANT_WITH_REDUNDANT_VARIANTS_AND_RENDITIONS =
      "media/m3u8/multivariant_with_redundant_variants_and_renditions";
  private static final String SAMPLE_M3U8_MEDIA_PLAYLIST = "media/m3u8/media_playlist";
  private static final String SAMPLE_M3U8_MULTIVARIANT_WITH_CONTENT_STEERING =
      "media/m3u8/multivariant_with_content_steering";
  private static final String CDN_A_PLAYLIST =
      "#EXTM3U\n"
          + "#EXT-X-VERSION:3\n"
          + "#EXT-X-TARGETDURATION:10\n"
          + "#EXTINF:10,\n"
          + "a-segment1.ts\n";
  private static final String CDN_B_PLAYLIST =
      "#EXTM3U\n"
          + "#EXT-X-VERSION:3\n"
          + "#EXT-X-TARGETDURATION:10\n"
          + "#EXTINF:10,\n"
          + "b-segment1.ts\n";

  private static final String CDN_A_CLONE_PLAYLIST =
      "#EXTM3U\n"
          + "#EXT-X-VERSION:3\n"
          + "#EXT-X-TARGETDURATION:10\n"
          + "#EXTINF:10,\n"
          + "a-clone-segment1.ts\n";

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

  @Test
  public void start_playlistCanNotSkip_requestsFullUpdate()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"multivariant.m3u8", "/media0/playlist.m3u8", "/media0/playlist.m3u8"},
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP_NEXT));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    HlsMediaPlaylist firstFullPlaylist = mediaPlaylists.get(0);
    assertThat(firstFullPlaylist.mediaSequence).isEqualTo(10);
    assertThat(firstFullPlaylist.segments.get(0).url).isEqualTo("fileSequence10.ts");
    assertThat(firstFullPlaylist.segments.get(5).url).isEqualTo("fileSequence15.ts");
    assertThat(firstFullPlaylist.segments).hasSize(6);
    HlsMediaPlaylist secondFullPlaylist = mediaPlaylists.get(1);
    assertThat(secondFullPlaylist.mediaSequence).isEqualTo(11);
    assertThat(secondFullPlaylist.segments.get(0).url).isEqualTo("fileSequence11.ts");
    assertThat(secondFullPlaylist.segments.get(5).url).isEqualTo("fileSequence16.ts");
    assertThat(secondFullPlaylist.segments).hasSize(6);
    assertThat(secondFullPlaylist.segments).containsNoneIn(firstFullPlaylist.segments);
  }

  @Test
  public void start_playlistCanSkip_requestsDeltaUpdateAndExpandsSkippedSegments()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8", "/media0/playlist.m3u8", "/media0/playlist.m3u8?_HLS_skip=YES"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_SKIPPED));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    HlsMediaPlaylist initialPlaylistWithAllSegments = mediaPlaylists.get(0);
    assertThat(initialPlaylistWithAllSegments.mediaSequence).isEqualTo(10);
    assertThat(initialPlaylistWithAllSegments.segments).hasSize(6);
    HlsMediaPlaylist mergedPlaylist = mediaPlaylists.get(1);
    assertThat(mergedPlaylist.mediaSequence).isEqualTo(11);
    assertThat(mergedPlaylist.segments).hasSize(6);
    // First 2 segments of the merged playlist need to be copied from the previous playlist.
    assertThat(mergedPlaylist.segments.get(0).url)
        .isEqualTo(initialPlaylistWithAllSegments.segments.get(1).url);
    assertThat(mergedPlaylist.segments.get(0).relativeStartTimeUs).isEqualTo(0);
    assertThat(mergedPlaylist.segments.get(1).url)
        .isEqualTo(initialPlaylistWithAllSegments.segments.get(2).url);
    assertThat(mergedPlaylist.segments.get(1).relativeStartTimeUs).isEqualTo(4000000);
  }

  @Test
  public void start_playlistCanSkip_missingSegments_reloadsWithoutSkipping()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_skip=YES",
              "/media0/playlist.m3u8"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_SKIPPED_MEDIA_SEQUENCE_NO_OVERLAPPING),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_FULL_RELOAD_AFTER_ERROR));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    HlsMediaPlaylist initialPlaylistWithAllSegments = mediaPlaylists.get(0);
    assertThat(initialPlaylistWithAllSegments.mediaSequence).isEqualTo(10);
    assertThat(initialPlaylistWithAllSegments.segments).hasSize(6);
    HlsMediaPlaylist mergedPlaylist = mediaPlaylists.get(1);
    assertThat(mergedPlaylist.mediaSequence).isEqualTo(20);
    assertThat(mergedPlaylist.segments).hasSize(6);
  }

  @Test
  public void start_playlistCanSkipDataRanges_requestsDeltaUpdateV2()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8", "/media0/playlist.m3u8", "/media0/playlist.m3u8?_HLS_skip=v2"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_DATERANGES),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_SKIPPED));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    // Finding the media sequence of the second playlist request asserts that the second request has
    // been made with the correct uri parameter appended.
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(11);
  }

  @Test
  public void start_playlistCanSkipAndUriWithParams_preservesOriginalParams()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8?param1=1&param2=2",
              "/media0/playlist.m3u8?param1=1&param2=2&_HLS_skip=YES"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT_MEDIA_URI_WITH_PARAM),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_SKIPPED));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    // Finding the media sequence of the second playlist request asserts that the second request has
    // been made with the original uri parameters preserved and the additional param concatenated
    // correctly.
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(11);
  }

  @Test
  public void start_playlistCanBlockReload_requestBlockingReloadWithCorrectMediaSequence()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8", "/media0/playlist.m3u8", "/media0/playlist.m3u8?_HLS_msn=14"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_NEXT));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(11);
  }

  @Test
  public void
      start_playlistCanBlockReloadLowLatency_requestBlockingReloadWithCorrectMediaSequenceAndPart()
          throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_msn=14&_HLS_part=1"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_NEXT));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(0).segments).hasSize(4);
    assertThat(mediaPlaylists.get(0).trailingParts).hasSize(2);
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).segments).hasSize(4);
    assertThat(mediaPlaylists.get(1).trailingParts).hasSize(3);
  }

  @Test
  public void start_playlistCanBlockReloadLowLatencyFullSegment_correctMsnAndPartParams()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_msn=14&_HLS_part=0"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_NEXT));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(0).segments).hasSize(4);
    assertThat(mediaPlaylists.get(0).trailingParts).isEmpty();
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).segments).hasSize(4);
    assertThat(mediaPlaylists.get(1).trailingParts).hasSize(1);
  }

  @Test
  public void start_playlistCanBlockReloadLowLatencyFullSegmentWithPreloadPart_ignoresPreloadPart()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_msn=14&_HLS_part=0"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD_NEXT));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(0).segments).hasSize(4);
    assertThat(mediaPlaylists.get(0).trailingParts).hasSize(1);
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).segments).hasSize(4);
    assertThat(mediaPlaylists.get(1).trailingParts).hasSize(2);
  }

  @Test
  public void start_lowLatencyScheduleReloadForPlayingButNonPrimaryPlaylist() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/english/audio-playlist.m3u8",
              "/english/audio-playlist.m3u8?_HLS_msn=14&_HLS_part=0",
              "/english/audio-playlist.m3u8?_HLS_msn=14&_HLS_part=1",
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT_WITH_AUDIO_RENDITIONS),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_AUDIO),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_AUDIO_NEXT),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_AUDIO_NEXT2));

    DefaultHlsPlaylistTracker defaultHlsPlaylistTracker =
        new DefaultHlsPlaylistTracker(
            dataType -> new DefaultHttpDataSource.Factory().createDataSource(),
            new DefaultLoadErrorHandlingPolicy(),
            new DefaultHlsPlaylistParserFactory(),
            /* cmcdConfiguration= */ null,
            /* downloadExecutorSupplier= */ null);
    AtomicInteger playlistChangedCounter = new AtomicInteger();
    AtomicReference<TimeoutException> audioPlaylistRefreshExceptionRef = new AtomicReference<>();
    defaultHlsPlaylistTracker.addListener(
        new HlsPlaylistTracker.PlaylistEventListener() {
          @Override
          public void onPlaylistChanged() {
            playlistChangedCounter.addAndGet(1);
            // Upon the first call of onPlaylistChanged(), we simulate the situation that the first
            // audio rendition is chosen for playback.
            Uri url = defaultHlsPlaylistTracker.getMultivariantPlaylist().audios.get(0).url;
            if (!defaultHlsPlaylistTracker.isSnapshotValid(url)) {
              defaultHlsPlaylistTracker.refreshPlaylist(url);
              try {
                // Make sure that the audio playlist has been refreshed and we've got a playlist
                // snapshot of it.
                RobolectricUtil.runMainLooperUntil(
                    () -> defaultHlsPlaylistTracker.isSnapshotValid(url));
              } catch (TimeoutException e) {
                audioPlaylistRefreshExceptionRef.set(e);
              }
              // Simulate the operations in HlsChunkSource where we keep loading and get a playlist
              // snapshot of the given url when there is a valid snapshot available.
              defaultHlsPlaylistTracker.getPlaylistSnapshot(url, /* isForPlayback= */ true);
              // We have to force the expected audio playlists to load in the first call of
              // onPlaylistChanged(), as once this method returned for "/media0/playlist.m3u8", the
              // DefaultHlsPlaylistTracker will continue reloading the primary playlist, and it's
              // hard to make the order of loading primary and audio playlists deterministic. Thus,
              // we will verify if audio playlists are reloaded as expected first, and ignore the
              // reloading of primary playlists, whose behaviour was already verified in the other
              // tests.
              try {
                RobolectricUtil.runMainLooperUntil(() -> playlistChangedCounter.get() >= 4);
              } catch (TimeoutException e) {
                audioPlaylistRefreshExceptionRef.set(e);
              }
            }
          }

          @Override
          public boolean onPlaylistError(
              Uri url, LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo, boolean forceRetry) {
            return false;
          }
        });

    defaultHlsPlaylistTracker.start(
        Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
        new MediaSourceEventListener.EventDispatcher(),
        mediaPlaylist -> {},
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> playlistChangedCounter.get() >= 4);
    defaultHlsPlaylistTracker.stop();

    assertThat(audioPlaylistRefreshExceptionRef.get()).isNull();
    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void start_lowLatencyNotScheduleReloadForNonPlayingPlaylist() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media1/playlist.m3u8",
              "/media1/playlist.m3u8",
              "/media1/playlist.m3u8?_HLS_msn=14&_HLS_part=0",
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_PRELOAD_NEXT));

    DefaultHlsPlaylistTracker defaultHlsPlaylistTracker =
        new DefaultHlsPlaylistTracker(
            dataType -> new DefaultHttpDataSource.Factory().createDataSource(),
            new DefaultLoadErrorHandlingPolicy(),
            new DefaultHlsPlaylistParserFactory(),
            /* cmcdConfiguration= */ null,
            /* downloadExecutorSupplier= */ null);
    List<HlsMediaPlaylist> mediaPlaylists = new ArrayList<>();
    AtomicInteger playlistCounter = new AtomicInteger();
    AtomicReference<TimeoutException> primaryPlaylistChangeExceptionRef = new AtomicReference<>();
    defaultHlsPlaylistTracker.addListener(
        new HlsPlaylistTracker.PlaylistEventListener() {
          @Override
          public void onPlaylistChanged() {
            // Upon the first call of onPlaylistChanged(), we simulate the situation that the
            // primary playlist url changes.
            Uri url = defaultHlsPlaylistTracker.getMultivariantPlaylist().mediaPlaylistUrls.get(1);
            if (defaultHlsPlaylistTracker.isSnapshotValid(url)) {
              return;
            }
            defaultHlsPlaylistTracker.refreshPlaylist(url);
            try {
              // Make sure that the playlist for the new url has been refreshed and set it as the
              // current primary playlist, before this method returns.
              RobolectricUtil.runMainLooperUntil(
                  () ->
                      defaultHlsPlaylistTracker.getPlaylistSnapshot(url, /* isForPlayback= */ true)
                          != null);
            } catch (TimeoutException e) {
              primaryPlaylistChangeExceptionRef.set(e);
            }
          }

          @Override
          public boolean onPlaylistError(
              Uri url, LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo, boolean forceRetry) {
            return false;
          }
        });

    defaultHlsPlaylistTracker.start(
        Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
        new MediaSourceEventListener.EventDispatcher(),
        mediaPlaylist -> {
          mediaPlaylists.add(mediaPlaylist);
          playlistCounter.addAndGet(1);
        },
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> playlistCounter.get() >= 2);
    defaultHlsPlaylistTracker.stop();

    assertThat(primaryPlaylistChangeExceptionRef.get()).isNull();
    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(0).segments).hasSize(4);
    assertThat(mediaPlaylists.get(0).trailingParts).hasSize(1);
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).segments).hasSize(4);
    assertThat(mediaPlaylists.get(1).trailingParts).hasSize(2);
  }

  @Test
  public void start_refreshScheduledButNotExecutedForNonPlayingPlaylist() throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media1/playlist.m3u8",
              "/media1/playlist.m3u8",
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_NOT_SKIP_NEXT));
    DefaultHlsPlaylistTracker defaultHlsPlaylistTracker =
        new DefaultHlsPlaylistTracker(
            dataType -> new DefaultHttpDataSource.Factory().createDataSource(),
            new DefaultLoadErrorHandlingPolicy(),
            new DefaultHlsPlaylistParserFactory(),
            /* cmcdConfiguration= */ null,
            /* downloadExecutorSupplier= */ null);
    AtomicInteger playlistChangedCounter = new AtomicInteger();
    defaultHlsPlaylistTracker.addListener(
        new HlsPlaylistTracker.PlaylistEventListener() {
          @Override
          public void onPlaylistChanged() {
            playlistChangedCounter.addAndGet(1);
          }

          @Override
          public boolean onPlaylistError(
              Uri url, LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo, boolean forceRetry) {
            return false;
          }
        });

    defaultHlsPlaylistTracker.start(
        Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
        new MediaSourceEventListener.EventDispatcher(),
        mediaPlaylist -> {},
        BandwidthMeter.NO_OP);
    // Wait for playlist A (media0) to load. Since A is the initial primary playlist, it will
    // schedule a refresh with a 4-second delay (based on target duration) once it finishes loading.
    Uri playlistUrlA = Uri.parse(mockWebServer.url("/media0/playlist.m3u8").toString());
    RobolectricUtil.runMainLooperUntil(
        () -> defaultHlsPlaylistTracker.isSnapshotValid(playlistUrlA));
    // Explicitly activate playlist A for playback.
    defaultHlsPlaylistTracker.getPlaylistSnapshot(playlistUrlA, /* isForPlayback= */ true);
    // Refresh B (media1) and wait for it to load.
    Uri playlistUrlB = Uri.parse(mockWebServer.url("/media1/playlist.m3u8").toString());
    defaultHlsPlaylistTracker.refreshPlaylist(playlistUrlB);
    RobolectricUtil.runMainLooperUntil(
        () -> defaultHlsPlaylistTracker.isSnapshotValid(playlistUrlB));
    // Make playlist B primary and active.
    defaultHlsPlaylistTracker.getPlaylistSnapshot(playlistUrlB, /* isForPlayback= */ true);
    // Explicitly deactivate playlist A for playback, simulating the track change. The Playlist A
    // is now non-primary and inactive, but its refresh task is still pending in the looper queue
    // with the original 4-second delay.
    defaultHlsPlaylistTracker.deactivatePlaylistForPlayback(playlistUrlA);
    // Keep running the looper until the scheduled refresh tasks got run. Both A and B's refresh
    // tasks will run by the looper. But A is no longer primary or active, so its task should skip
    // the actual loading.
    RobolectricUtil.runMainLooperUntil(
        /* maxTimeDiffMs= */ 10_000, () -> playlistChangedCounter.get() >= 3);
    defaultHlsPlaylistTracker.stop();

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void
      start_refreshPlaylistWithAllowingDeliveryDirectives_requestWithCorrectDeliveryDirectives()
          throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_msn=14&_HLS_part=0",
              "/media0/playlist.m3u8?_HLS_msn=14&_HLS_part=1"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_NEXT),
            getMockResponse(
                SAMPLE_M3U8_LIVE_MEDIA_CAN_BLOCK_RELOAD_LOW_LATENCY_FULL_SEGMENT_NEXT2));

    DefaultHlsPlaylistTracker defaultHlsPlaylistTracker =
        new DefaultHlsPlaylistTracker(
            dataType -> new DefaultHttpDataSource.Factory().createDataSource(),
            new DefaultLoadErrorHandlingPolicy(),
            new DefaultHlsPlaylistParserFactory(),
            /* cmcdConfiguration= */ null,
            /* downloadExecutorSupplier= */ null);
    List<HlsMediaPlaylist> mediaPlaylists = new ArrayList<>();
    AtomicInteger playlistCounter = new AtomicInteger();
    AtomicReference<TimeoutException> playlistRefreshExceptionRef = new AtomicReference<>();
    defaultHlsPlaylistTracker.addListener(
        new HlsPlaylistTracker.PlaylistEventListener() {
          @Override
          public void onPlaylistChanged() {
            // Upon the first call of onPlaylistChanged(), we call refreshPlaylist(Uri) on the
            // same url.
            defaultHlsPlaylistTracker.refreshPlaylist(
                defaultHlsPlaylistTracker.getMultivariantPlaylist().mediaPlaylistUrls.get(0));
            try {
              // Make sure that playlist reload triggered by refreshPlaylist(Uri) call comes before
              // the one triggered by the regular scheduling, to ensure the playlists to be
              // verified are in the expected order.
              RobolectricUtil.runMainLooperUntil(() -> playlistCounter.get() >= 2);
            } catch (TimeoutException e) {
              playlistRefreshExceptionRef.set(e);
            }
          }

          @Override
          public boolean onPlaylistError(
              Uri url, LoadErrorHandlingPolicy.LoadErrorInfo loadErrorInfo, boolean forceRetry) {
            return false;
          }
        });

    defaultHlsPlaylistTracker.start(
        Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
        new MediaSourceEventListener.EventDispatcher(),
        mediaPlaylist -> {
          mediaPlaylists.add(mediaPlaylist);
          playlistCounter.addAndGet(1);
        },
        BandwidthMeter.NO_OP);
    RobolectricUtil.runMainLooperUntil(() -> playlistCounter.get() >= 3);
    defaultHlsPlaylistTracker.stop();

    assertThat(playlistRefreshExceptionRef.get()).isNull();
    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(0).segments).hasSize(4);
    assertThat(mediaPlaylists.get(0).trailingParts).isEmpty();
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).segments).hasSize(4);
    assertThat(mediaPlaylists.get(1).trailingParts).hasSize(1);
    assertThat(mediaPlaylists.get(2).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(2).segments).hasSize(4);
    assertThat(mediaPlaylists.get(2).trailingParts).hasSize(2);
  }

  @Test
  public void start_httpBadRequest_forcesFullNonBlockingPlaylistRequest()
      throws IOException, TimeoutException, InterruptedException {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_msn=16&_HLS_skip=YES",
              "/media0/playlist.m3u8",
              "/media0/playlist.m3u8?_HLS_msn=17&_HLS_skip=YES"
            },
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_AND_BLOCK_RELOAD),
            new MockResponse().setResponseCode(400),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_AND_BLOCK_RELOAD_NEXT),
            getMockResponse(SAMPLE_M3U8_LIVE_MEDIA_CAN_SKIP_UNTIL_AND_BLOCK_RELOAD_NEXT_SKIPPED));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            /* dataSourceFactory= */ new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 3);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).mediaSequence).isEqualTo(10);
    assertThat(mediaPlaylists.get(1).mediaSequence).isEqualTo(11);
    assertThat(mediaPlaylists.get(2).mediaSequence).isEqualTo(12);
  }

  @Test
  public void
      start_withRedundantVariantsAndRenditions_fallbackToRedundantStreamWhenFirstPrimaryPlaylistFailedToLoad()
          throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"/multivariant.m3u8", "/media/high.m3u8", "/media-b/high.m3u8"},
            getMockResponse(SAMPLE_M3U8_MULTIVARIANT_WITH_REDUNDANT_VARIANTS_AND_RENDITIONS),
            new MockResponse().setResponseCode(404),
            getMockResponse(SAMPLE_M3U8_MEDIA_PLAYLIST));

    List<HlsMediaPlaylist> unusedMediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            /* dataSourceFactory= */ new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 1);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void
      start_withRedundantVariantsAndRenditions_fallbackToAnotherTrackWhenLocationFallbackIsImpossible()
          throws Exception {
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8", "/media/high.m3u8", "/media-b/high.m3u8", "/media/low.m3u8"
            },
            getMockResponse(SAMPLE_M3U8_MULTIVARIANT_WITH_REDUNDANT_VARIANTS_AND_RENDITIONS),
            new MockResponse().setResponseCode(404),
            new MockResponse().setResponseCode(404),
            getMockResponse(SAMPLE_M3U8_MEDIA_PLAYLIST));

    List<HlsMediaPlaylist> unusedMediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            /* dataSourceFactory= */ new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 1);

    assertRequestUrlsCalled(httpUrls);
  }

  @Test
  public void start_playlistUpdateWithoutInitSegment_persistsInitSegmentFromPreviousPlaylist()
      throws Exception {
    String playlist =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:1\n"
            + "#EXT-X-MAP:URI=\"init0.mp4\"\n"
            + "#EXTINF:10.0,\n"
            + "file0.mp4\n"
            + "#EXT-X-DISCONTINUITY\n"
            + "#EXT-X-MAP:URI=\"init1.mp4\"\n"
            + "#EXTINF:10.0,\n"
            + "file1.mp4\n";
    String playlistUpdate =
        "#EXTM3U\n"
            + "#EXT-X-VERSION:6\n"
            + "#EXT-X-TARGETDURATION:10\n"
            + "#EXT-X-MEDIA-SEQUENCE:2\n"
            + "#EXTINF:10.0,\n"
            + "file2.mp4\n";

    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {"/multivariant.m3u8", "/media0/playlist.m3u8", "/media0/playlist.m3u8"},
            getMockResponse(SAMPLE_M3U8_LIVE_MULTIVARIANT),
            new MockResponse().setResponseCode(200).setBody(playlist),
            new MockResponse().setResponseCode(200).setBody(playlistUpdate));

    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            new DefaultHttpDataSource.Factory(),
            /* downloadExecutorSupplier= */ null,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    HlsMediaPlaylist.Segment segment0 = mediaPlaylists.get(0).segments.get(0);
    HlsMediaPlaylist.Segment segment1 = mediaPlaylists.get(0).segments.get(1);
    HlsMediaPlaylist.Segment segment2 = mediaPlaylists.get(1).segments.get(0);
    assertThat(segment0.url).isEqualTo("file0.mp4");
    assertThat(segment0.initializationSegment.url).isEqualTo("init0.mp4");
    assertThat(segment1.url).isEqualTo("file1.mp4");
    assertThat(segment1.initializationSegment.url).isEqualTo("init1.mp4");
    assertThat(segment2.url).isEqualTo("file2.mp4");
    assertThat(segment2.initializationSegment.url).isEqualTo("init1.mp4");
  }

  @Test
  public void start_withContentSteering_switchesToMostPrioritizedPathway() throws Exception {
    String steeringManifest =
        "{\"VERSION\": 1, \"TTL\": 300, \"PATHWAY-PRIORITY\": [\"CDN-B\", \"CDN-A\"]}";
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/steering?_HLS_pathway=CDN-A&_HLS_throughput=0",
              "/cdn-a/720p.m3u8",
              "/cdn-b/720p.m3u8"
            },
            getMockResponse(SAMPLE_M3U8_MULTIVARIANT_WITH_CONTENT_STEERING),
            new MockResponse().setResponseCode(200).setBody(steeringManifest),
            new MockResponse().setResponseCode(200).setBody(CDN_A_PLAYLIST),
            new MockResponse().setResponseCode(200).setBody(CDN_B_PLAYLIST));
    MediaSourceEventListener mockListener = mock(MediaSourceEventListener.class);
    MediaSourceEventListener.EventDispatcher eventDispatcher =
        new MediaSourceEventListener.EventDispatcher();
    eventDispatcher.addEventListener(Util.createHandlerForCurrentLooper(), mockListener);

    // Use the directExecutor() to ensure the order of the playlist arrivals.
    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            /* dataSourceFactory= */ new DefaultHttpDataSource.Factory(),
            () -> ReleasableExecutor.from(directExecutor(), e -> {}),
            eventDispatcher,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).segments.get(0).url).endsWith("a-segment1.ts");
    assertThat(mediaPlaylists.get(1).segments.get(0).url).endsWith("b-segment1.ts");
    ArgumentCaptor<LoadEventInfo> loadStartedCaptor = ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadStartedMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    ArgumentCaptor<LoadEventInfo> loadCompletedCaptor =
        ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadCompletedMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    verify(mockListener, times(4))
        .onLoadStarted(
            anyInt(),
            any(),
            loadStartedCaptor.capture(),
            loadStartedMediaLoadDataCaptor.capture(),
            anyInt());
    verify(mockListener, times(4))
        .onLoadCompleted(
            anyInt(),
            any(),
            loadCompletedCaptor.capture(),
            loadCompletedMediaLoadDataCaptor.capture());
    verify(mockListener, never()).onLoadError(anyInt(), any(), any(), any(), any(), anyBoolean());
    verifySteeredPathwayIds(
        loadStartedCaptor.getAllValues(),
        loadStartedMediaLoadDataCaptor.getAllValues(),
        "CDN-A",
        "CDN-B");
    verifySteeredPathwayIds(
        loadCompletedCaptor.getAllValues(),
        loadCompletedMediaLoadDataCaptor.getAllValues(),
        "CDN-A",
        "CDN-B");
  }

  @Test
  public void
      start_withContentSteeringAndPrimaryPlaylistLoadFailures_switchesTrackFirstAndThenSwitchesToLessPrioritizedPathway()
          throws Exception {
    String steeringManifest =
        "{\"VERSION\": 1, \"TTL\": 300, \"PATHWAY-PRIORITY\": [\"CDN-A\", \"CDN-B\"]}";
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/steering?_HLS_pathway=CDN-A&_HLS_throughput=0",
              "/cdn-a/720p.m3u8",
              "/cdn-a/360p.m3u8",
              "/cdn-b/360p.m3u8"
            },
            getMockResponse(SAMPLE_M3U8_MULTIVARIANT_WITH_CONTENT_STEERING),
            new MockResponse().setResponseCode(200).setBody(steeringManifest),
            new MockResponse().setResponseCode(404),
            new MockResponse().setResponseCode(404),
            new MockResponse().setResponseCode(200).setBody(CDN_B_PLAYLIST));
    MediaSourceEventListener mockListener = mock(MediaSourceEventListener.class);
    MediaSourceEventListener.EventDispatcher eventDispatcher =
        new MediaSourceEventListener.EventDispatcher();
    eventDispatcher.addEventListener(Util.createHandlerForCurrentLooper(), mockListener);

    // Use the directExecutor() to ensure the order of the playlist arrivals.
    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            /* dataSourceFactory= */ new DefaultHttpDataSource.Factory(),
            () -> ReleasableExecutor.from(directExecutor(), e -> {}),
            eventDispatcher,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 1);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).segments.get(0).url).isEqualTo("b-segment1.ts");
    ArgumentCaptor<LoadEventInfo> loadStartedCaptor = ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadStartedMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    ArgumentCaptor<LoadEventInfo> loadCompletedCaptor =
        ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadCompletedMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    ArgumentCaptor<LoadEventInfo> loadErrorCaptor = ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadErrorMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    verify(mockListener, times(5))
        .onLoadStarted(
            anyInt(),
            any(),
            loadStartedCaptor.capture(),
            loadStartedMediaLoadDataCaptor.capture(),
            anyInt());
    verify(mockListener, times(3))
        .onLoadCompleted(
            anyInt(),
            any(),
            loadCompletedCaptor.capture(),
            loadCompletedMediaLoadDataCaptor.capture());
    verify(mockListener, times(2))
        .onLoadError(
            anyInt(),
            any(),
            loadErrorCaptor.capture(),
            loadErrorMediaLoadDataCaptor.capture(),
            any(),
            anyBoolean());
    verifySteeredPathwayIds(
        loadStartedCaptor.getAllValues(),
        loadStartedMediaLoadDataCaptor.getAllValues(),
        "CDN-A",
        "CDN-B");
    verifySteeredPathwayIds(
        loadCompletedCaptor.getAllValues(),
        loadCompletedMediaLoadDataCaptor.getAllValues(),
        "CDN-B");
    verifySteeredPathwayIds(
        loadErrorCaptor.getAllValues(), loadErrorMediaLoadDataCaptor.getAllValues(), "CDN-A");
  }

  @Test
  public void start_withContentSteeringAndPathwayClones_switchesToClonedPathway() throws Exception {
    String steeringManifest =
        "{\n"
            + "  \"VERSION\": 1,\n"
            + "  \"TTL\": 300,\n"
            + "  \"PATHWAY-PRIORITY\": [\"CDN-A-CLONE\", \"CDN-A\"],\n"
            + "  \"PATHWAY-CLONES\": [\n"
            + "    {\n"
            + "      \"BASE-ID\": \"CDN-A\",\n"
            + "      \"ID\": \"CDN-A-CLONE\",\n"
            + "      \"URI-REPLACEMENT\": {\n"
            + "        \"PARAMS\": {\n"
            + "          \"param1\": \"value1\"\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  ]\n"
            + "}";
    List<HttpUrl> httpUrls =
        enqueueWebServerResponses(
            new String[] {
              "/multivariant.m3u8",
              "/steering?_HLS_pathway=CDN-A&_HLS_throughput=0",
              "/cdn-a/720p.m3u8",
              "/cdn-a/720p.m3u8?param1=value1"
            },
            getMockResponse(SAMPLE_M3U8_MULTIVARIANT_WITH_CONTENT_STEERING),
            new MockResponse().setResponseCode(200).setBody(steeringManifest),
            new MockResponse().setResponseCode(200).setBody(CDN_A_PLAYLIST),
            new MockResponse().setResponseCode(200).setBody(CDN_A_CLONE_PLAYLIST));
    MediaSourceEventListener mockListener = mock(MediaSourceEventListener.class);
    MediaSourceEventListener.EventDispatcher eventDispatcher =
        new MediaSourceEventListener.EventDispatcher();
    eventDispatcher.addEventListener(Util.createHandlerForCurrentLooper(), mockListener);

    // Use the directExecutor() to ensure the order of the playlist arrivals.
    List<HlsMediaPlaylist> mediaPlaylists =
        runPlaylistTrackerAndCollectMediaPlaylists(
            /* dataSourceFactory= */ new DefaultHttpDataSource.Factory(),
            () -> ReleasableExecutor.from(directExecutor(), e -> {}),
            eventDispatcher,
            Uri.parse(mockWebServer.url("/multivariant.m3u8").toString()),
            /* awaitedMediaPlaylistCount= */ 2);

    assertRequestUrlsCalled(httpUrls);
    assertThat(mediaPlaylists.get(0).segments.get(0).url).endsWith("a-segment1.ts");
    assertThat(mediaPlaylists.get(1).segments.get(0).url).endsWith("a-clone-segment1.ts");
    ArgumentCaptor<LoadEventInfo> loadStartedCaptor = ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadStartedMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    ArgumentCaptor<LoadEventInfo> loadCompletedCaptor =
        ArgumentCaptor.forClass(LoadEventInfo.class);
    ArgumentCaptor<MediaLoadData> loadCompletedMediaLoadDataCaptor =
        ArgumentCaptor.forClass(MediaLoadData.class);
    verify(mockListener, times(4))
        .onLoadStarted(
            anyInt(),
            any(),
            loadStartedCaptor.capture(),
            loadStartedMediaLoadDataCaptor.capture(),
            anyInt());
    verify(mockListener, times(4))
        .onLoadCompleted(
            anyInt(),
            any(),
            loadCompletedCaptor.capture(),
            loadCompletedMediaLoadDataCaptor.capture());
    verify(mockListener, never()).onLoadError(anyInt(), any(), any(), any(), any(), anyBoolean());
    verifySteeredPathwayIds(
        loadStartedCaptor.getAllValues(),
        loadStartedMediaLoadDataCaptor.getAllValues(),
        "CDN-A",
        "CDN-A-CLONE");
    verifySteeredPathwayIds(
        loadCompletedCaptor.getAllValues(),
        loadCompletedMediaLoadDataCaptor.getAllValues(),
        "CDN-A",
        "CDN-A-CLONE");
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

  private static List<HlsMediaPlaylist> runPlaylistTrackerAndCollectMediaPlaylists(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      Uri multivariantPlaylistUri,
      int awaitedMediaPlaylistCount)
      throws TimeoutException {
    return runPlaylistTrackerAndCollectMediaPlaylists(
        dataSourceFactory,
        downloadExecutorSupplier,
        new MediaSourceEventListener.EventDispatcher(),
        multivariantPlaylistUri,
        awaitedMediaPlaylistCount);
  }

  private static List<HlsMediaPlaylist> runPlaylistTrackerAndCollectMediaPlaylists(
      DataSource.Factory dataSourceFactory,
      @Nullable Supplier<ReleasableExecutor> downloadExecutorSupplier,
      MediaSourceEventListener.EventDispatcher eventDispatcher,
      Uri multivariantPlaylistUri,
      int awaitedMediaPlaylistCount)
      throws TimeoutException {

    DefaultHlsPlaylistTracker defaultHlsPlaylistTracker =
        new DefaultHlsPlaylistTracker(
            dataType -> dataSourceFactory.createDataSource(),
            new DefaultLoadErrorHandlingPolicy(),
            new DefaultHlsPlaylistParserFactory(),
            /* cmcdConfiguration= */ null,
            downloadExecutorSupplier);

    List<HlsMediaPlaylist> mediaPlaylists = new ArrayList<>();
    AtomicInteger playlistCounter = new AtomicInteger();
    defaultHlsPlaylistTracker.start(
        multivariantPlaylistUri,
        eventDispatcher,
        mediaPlaylist -> {
          mediaPlaylists.add(mediaPlaylist);
          playlistCounter.addAndGet(1);
        },
        BandwidthMeter.NO_OP);

    RobolectricUtil.runMainLooperUntil(
        /* maxTimeDiffMs= */ 10_000, // Account for scheduled playlist refresh delays
        () -> playlistCounter.get() >= awaitedMediaPlaylistCount);

    defaultHlsPlaylistTracker.stop();
    return mediaPlaylists;
  }

  private static void verifySteeredPathwayIds(
      List<LoadEventInfo> loadEventInfos,
      List<MediaLoadData> mediaLoadDatas,
      String... expectedPathwayIds) {
    ImmutableSet.Builder<String> mediaPlaylistLoadingSteeredPathwayIds = ImmutableSet.builder();
    for (int i = 0; i < loadEventInfos.size(); i++) {
      LoadEventInfo eventInfo = loadEventInfos.get(i);
      MediaLoadData mediaLoadData = mediaLoadDatas.get(i);
      if (eventInfo.uri.getPath() != null
          && eventInfo.uri.getPath().endsWith("multivariant.m3u8")) {
        // The multivariant playlist load event is initiated before the content steering manifest
        // is parsed, so its steered pathway ID must be null.
        assertWithMessage("loadEventInfos[%s]: %s", i, loadEventInfoString(eventInfo))
            .that(eventInfo.steeredPathwayId)
            .isNull();
      } else if (mediaLoadData.dataType == C.DATA_TYPE_STEERING_MANIFEST) {
        // Content steering manifest load events are decoupled from any specific steered pathway,
        // so their steered pathway ID must be null.
        assertWithMessage("loadEventInfos[%s]: %s", i, loadEventInfoString(eventInfo))
            .that(eventInfo.steeredPathwayId)
            .isNull();
      } else if (eventInfo.steeredPathwayId != null) {
        // Collect the steered pathway IDs from the media playlist requests.
        mediaPlaylistLoadingSteeredPathwayIds.add(eventInfo.steeredPathwayId);
      }
    }
    assertThat(mediaPlaylistLoadingSteeredPathwayIds.build())
        .containsExactlyElementsIn(expectedPathwayIds);
  }

  private static String loadEventInfoString(LoadEventInfo loadEventInfo) {
    return "LoadEventInfo{uri=" + loadEventInfo.uri + "}";
  }

  private static MockResponse getMockResponse(String assetFile) throws IOException {
    return new MockResponse().setResponseCode(200).setBody(new Buffer().write(getBytes(assetFile)));
  }

  private static byte[] getBytes(String filename) throws IOException {
    return TestUtil.getByteArray(ApplicationProvider.getApplicationContext(), filename);
  }
}
