/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.exoplayer.hls;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.TimestampAdjuster;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.SeekParameters;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.drm.DrmSessionEventListener;
import androidx.media3.exoplayer.drm.DrmSessionManager;
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser;
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker;
import androidx.media3.exoplayer.source.MediaSourceEventListener;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.test.utils.ExoPlayerTestRunner;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.FakeTrackSelection;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableListMultimap;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.shadows.ShadowSystemClock;

/** Unit tests for {@link HlsChunkSource}. */
@RunWith(AndroidJUnit4.class)
public class HlsChunkSourceTest {

  private static final String PLAYLIST = "media/m3u8/media_playlist";
  private static final String PLAYLIST_INDEPENDENT_SEGMENTS =
      "media/m3u8/media_playlist_independent_segments";
  private static final String PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY =
      "media/m3u8/live_low_latency_segments_only";
  private static final String PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_AND_PARTS =
      "media/m3u8/live_low_latency_segments_and_parts";
  private static final String PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_AND_SINGLE_PRELOAD_PART =
      "media/m3u8/live_low_latency_segments_and_single_preload_part";
  private static final Uri PLAYLIST_URI = Uri.parse("http://example.com/");
  private static final Uri PLAYLIST_URI_2 = Uri.parse("http://example2.com/");
  private static final long PLAYLIST_START_PERIOD_OFFSET_US = 8_000_000L;
  private static final Uri IFRAME_URI = Uri.parse("http://example.com/iframe");
  private static final Format IFRAME_FORMAT =
      new Format.Builder()
          .setSampleMimeType(MimeTypes.VIDEO_H264)
          .setAverageBitrate(30_000)
          .setWidth(1280)
          .setHeight(720)
          .setRoleFlags(C.ROLE_FLAG_TRICK_PLAY)
          .build();

  @Test
  public void getAdjustedSeekPositionUs_previousSync() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.PREVIOUS_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(16_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_nextSync() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.NEXT_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(20_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_nextSyncAtEnd() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(24_000_000), SeekParameters.NEXT_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(24_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_closestSyncBefore() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.CLOSEST_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(16_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_closestSyncAfter() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(19_000_000), SeekParameters.CLOSEST_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(20_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_exact() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.EXACT);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(17_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUsNoIndependentSegments_tryPreviousSync() throws IOException {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.PREVIOUS_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(16_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUsNoIndependentSegments_notTryNextSync() throws IOException {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.NEXT_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(17_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUsNoIndependentSegments_alwaysTryClosestSyncBefore()
      throws IOException {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST);

    long adjustedPositionUs1 =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(17_000_000), SeekParameters.CLOSEST_SYNC);
    long adjustedPositionUs2 =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(19_000_000), SeekParameters.CLOSEST_SYNC);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs1)).isEqualTo(16_000_000);
    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs2)).isEqualTo(16_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUsNoIndependentSegments_exact() throws IOException {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(100_000_000), SeekParameters.EXACT);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(100_000_000);
  }

  @Test
  public void getAdjustedSeekPositionUs_emptyPlaylist() throws IOException {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST);

    long adjustedPositionUs =
        testChunkSource.getAdjustedSeekPositionUs(
            playlistTimeToPeriodTimeUs(100_000_000), SeekParameters.EXACT);

    assertThat(periodTimeToPlaylistTimeUs(adjustedPositionUs)).isEqualTo(100_000_000);
  }

  @Test
  public void getNextChunk_chunkSourceWithDefaultCmcdConfiguration_setsCmcdHttpRequestHeaders()
      throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory = CmcdConfiguration.Factory.DEFAULT;
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=800,d=4000,ot=v,tb=800",
            "CMCD-Request",
            "bl=0,dl=0,nor=\"..%2F3.mp4\",nrr=\"0-\",su",
            "CMCD-Session",
            "cid=\"mediaId\",sf=h,sid=\"" + cmcdConfiguration.sessionId + "\",st=v");

    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(3_000_000).setPlaybackSpeed(1.25f).build(),
        /* loadPositionUs= */ 4_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of((HlsMediaChunk) output.chunk),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=800,d=4000,ot=v,tb=800",
            "CMCD-Request",
            "bl=1000,dl=800,nor=\"..%2F3.mp4\",nrr=\"0-\"",
            "CMCD-Session",
            "cid=\"mediaId\",pr=1.25,sf=h,sid=\"" + cmcdConfiguration.sessionId + "\",st=v");

