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
package androidx.media3.exoplayer.hls.offline;

import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.ENC_MEDIA_PLAYLIST_DATA;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.ENC_MEDIA_PLAYLIST_URI;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_0_DIR;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_0_URI;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_1_DIR;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_1_URI;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_2_DIR;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_2_URI;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_3_DIR;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_3_URI;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MEDIA_PLAYLIST_DATA;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MULTIVARIANT_MEDIA_PLAYLIST_1_INDEX;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MULTIVARIANT_MEDIA_PLAYLIST_2_INDEX;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MULTIVARIANT_PLAYLIST_DATA;
import static androidx.media3.exoplayer.hls.offline.HlsDownloadTestData.MULTIVARIANT_PLAYLIST_URI;
import static androidx.media3.test.utils.CacheAsserts.assertCacheEmpty;
import static androidx.media3.test.utils.CacheAsserts.assertCachedData;
import static com.google.common.truth.Truth.assertThat;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.datasource.PlaceholderDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.Downloader;
import androidx.media3.exoplayer.offline.DownloaderFactory;
import androidx.media3.test.utils.CacheAsserts;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.InMemoryDatabaseRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit tests for {@link HlsDownloader}. */
@RunWith(AndroidJUnit4.class)
public class HlsDownloaderTest {

  @Rule public final InMemoryDatabaseRule cacheRule = InMemoryDatabaseRule.create();

  private ProgressListener progressListener;
  private FakeDataSet fakeDataSet;
  private SimpleCache cache;

  @Before
  public void setUp() throws Exception {
    cache = cacheRule.createSimpleCache();
    progressListener = new ProgressListener();
    fakeDataSet =
        new FakeDataSet()
            .setData(MULTIVARIANT_PLAYLIST_URI, MULTIVARIANT_PLAYLIST_DATA)
            .setData(MEDIA_PLAYLIST_1_URI, MEDIA_PLAYLIST_DATA)
            .setRandomData(MEDIA_PLAYLIST_1_DIR + "fileSequence0.ts", 10)
            .setRandomData(MEDIA_PLAYLIST_1_DIR + "fileSequence1.ts", 11)
            .setRandomData(MEDIA_PLAYLIST_1_DIR + "fileSequence2.ts", 12)
            .setData(MEDIA_PLAYLIST_2_URI, MEDIA_PLAYLIST_DATA)
            .setRandomData(MEDIA_PLAYLIST_2_DIR + "fileSequence0.ts", 13)
            .setRandomData(MEDIA_PLAYLIST_2_DIR + "fileSequence1.ts", 14)
            .setRandomData(MEDIA_PLAYLIST_2_DIR + "fileSequence2.ts", 15);
  }