    // Playing mid-chunk, where loadPositionUs is less than playbackPositionUs
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(5_000_000).setPlaybackSpeed(1.25f).build(),
        /* loadPositionUs= */ 4_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of((HlsMediaChunk) output.chunk),
        /* allowEndOfStream= */ true,
        output);

    // buffer length is set to 0 when bufferedDurationUs is negative
    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=800,d=4000,ot=v,tb=800",
            "CMCD-Request",
            "bl=0,dl=0,nor=\"..%2F3.mp4\",nrr=\"0-\"",
            "CMCD-Session",
            "cid=\"mediaId\",pr=1.25,sf=h,sid=\"" + cmcdConfiguration.sessionId + "\",st=v");
  }

  @Test
  public void getNextChunk_chunkSourceWithDefaultCmcdConfiguration_setsCorrectBufferStarvationKey()
      throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory = CmcdConfiguration.Factory.DEFAULT;
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();
    LoadingInfo loadingInfo =
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build();

    testChunkSource.getNextChunk(
        loadingInfo,
        /* loadPositionUs= */ 0,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders).doesNotContainKey("CMCD-Status");

    loadingInfo =
        loadingInfo
            .buildUpon()
            .setPlaybackPositionUs(2_000_000)
            .setLastRebufferRealtimeMs(SystemClock.elapsedRealtime())
            .build();
    ShadowSystemClock.advanceBy(Duration.ofMillis(100));

    testChunkSource.getNextChunk(
        loadingInfo,
        /* loadPositionUs= */ 4_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders).containsEntry("CMCD-Status", "bs");

    loadingInfo = loadingInfo.buildUpon().setPlaybackPositionUs(6_000_000).build();
    ShadowSystemClock.advanceBy(Duration.ofMillis(100));

    testChunkSource.getNextChunk(
        loadingInfo,
        /* loadPositionUs= */ 8_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders).doesNotContainKey("CMCD-Status");
  }

  @Test
  public void getNextChunk_forLivePlaylistWithSegmentsOnly_setsCorrectNextObjectRequest()
      throws IOException {
    // The live playlist contains 6 segments, each 4 seconds long. With a playlist start offset of 8
    // seconds, the total media time is 8 + 6*4 = 32 seconds.
    CmcdConfiguration.Factory cmcdConfigurationFactory = CmcdConfiguration.Factory.DEFAULT;
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // A request to fetch the chunk at 27 seconds should retrieve the second-to-last segment.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(27_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 27_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    // The `nor` key should point to the last segment, which is `FileSequence15.ts`.
    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsEntry("CMCD-Request", "bl=0,dl=0,nor=\"..%2FfileSequence15.ts\",nrr=\"0-\",su");

    // A request to fetch the chunk at 31 seconds should retrieve the last segment.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(31_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 31_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    // Since there are no next segments left, the `nor` key should be absent.
    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsEntry("CMCD-Request", "bl=0,dl=0,su");
  }

  @Test
  public void getNextChunk_forLivePlaylistWithSegmentsAndParts_setsCorrectNextObjectRequest()
      throws IOException {
    // The live playlist contains 6 segments, each 4 seconds long, and two trailing parts of 1
    // second each. With a playlist start offset of 8 seconds, the total media time is 8 + 6*4 + 2*1
    // = 34 seconds.
    CmcdConfiguration.Factory cmcdConfigurationFactory = CmcdConfiguration.Factory.DEFAULT;
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_AND_PARTS, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // A request to fetch the chunk at 31 seconds should retrieve the last segment.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(31_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 31_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    // The `nor` key should point to the first trailing part, which is `FileSequence16.0.ts`.
    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsEntry("CMCD-Request", "bl=0,dl=0,nor=\"..%2FfileSequence16.0.ts\",nrr=\"0-\",su");

    // A request to fetch the chunk at 34 seconds should retrieve the first trailing part.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(34_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 34_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    // The `nor` key should point to the second trailing part, which is `FileSequence16.1.ts`.
    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsEntry("CMCD-Request", "bl=0,dl=0,nor=\"..%2FfileSequence16.1.ts\",nrr=\"0-\",su");
  }

  @Test
  public void getNextChunk_chunkSourceWithCustomCmcdConfiguration_setsCmcdHttpRequestHeaders()
      throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public boolean isKeyAllowed(String key) {
                  return !key.equals(CmcdConfiguration.KEY_SESSION_ID);
                }

                @Override
                public int getRequestedMaximumThroughputKbps(int throughputKbps) {
                  return 5 * throughputKbps;
                }
              };

          return new CmcdConfiguration(
              /* sessionId= */ "sessionId",
              /* contentId= */ mediaItem.mediaId + "contentIdSuffix",
              cmcdRequestConfig);
        };
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=800,d=4000,ot=v,tb=800",
            "CMCD-Request",
            "bl=0,dl=0,nor=\"..%2F3.mp4\",nrr=\"0-\",su",
            "CMCD-Session",
            "cid=\"mediaIdcontentIdSuffix\",sf=h,st=v",
            "CMCD-Status",
            "rtp=4000");
  }

  @Test
  public void
      getNextChunk_chunkSourceWithCustomCmcdConfigurationAndCustomData_setsCmcdHttpRequestHeaders()
          throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public ImmutableListMultimap<@CmcdConfiguration.HeaderKey String, String>
                    getCustomData() {
                  return new ImmutableListMultimap.Builder<
                          @CmcdConfiguration.HeaderKey String, String>()
                      .put(CmcdConfiguration.KEY_CMCD_OBJECT, "key-1=1")
                      .put(CmcdConfiguration.KEY_CMCD_REQUEST, "key-2=\"stringValue\"")
                      .put(CmcdConfiguration.KEY_CMCD_SESSION, "com.example-key3=3")
                      .put(CmcdConfiguration.KEY_CMCD_STATUS, "com.example.test-key4=5.0")
                      .build();
                }
              };

          return new CmcdConfiguration(
              /* sessionId= */ "sessionId", /* contentId= */ mediaItem.mediaId, cmcdRequestConfig);
        };
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(output.chunk.dataSpec.httpRequestHeaders)
        .containsExactly(
            "CMCD-Object",
            "br=800,d=4000,key-1=1,ot=v,tb=800",
            "CMCD-Request",
            "bl=0,dl=0,key-2=\"stringValue\",nor=\"..%2F3.mp4\",nrr=\"0-\",su",
            "CMCD-Session",
            "cid=\"mediaId\",com.example-key3=3,sf=h,sid=\""
                + cmcdConfiguration.sessionId
                + "\",st=v",
            "CMCD-Status",
            "com.example.test-key4=5.0");
  }

  @Test
  public void
      getNextChunk_chunkSourceWithCustomCmcdConfigurationAndCustomData_setsCmcdHttpQueryParameters()
          throws Exception {
    CmcdConfiguration.Factory cmcdConfigurationFactory =
        mediaItem -> {
          CmcdConfiguration.RequestConfig cmcdRequestConfig =
              new CmcdConfiguration.RequestConfig() {
                @Override
                public ImmutableListMultimap<@CmcdConfiguration.HeaderKey String, String>
                    getCustomData() {
                  return new ImmutableListMultimap.Builder<
                          @CmcdConfiguration.HeaderKey String, String>()
                      .put(CmcdConfiguration.KEY_CMCD_OBJECT, "com.example.test-key-1=1")
                      .put(CmcdConfiguration.KEY_CMCD_REQUEST, "key-2=\"stringValue\"")
                      .build();
                }
              };

          return new CmcdConfiguration(
              /* sessionId= */ "sessionId",
              /* contentId= */ mediaItem.mediaId,
              cmcdRequestConfig,
              CmcdConfiguration.MODE_QUERY_PARAMETER);
        };
    MediaItem mediaItem = new MediaItem.Builder().setMediaId("mediaId").build();
    CmcdConfiguration cmcdConfiguration =
        cmcdConfigurationFactory.createCmcdConfiguration(mediaItem);
    HlsChunkSource testChunkSource =
        createHlsChunkSource(PLAYLIST_INDEPENDENT_SEGMENTS, cmcdConfiguration);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(0).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 0,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);

    assertThat(
            output.chunk.dataSpec.uri.getQueryParameter(CmcdConfiguration.CMCD_QUERY_PARAMETER_KEY))
        .isEqualTo(
            "bl=0,br=800,cid=\"mediaId\",com.example.test-key-1=1,d=4000,dl=0,"
                + "key-2=\"stringValue\",nor=\"..%2F3.mp4\",nrr=\"0-\",ot=v,sf=h,"
                + "sid=\"sessionId\",st=v,su,tb=800");
  }

  @Test
  public void getNextChunk_reloadingCurrentPreloadPartAfterPublication_returnsShouldSpliceIn()
      throws Exception {
    HlsChunkSource chunkSource =
        createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_AND_SINGLE_PRELOAD_PART);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();
    // The live playlist contains 6 finished segments, each 4 seconds long. With a playlist start
    // offset of 8 seconds, the total media time of these segments is 8 + 6*4 = 32 seconds. A
    // request to fetch the chunk at 34 seconds should return the preload part.
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(34_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 34_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    // Verify setup.
    HlsMediaChunk hlsMediaChunk = (HlsMediaChunk) output.chunk;
    assertThat(hlsMediaChunk.isPublished()).isFalse();

    // Request the same part again after the playlist is updated and its duration is known.
    chunkSource = createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_AND_PARTS);
    hlsMediaChunk.publish(/* publishedDurationUs= */ 1_000_000);
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(34_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 34_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(hlsMediaChunk),
        /* allowEndOfStream= */ true,
        output);

    assertThat(((HlsMediaChunk) output.chunk).shouldSpliceIn()).isTrue();
  }

  @Test
  public void
      getNextChunk_changedTrackSelectionWithNonOverlappingSegments_returnsShouldSpliceInFalse()
          throws Exception {
    HlsChunkSource chunkSource =
        createHlsChunkSource(
            ImmutableMap.of(
                PLAYLIST_URI,
                PLAYLIST_INDEPENDENT_SEGMENTS,
                PLAYLIST_URI_2,
                PLAYLIST_INDEPENDENT_SEGMENTS));
    TrackGroup trackGroup = chunkSource.getTrackGroup();
    FakeTrackSelection trackSelection1 = new FakeTrackSelection(trackGroup, /* selectedIndex= */ 0);
    trackSelection1.enable();
    FakeTrackSelection trackSelection2 = new FakeTrackSelection(trackGroup, /* selectedIndex= */ 1);
    trackSelection2.enable();
    chunkSource.setTrackSelection(trackSelection1);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // Select first chunk, starting at exactly 16 seconds.
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(8_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 16_000_000,
        /* largestReadPositionUs= */ 8_000_000,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    HlsMediaChunk firstChunk = (HlsMediaChunk) output.chunk;
    loadMediaChunk(firstChunk, chunkSource);
    // Update track selection with a load position (20 seconds) matching the start of the next
    // chunk, so that there is no overlap.
    chunkSource.setTrackSelection(trackSelection2);
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(8_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 20_000_000,
        /* largestReadPositionUs= */ 8_000_000,
        /* queue= */ ImmutableList.of(firstChunk),
        /* allowEndOfStream= */ true,
        output);
    HlsMediaChunk secondChunk = (HlsMediaChunk) output.chunk;

    assertThat(firstChunk.playlistUrl).isEqualTo(PLAYLIST_URI);
    assertThat(firstChunk.shouldSpliceIn()).isFalse();
    assertThat(firstChunk.startTimeUs).isEqualTo(16_000_000);
    assertThat(secondChunk.playlistUrl).isEqualTo(PLAYLIST_URI_2);
    assertThat(secondChunk.shouldSpliceIn()).isFalse();
    assertThat(secondChunk.startTimeUs).isEqualTo(20_000_000);
  }

  @Test
  public void getNextChunk_changedTrackSelectionWithOverlappingSegments_returnsShouldSpliceInTrue()
      throws Exception {
    HlsChunkSource chunkSource =
        createHlsChunkSource(
            ImmutableMap.of(
                PLAYLIST_URI,
                PLAYLIST_INDEPENDENT_SEGMENTS,
                PLAYLIST_URI_2,
                PLAYLIST_INDEPENDENT_SEGMENTS));
    TrackGroup trackGroup = chunkSource.getTrackGroup();
    FakeTrackSelection trackSelection1 = new FakeTrackSelection(trackGroup, /* selectedIndex= */ 0);
    trackSelection1.enable();
    FakeTrackSelection trackSelection2 = new FakeTrackSelection(trackGroup, /* selectedIndex= */ 1);
    trackSelection2.enable();
    chunkSource.setTrackSelection(trackSelection1);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // Select first chunk, starting at exactly 16 seconds.
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(8_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 16_000_000,
        /* largestReadPositionUs= */ 8_000_000,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    HlsMediaChunk firstChunk = (HlsMediaChunk) output.chunk;
    loadMediaChunk(firstChunk, chunkSource);
    // Update track selection with a load position (18 seconds) beyond the start of the chunk (16
    // seconds), so that the data will overlap.
    chunkSource.setTrackSelection(trackSelection2);
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(8_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 18_000_000,
        /* largestReadPositionUs= */ 8_000_000,
        /* queue= */ ImmutableList.of(firstChunk),
        /* allowEndOfStream= */ true,
        output);
    HlsMediaChunk secondChunk = (HlsMediaChunk) output.chunk;

    assertThat(firstChunk.playlistUrl).isEqualTo(PLAYLIST_URI);
    assertThat(firstChunk.shouldSpliceIn()).isFalse();
    assertThat(firstChunk.startTimeUs).isEqualTo(16_000_000);
    assertThat(secondChunk.playlistUrl).isEqualTo(PLAYLIST_URI_2);
    assertThat(secondChunk.shouldSpliceIn()).isTrue();
    assertThat(secondChunk.startTimeUs).isEqualTo(16_000_000);
  }

  @Test
  public void
      getNextChunk_changedTrackSelectionWithOverlappingSegmentsStartingBeforeReadPosition_forcesCurrentSelection()
          throws Exception {
    HlsChunkSource chunkSource =
        createHlsChunkSource(
            ImmutableMap.of(
                PLAYLIST_URI,
                PLAYLIST_INDEPENDENT_SEGMENTS,
                PLAYLIST_URI_2,
                PLAYLIST_INDEPENDENT_SEGMENTS));
    TrackGroup trackGroup = chunkSource.getTrackGroup();
    FakeTrackSelection trackSelection1 = new FakeTrackSelection(trackGroup, /* selectedIndex= */ 0);
    trackSelection1.enable();
    FakeTrackSelection trackSelection2 = new FakeTrackSelection(trackGroup, /* selectedIndex= */ 1);
    trackSelection2.enable();
    chunkSource.setTrackSelection(trackSelection1);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // Select first chunk, starting at exactly 16 seconds.
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(8_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 16_000_000,
        /* largestReadPositionUs= */ 8_000_000,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    HlsMediaChunk firstChunk = (HlsMediaChunk) output.chunk;
    loadMediaChunk(firstChunk, chunkSource);
    // Update track selection with a load position (18 seconds) beyond the start of the chunk (16
    // seconds), so that the data will overlap. Also simulate reading into this range already.
    chunkSource.setTrackSelection(trackSelection2);
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(8_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 18_000_000,
        /* largestReadPositionUs= */ 16_000_001,
        /* queue= */ ImmutableList.of(firstChunk),
        /* allowEndOfStream= */ true,
        output);
    HlsMediaChunk secondChunk = (HlsMediaChunk) output.chunk;

    assertThat(firstChunk.playlistUrl).isEqualTo(PLAYLIST_URI);
    assertThat(firstChunk.shouldSpliceIn()).isFalse();
    assertThat(firstChunk.startTimeUs).isEqualTo(16_000_000);
    assertThat(secondChunk.playlistUrl).isEqualTo(PLAYLIST_URI);
    assertThat(secondChunk.shouldSpliceIn()).isFalse();
    assertThat(secondChunk.startTimeUs).isEqualTo(20_000_000);
  }

  @Test
  public void getPublishedPartDurationUs_returnsExpectedPartDuration() throws Exception {
    HlsChunkSource chunkSource = createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_AND_PARTS);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();
    // The live playlist contains 6 finished segments, each 4 seconds long. With a playlist start
    // offset of 8 seconds, the total media time of these segments is 8 + 6*4 = 32 seconds. A
    // request to fetch the chunk at 34 seconds should return the first trailing part.
    chunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(34_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 34_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    // Verify setup.
    assertThat(output.chunk).isInstanceOf(HlsMediaChunk.class);
    assertThat(((HlsMediaChunk) output.chunk).partIndex).isNotEqualTo(C.INDEX_UNSET);

    long durationUs = chunkSource.getPublishedPartDurationUs((HlsMediaChunk) output.chunk);

    assertThat(durationUs).isEqualTo(1_000_000L);
  }

  @Test
  public void maybeThrowError_withoutPlaylistError_doesNotThrow() throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // The live playlist contains 6 segments, each 4 seconds long. With a playlist start offset of 8
    // seconds, the total media time is 8 + 6*4 = 32 seconds. A request to fetch the chunk at 27
    // seconds should retrieve a valid chunk.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(27_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 27_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    assertThat(output.chunk).isNotNull(); // Verify setup assumption.

    // Assert no error is thrown.
    testChunkSource.maybeThrowError();
  }

  @Test
  public void maybeThrowError_withPlaylistErrorAndSuccessfulNextChunk_doesNotThrow()
      throws Exception {
    HlsChunkSource testChunkSource =
        createHlsChunkSource(
            PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY,
            /* playlistLoadException= */ new IOException());
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // The live playlist contains 6 segments, each 4 seconds long. With a playlist start offset of 8
    // seconds, the total media time is 8 + 6*4 = 32 seconds. A request to fetch the chunk at 27
    // seconds should retrieve a valid chunk.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(27_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 27_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    assertThat(output.chunk).isNotNull(); // Verify setup assumption.
    testChunkSource.onPlaylistError(PLAYLIST_URI, /* exclusionDurationMs= */ C.TIME_UNSET);

    // Assert no error is thrown.
    testChunkSource.maybeThrowError();
  }

  @Test
  public void maybeThrowError_withBlockedNextChunkAndNoPlaylistError_doesNotThrow()
      throws Exception {
    HlsChunkSource testChunkSource = createHlsChunkSource(PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY);
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // The live playlist contains 6 segments, each 4 seconds long. With a playlist start offset of 8
    // seconds, the total media time is 8 + 6*4 = 32 seconds. A request to fetch the chunk at 32
    // seconds should be blocked on reloading a new playlist.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(32_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 32_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    assertThat(output.playlistUrl).isNotNull(); // Verify setup assumption.
    assertThat(output.chunk).isNull();

    // Assert no error is thrown.
    testChunkSource.maybeThrowError();
  }

  @Test
  public void maybeThrowError_withPlaylistErrorAndThenBlockedNextChunk_doesThrow()
      throws Exception {
    HlsChunkSource testChunkSource =
        createHlsChunkSource(
            PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY,
            /* playlistLoadException= */ new IOException());
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // Report error before being blocked on reloading new playlist.
    testChunkSource.onPlaylistError(PLAYLIST_URI, /* exclusionDurationMs= */ C.TIME_UNSET);
    // The live playlist contains 6 segments, each 4 seconds long. With a playlist start offset of 8
    // seconds, the total media time is 8 + 6*4 = 32 seconds. A request to fetch the chunk at 32
    // seconds should be blocked on reloading a new playlist.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(32_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 32_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    assertThat(output.playlistUrl).isNotNull(); // Verify setup assumption.
    assertThat(output.chunk).isNull();

    // Assert error is thrown.
    assertThrows(IOException.class, testChunkSource::maybeThrowError);
  }

  @Test
  public void maybeThrowError_withBlockedNextChunkAndThenPlaylistError_doesThrow()
      throws Exception {
    HlsChunkSource testChunkSource =
        createHlsChunkSource(
            PLAYLIST_LIVE_LOW_LATENCY_SEGMENTS_ONLY,
            /* playlistLoadException= */ new IOException());
    HlsChunkSource.HlsChunkHolder output = new HlsChunkSource.HlsChunkHolder();

    // The live playlist contains 6 segments, each 4 seconds long. With a playlist start offset of 8
    // seconds, the total media time is 8 + 6*4 = 32 seconds. A request to fetch the chunk at 32
    // seconds should be blocked on reloading a new playlist.
    testChunkSource.getNextChunk(
        new LoadingInfo.Builder().setPlaybackPositionUs(32_000_000).setPlaybackSpeed(1.0f).build(),
        /* loadPositionUs= */ 32_000_000,
        /* largestReadPositionUs= */ 0,
        /* queue= */ ImmutableList.of(),
        /* allowEndOfStream= */ true,
        output);
    assertThat(output.playlistUrl).isNotNull(); // Verify setup assumption.
    assertThat(output.chunk).isNull();
    // Report error after being blocked on reloading new playlist.
    testChunkSource.onPlaylistError(PLAYLIST_URI, /* exclusionDurationMs= */ C.TIME_UNSET);

    // Assert error is thrown.
    assertThrows(IOException.class, testChunkSource::maybeThrowError);
  }

  private static HlsChunkSource createHlsChunkSource(String playlistPath) throws IOException {
    return createHlsChunkSource(
        ImmutableMap.of(PLAYLIST_URI, playlistPath),
        /* cmcdConfiguration= */ null,
        /* playlistLoadException= */ null);
  }

  private static HlsChunkSource createHlsChunkSource(
      String playlistPath, @Nullable CmcdConfiguration cmcdConfiguration) throws IOException {
    return createHlsChunkSource(
        ImmutableMap.of(PLAYLIST_URI, playlistPath),
        cmcdConfiguration,
        /* playlistLoadException= */ null);
  }

  private static HlsChunkSource createHlsChunkSource(
      String playlistPath, @Nullable IOException playlistLoadException) throws IOException {
    return createHlsChunkSource(
        ImmutableMap.of(PLAYLIST_URI, playlistPath),
        /* cmcdConfiguration= */ null,
        playlistLoadException);
  }

  private static HlsChunkSource createHlsChunkSource(Map<Uri, String> playlistUrisToPaths)
      throws IOException {
    return createHlsChunkSource(
        playlistUrisToPaths, /* cmcdConfiguration= */ null, /* playlistLoadException= */ null);
  }

  private static HlsChunkSource createHlsChunkSource(
      Map<Uri, String> playlistUrisToPaths,
      @Nullable CmcdConfiguration cmcdConfiguration,
      @Nullable IOException playlistLoadException)
      throws IOException {
    HlsPlaylistTracker mockPlaylistTracker = mock(HlsPlaylistTracker.class);
    long playlistStartTimeUs = 0;
    Format[] playlistFormats = new Format[playlistUrisToPaths.size() + 1];
    Uri[] playlistUris = new Uri[playlistUrisToPaths.size() + 1];
    playlistFormats[0] = IFRAME_FORMAT;
    playlistUris[0] = IFRAME_URI;
    int playlistArrayIndex = 1;
    for (Map.Entry<Uri, String> playlistUriAndPath : playlistUrisToPaths.entrySet()) {
      Uri playlistUri = playlistUriAndPath.getKey();
      String playlistPath = playlistUriAndPath.getValue();
      InputStream inputStream =
          TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), playlistPath);
      HlsMediaPlaylist playlist =
          (HlsMediaPlaylist) new HlsPlaylistParser().parse(playlistUri, inputStream);
      when(mockPlaylistTracker.getPlaylistSnapshot(eq(playlistUri), anyBoolean()))
          .thenReturn(playlist);
      when(mockPlaylistTracker.isSnapshotValid(eq(playlistUri))).thenReturn(true);
      if (playlistLoadException != null) {
        doThrow(playlistLoadException)
            .when(mockPlaylistTracker)
            .maybeThrowPlaylistRefreshError(playlistUri);
      }
      playlistStartTimeUs = playlist.startTimeUs;
      playlistFormats[playlistArrayIndex] =
          ExoPlayerTestRunner.VIDEO_FORMAT.buildUpon().setId(playlistArrayIndex).build();
      playlistUris[playlistArrayIndex] = playlistUri;
      playlistArrayIndex++;
    }
    // Mock that segments totalling PLAYLIST_START_PERIOD_OFFSET_US in duration have been removed
    // from the start of the playlist.
    when(mockPlaylistTracker.getInitialStartTimeUs())
        .thenReturn(playlistStartTimeUs - PLAYLIST_START_PERIOD_OFFSET_US);
    HlsChunkSource chunkSource =
        new HlsChunkSource(
            createPlaceholderExtractorFactory(),
            mockPlaylistTracker,
            playlistUris,
            playlistFormats,
            new DefaultHlsDataSourceFactory(
                new FakeDataSource.Factory()
                    .setFakeDataSet(
                        new FakeDataSet().newDefaultData().appendReadData(1).endData())),
            /* mediaTransferListener= */ null,
            new TimestampAdjusterProvider(),
            /* timestampAdjusterInitializationTimeoutMs= */ 0,
            /* muxedCaptionFormats= */ null,
            PlayerId.UNSET,
            cmcdConfiguration);
    chunkSource.setIsPrimaryTimestampSource(true);
    return chunkSource;
  }

  private static HlsExtractorFactory createPlaceholderExtractorFactory() {
    return new HlsExtractorFactory() {
      @Override
      public HlsMediaChunkExtractor createExtractor(
          Uri uri,
          Format format,
          @Nullable List<Format> muxedCaptionFormats,
          TimestampAdjuster timestampAdjuster,
          Map<String, List<String>> responseHeaders,
          ExtractorInput sniffingExtractorInput,
          PlayerId playerId)
          throws IOException {
        return new HlsMediaChunkExtractor() {
          @Override
          public void init(ExtractorOutput extractorOutput) {}

          @Override
          public boolean read(ExtractorInput extractorInput) {
            return false;
          }

          @Override
          public boolean isPackedAudioExtractor() {
            return false;
          }

          @Override
          public boolean isReusable() {
            return true;
          }

          @Override
          public HlsMediaChunkExtractor recreate() {
            return this;
          }

          @Override
          public void onTruncatedSegmentParsed() {}
        };
      }
    };
  }

  private static void loadMediaChunk(HlsMediaChunk mediaChunk, HlsChunkSource chunkSource)
      throws IOException {
    HlsSampleStreamWrapper sampleStreamWrapper =
        new HlsSampleStreamWrapper(
            /* uid= */ "",
            C.TRACK_TYPE_VIDEO,
            mock(HlsSampleStreamWrapper.Callback.class),
            chunkSource,
            /* overridingDrmInitData= */ ImmutableMap.of(),
            mock(Allocator.class),
            /* positionUs= */ 0,
            /* muxedAudioFormat= */ null,
            mock(DrmSessionManager.class),
            mock(DrmSessionEventListener.EventDispatcher.class),
            mock(LoadErrorHandlingPolicy.class),
            mock(MediaSourceEventListener.EventDispatcher.class),
            /* metadataType= */ HlsMediaSource.METADATA_TYPE_ID3,
            /* downloadExecutor= */ null);
    mediaChunk.init(sampleStreamWrapper, ImmutableList.of());
    mediaChunk.load();
  }

  private static long playlistTimeToPeriodTimeUs(long playlistTimeUs) {
    return playlistTimeUs + PLAYLIST_START_PERIOD_OFFSET_US;
  }

  private static long periodTimeToPlaylistTimeUs(long periodTimeUs) {
    return periodTimeUs - PLAYLIST_START_PERIOD_OFFSET_US;
  }
}