  @Test
  public void createWithDefaultDownloaderFactory_downloadRequestWithoutTimeRange() {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(Mockito.mock(Cache.class))
            .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY);
    DownloaderFactory factory =
        new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);

    Downloader downloader =
        factory.createDownloader(
            new DownloadRequest.Builder(/* id= */ "id", Uri.parse("https://www.test.com/download"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setStreamKeys(
                    Collections.singletonList(
                        new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0)))
                .build());

    assertThat(downloader).isInstanceOf(HlsDownloader.class);
  }

  @Test
  public void createWithDefaultDownloaderFactory_downloadRequestWithTimeRange() {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(Mockito.mock(Cache.class))
            .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY);
    DownloaderFactory factory =
        new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);

    Downloader downloader =
        factory.createDownloader(
            new DownloadRequest.Builder(/* id= */ "id", Uri.parse("https://www.test.com/download"))
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setStreamKeys(
                    Collections.singletonList(
                        new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0)))
                .setTimeRange(/* startPositionUs= */ 10_000, /* durationUs= */ 20_000)
                .build());

    assertThat(downloader).isInstanceOf(HlsDownloader.class);
    HlsDownloader hlsDownloader = (HlsDownloader) downloader;
    assertThat(hlsDownloader.startPositionUs).isEqualTo(10_000);
    assertThat(hlsDownloader.durationUs).isEqualTo(20_000);
  }

  @Test
  public void counterMethods() throws Exception {
    HlsDownloader downloader =
        getHlsDownloader(MULTIVARIANT_PLAYLIST_URI, getKeys(MULTIVARIANT_MEDIA_PLAYLIST_1_INDEX));
    downloader.download(progressListener);

    progressListener.assertBytesDownloaded(MEDIA_PLAYLIST_DATA.length + 10 + 11 + 12);
  }

  @Test
  public void downloadRepresentation() throws Exception {
    HlsDownloader downloader =
        getHlsDownloader(MULTIVARIANT_PLAYLIST_URI, getKeys(MULTIVARIANT_MEDIA_PLAYLIST_1_INDEX));
    downloader.download(progressListener);

    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                MULTIVARIANT_PLAYLIST_URI,
                MEDIA_PLAYLIST_1_URI,
                MEDIA_PLAYLIST_1_DIR + "fileSequence0.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence1.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence2.ts"));
  }

  @Test
  public void downloadMultipleRepresentations() throws Exception {
    HlsDownloader downloader =
        getHlsDownloader(
            MULTIVARIANT_PLAYLIST_URI,
            getKeys(MULTIVARIANT_MEDIA_PLAYLIST_1_INDEX, MULTIVARIANT_MEDIA_PLAYLIST_2_INDEX));
    downloader.download(progressListener);

    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void downloadAllRepresentations() throws Exception {
    // Add data for the rest of the playlists
    fakeDataSet
        .setData(MEDIA_PLAYLIST_0_URI, MEDIA_PLAYLIST_DATA)
        .setRandomData(MEDIA_PLAYLIST_0_DIR + "fileSequence0.ts", 10)
        .setRandomData(MEDIA_PLAYLIST_0_DIR + "fileSequence1.ts", 11)
        .setRandomData(MEDIA_PLAYLIST_0_DIR + "fileSequence2.ts", 12)
        .setData(MEDIA_PLAYLIST_3_URI, MEDIA_PLAYLIST_DATA)
        .setRandomData(MEDIA_PLAYLIST_3_DIR + "fileSequence0.ts", 13)
        .setRandomData(MEDIA_PLAYLIST_3_DIR + "fileSequence1.ts", 14)
        .setRandomData(MEDIA_PLAYLIST_3_DIR + "fileSequence2.ts", 15);

    HlsDownloader downloader = getHlsDownloader(MULTIVARIANT_PLAYLIST_URI, getKeys());
    downloader.download(progressListener);

    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void remove() throws Exception {
    HlsDownloader downloader =
        getHlsDownloader(
            MULTIVARIANT_PLAYLIST_URI,
            getKeys(MULTIVARIANT_MEDIA_PLAYLIST_1_INDEX, MULTIVARIANT_MEDIA_PLAYLIST_2_INDEX));
    downloader.download(progressListener);
    downloader.remove();

    assertCacheEmpty(cache);
  }

  @Test
  public void remove_withContentBeyondDownloadRange_removesWholeContentInCache() throws Exception {
    // Let downloader1 download the first part of content.
    HlsDownloader downloader1 =
        getHlsDownloader(
            MEDIA_PLAYLIST_1_URI,
            getKeys(),
            /* startPositionUs= */ 0,
            /* durationUs= */ 10_000_000);
    downloader1.download(progressListener);
    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                MEDIA_PLAYLIST_1_URI,
                MEDIA_PLAYLIST_1_DIR + "fileSequence0.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence1.ts"));
    // Let downloader2 download the rest of content and then remove, it should remove the whole
    // content, instead of only the part it was asked to download.
    HlsDownloader downloader2 =
        getHlsDownloader(
            MEDIA_PLAYLIST_1_URI,
            getKeys(),
            /* startPositionUs= */ 10_000_000,
            /* durationUs= */ C.TIME_UNSET);
    downloader2.download(progressListener);
    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                MEDIA_PLAYLIST_1_URI,
                MEDIA_PLAYLIST_1_DIR + "fileSequence0.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence1.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence2.ts"));

    downloader2.remove();

    assertCacheEmpty(cache);
  }

  @Test
  public void downloadMediaPlaylist_withoutTimeRange() throws Exception {
    HlsDownloader downloader = getHlsDownloader(MEDIA_PLAYLIST_1_URI, getKeys());
    downloader.download(progressListener);

    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                MEDIA_PLAYLIST_1_URI,
                MEDIA_PLAYLIST_1_DIR + "fileSequence0.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence1.ts",
                MEDIA_PLAYLIST_1_DIR + "fileSequence2.ts"));
  }

  @Test
  public void downloadMediaPlaylist_withTimeRange() throws Exception {
    HlsDownloader downloader =
        getHlsDownloader(
            MEDIA_PLAYLIST_1_URI,
            getKeys(),
            /* startPositionUs= */ 9_976_670,
            /* durationUs= */ 9_976_670);
    downloader.download(progressListener);

    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(MEDIA_PLAYLIST_1_URI, MEDIA_PLAYLIST_1_DIR + "fileSequence1.ts"));
  }

  @Test
  public void downloadEncMediaPlaylist() throws Exception {
    fakeDataSet =
        new FakeDataSet()
            .setData(ENC_MEDIA_PLAYLIST_URI, ENC_MEDIA_PLAYLIST_DATA)
            .setRandomData("enc.key", 8)
            .setRandomData("enc2.key", 9)
            .setRandomData("fileSequence0.ts", 10)
            .setRandomData("fileSequence1.ts", 11)
            .setRandomData("fileSequence2.ts", 12);

    HlsDownloader downloader = getHlsDownloader(ENC_MEDIA_PLAYLIST_URI, getKeys());
    downloader.download(progressListener);
    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void download_withVariableSubstitutionInSegmentUrls_resolvesVariables() throws Exception {
    // Multivariant playlist defines a variable via EXT-X-DEFINE NAME/VALUE
    byte[] multivariantPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-DEFINE:NAME=\"cdnPrefix\",VALUE=\"https://cdn.example.com/\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=232370,CODECS=\"mp4a.40.2, avc1.4d4015\"\n"
                + "media_with_vars.m3u8\n")
            .getBytes(UTF_8);
    // Media playlist uses the variable in segment URLs with IMPORT
    byte[] mediaPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-VERSION:8\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-DEFINE:IMPORT=\"cdnPrefix\"\n"
                + "#EXTINF:9.97667,\n"
                + "{$cdnPrefix}segment0.ts\n"
                + "#EXTINF:9.97667,\n"
                + "{$cdnPrefix}segment1.ts\n"
                + "#EXT-X-ENDLIST")
            .getBytes(UTF_8);
    fakeDataSet =
        new FakeDataSet()
            .setData("master_vars.m3u8", multivariantPlaylistData)
            .setData("media_with_vars.m3u8", mediaPlaylistData)
            .setRandomData("https://cdn.example.com/segment0.ts", 10)
            .setRandomData("https://cdn.example.com/segment1.ts", 11);
    // Create a downloader with CacheDataSource for validating downloaded data.
    HlsDownloader downloader =
        getHlsDownloader(/* mediaPlaylistUri= */ "master_vars.m3u8", getKeys(0));

    downloader.download(progressListener);

    // Verify segments were downloaded with resolved URLs
    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void download_withMultipleVariablesInSegmentUrls_resolvesAllVariables() throws Exception {
    // Multivariant playlist defines multiple variables.
    byte[] multivariantPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-DEFINE:NAME=\"contentPrefix\",VALUE=\"https://media.example.com/\"\n"
                + "#EXT-X-DEFINE:NAME=\"sessionId\",VALUE=\"abc123\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=500000,CODECS=\"avc1.4d401f,mp4a.40.2\"\n"
                + "{$contentPrefix}video/720p.m3u8?sid={$sessionId}\n")
            .getBytes(UTF_8);
    // Media playlist imports and uses both variables.
    byte[] mediaPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:6\n"
                + "#EXT-X-VERSION:8\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-DEFINE:IMPORT=\"contentPrefix\"\n"
                + "#EXT-X-DEFINE:IMPORT=\"sessionId\"\n"
                + "#EXTINF:6.0,\n"
                + "{$contentPrefix}seg/s0.ts?sid={$sessionId}\n"
                + "#EXTINF:6.0,\n"
                + "{$contentPrefix}seg/s1.ts?sid={$sessionId}\n"
                + "#EXT-X-ENDLIST")
            .getBytes(UTF_8);
    fakeDataSet =
        new FakeDataSet()
            .setData("master_multi_vars.m3u8", multivariantPlaylistData)
            .setData("https://media.example.com/video/720p.m3u8?sid=abc123", mediaPlaylistData)
            .setRandomData("https://media.example.com/seg/s0.ts?sid=abc123", 10)
            .setRandomData("https://media.example.com/seg/s1.ts?sid=abc123", 11);
    // Create a downloader with CacheDataSource for validating downloaded data.
    HlsDownloader downloader =
        getHlsDownloader(/* mediaPlaylistUri= */ "master_multi_vars.m3u8", getKeys(0));

    downloader.download(progressListener);

    // Verify variables have been inserted into URIs correctly.
    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void download_withVariableInChildManifestUri_resolvesVariables() throws Exception {
    // Multivariant playlist uses variable in the child manifest URI itself
    byte[] multivariantPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-DEFINE:NAME=\"manifestBase\",VALUE=\"https://manifest.example.com/\"\n"
                + "#EXT-X-DEFINE:NAME=\"segmentBase\",VALUE=\"https://segments.example.com/\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS=\"mp4a.40.2\"\n"
                + "{$manifestBase}audio/playlist.m3u8\n")
            .getBytes(UTF_8);
    // Media playlist uses a different variable for segment URLs
    byte[] mediaPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-VERSION:8\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-DEFINE:IMPORT=\"segmentBase\"\n"
                + "#EXTINF:10.0,\n"
                + "{$segmentBase}audio/chunk_0.ts\n"
                + "#EXTINF:10.0,\n"
                + "{$segmentBase}audio/chunk_1.ts\n"
                + "#EXT-X-ENDLIST")
            .getBytes(UTF_8);
    fakeDataSet =
        new FakeDataSet()
            .setData("master_child_var.m3u8", multivariantPlaylistData)
            .setData("https://manifest.example.com/audio/playlist.m3u8", mediaPlaylistData)
            .setRandomData("https://segments.example.com/audio/chunk_0.ts", 10)
            .setRandomData("https://segments.example.com/audio/chunk_1.ts", 11);
    // Create a downloader with CacheDataSource for validating downloaded data.
    HlsDownloader downloader =
        getHlsDownloader(/* mediaPlaylistUri= */ "master_child_var.m3u8", getKeys(0));

    downloader.download(progressListener);

    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void download_withVariableInEncryptionKeyUri_resolvesVariables() throws Exception {
    // Multivariant playlist defines a variable for the key base URL
    byte[] multivariantPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-DEFINE:NAME=\"keyBase\",VALUE=\"https://keys.example.com/\"\n"
                + "#EXT-X-STREAM-INF:BANDWIDTH=232370,CODECS=\"mp4a.40.2, avc1.4d4015\"\n"
                + "media_with_key_var.m3u8\n")
            .getBytes(UTF_8);
    // Media playlist imports the variable and uses it in the EXT-X-KEY URI
    byte[] mediaPlaylistData =
        ("#EXTM3U\n"
                + "#EXT-X-TARGETDURATION:10\n"
                + "#EXT-X-VERSION:8\n"
                + "#EXT-X-MEDIA-SEQUENCE:0\n"
                + "#EXT-X-PLAYLIST-TYPE:VOD\n"
                + "#EXT-X-DEFINE:IMPORT=\"keyBase\"\n"
                + "#EXT-X-KEY:METHOD=AES-128,URI=\"{$keyBase}enc.key\"\n"
                + "#EXTINF:9.97667,\n"
                + "segment0.ts\n"
                + "#EXT-X-ENDLIST")
            .getBytes(UTF_8);
    fakeDataSet =
        new FakeDataSet()
            .setData("master_key_var.m3u8", multivariantPlaylistData)
            .setData("media_with_key_var.m3u8", mediaPlaylistData)
            .setRandomData("https://keys.example.com/enc.key", 8)
            .setRandomData("segment0.ts", 10);
    // Create a downloader with CacheDataSource for validating downloaded data.
    HlsDownloader downloader =
        getHlsDownloader(/* mediaPlaylistUri= */ "master_key_var.m3u8", getKeys(0));

    downloader.download(progressListener);

    assertCachedData(cache, fakeDataSet);
  }

  private HlsDownloader getHlsDownloader(String mediaPlaylistUri, List<StreamKey> keys) {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new FakeDataSource.Factory().setFakeDataSet(fakeDataSet));
    return new HlsDownloader.Factory(cacheDataSourceFactory)
        .create(new MediaItem.Builder().setUri(mediaPlaylistUri).setStreamKeys(keys).build());
  }

  private HlsDownloader getHlsDownloader(
      String mediaPlaylistUri, List<StreamKey> keys, long startPositionUs, long durationUs) {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new FakeDataSource.Factory().setFakeDataSet(fakeDataSet));
    return new HlsDownloader.Factory(cacheDataSourceFactory)
        .setStartPositionUs(startPositionUs)
        .setDurationUs(durationUs)
        .create(new MediaItem.Builder().setUri(mediaPlaylistUri).setStreamKeys(keys).build());
  }

  private static ArrayList<StreamKey> getKeys(int... variantIndices) {
    ArrayList<StreamKey> streamKeys = new ArrayList<>();
    for (int variantIndex : variantIndices) {
      streamKeys.add(new StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, variantIndex));
    }
    return streamKeys;
  }

  private static final class ProgressListener implements Downloader.ProgressListener {

    private long bytesDownloaded;

    @Override
    public void onProgress(long contentLength, long bytesDownloaded, float percentDownloaded) {
      this.bytesDownloaded = bytesDownloaded;
    }

    public void assertBytesDownloaded(long bytesDownloaded) {
      assertThat(this.bytesDownloaded).isEqualTo(bytesDownloaded);
    }
  }
}
