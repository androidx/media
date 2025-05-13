/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.exoplayer.smoothstreaming.offline;

import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_FRAGMENT_URI_1;
import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_FRAGMENT_URI_2;
import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_FRAGMENT_URI_3;
import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_MANIFEST_DATA;
import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_MANIFEST_URI;
import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_QUALITY_LEVEL_DIR_1;
import static androidx.media3.exoplayer.smoothstreaming.offline.SsDownloadTestData.TEST_ISM_QUALITY_LEVEL_DIR_2;
import static androidx.media3.test.utils.CacheAsserts.assertCacheEmpty;
import static androidx.media3.test.utils.CacheAsserts.assertCachedData;
import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.PlaceholderDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.offline.Downloader;
import androidx.media3.exoplayer.offline.DownloaderFactory;
import androidx.media3.test.utils.CacheAsserts;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.File;
import java.util.Collections;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit tests for {@link SsDownloader}. */
@RunWith(AndroidJUnit4.class)
public final class SsDownloaderTest {

  private SimpleCache cache;
  private File tempFolder;
  private ProgressListener progressListener;
  private FakeDataSet fakeDataSet;

  @Before
  public void setUp() throws Exception {
    tempFolder =
        Util.createTempDirectory(ApplicationProvider.getApplicationContext(), "ExoPlayerTest");
    cache =
        new SimpleCache(tempFolder, new NoOpCacheEvictor(), TestUtil.getInMemoryDatabaseProvider());
    progressListener = new ProgressListener();
    fakeDataSet =
        new FakeDataSet()
            .setData(TEST_ISM_MANIFEST_URI, TEST_ISM_MANIFEST_DATA)
            .setRandomData(TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_1, 10)
            .setRandomData(TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_2, 8)
            .setRandomData(TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_3, 6)
            .setRandomData(TEST_ISM_QUALITY_LEVEL_DIR_2 + TEST_ISM_FRAGMENT_URI_1, 6)
            .setRandomData(TEST_ISM_QUALITY_LEVEL_DIR_2 + TEST_ISM_FRAGMENT_URI_2, 4)
            .setRandomData(TEST_ISM_QUALITY_LEVEL_DIR_2 + TEST_ISM_FRAGMENT_URI_3, 3);
  }

  @Test
  public void createWithDefaultDownloaderFactory_downloadRequestWithoutTimeRange()
      throws Exception {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(Mockito.mock(Cache.class))
            .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY);
    DownloaderFactory factory =
        new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);

    Downloader downloader =
        factory.createDownloader(
            new DownloadRequest.Builder(/* id= */ "id", Uri.parse("https://www.test.com/download"))
                .setMimeType(MimeTypes.APPLICATION_SS)
                .setStreamKeys(
                    Collections.singletonList(
                        new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0)))
                .build());

    assertThat(downloader).isInstanceOf(SsDownloader.class);
  }

  @Test
  public void createWithDefaultDownloaderFactory_downloadRequestWithTimeRange() throws Exception {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(Mockito.mock(Cache.class))
            .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY);
    DownloaderFactory factory =
        new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);

    Downloader downloader =
        factory.createDownloader(
            new DownloadRequest.Builder(/* id= */ "id", Uri.parse("https://www.test.com/download"))
                .setMimeType(MimeTypes.APPLICATION_SS)
                .setStreamKeys(
                    Collections.singletonList(
                        new StreamKey(/* groupIndex= */ 0, /* streamIndex= */ 0)))
                .setTimeRange(/* startPositionUs= */ 10_000, /* durationUs= */ 20_000)
                .build());

    assertThat(downloader).isInstanceOf(SsDownloader.class);
    SsDownloader ssDownloader = (SsDownloader) downloader;
    assertThat(ssDownloader.startPositionUs).isEqualTo(10_000);
    assertThat(ssDownloader.durationUs).isEqualTo(20_000);
  }

  @Test
  public void downloadAllStreams() throws Exception {
    SsDownloader downloader =
        getSsDownloader(
            TEST_ISM_MANIFEST_URI,
            ImmutableList.of(new StreamKey(0, 0, 0), new StreamKey(0, 0, 1)));

    downloader.download(progressListener);

    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void downloadStreams_filteredByStreamKeys() throws Exception {
    SsDownloader downloader =
        getSsDownloader(TEST_ISM_MANIFEST_URI, ImmutableList.of(new StreamKey(0, 0, 0)));

    downloader.download(progressListener);

    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                TEST_ISM_MANIFEST_URI,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_1,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_2,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_3));
  }

  @Test
  public void downloadSegments_withTimeRange() throws Exception {
    SsDownloader downloader =
        getSsDownloader(
            TEST_ISM_MANIFEST_URI,
            ImmutableList.of(new StreamKey(0, 0, 0)),
            /* startPositionUs= */ 19_680_000,
            /* durationUs= */ 8_980_000);

    downloader.download(progressListener);

    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(TEST_ISM_MANIFEST_URI, TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_2));
  }

  @Test
  public void counter() throws Exception {
    SsDownloader downloader =
        getSsDownloader(TEST_ISM_MANIFEST_URI, ImmutableList.of(new StreamKey(0, 0, 1)));

    downloader.download(progressListener);

    progressListener.assertBytesDownloaded(6 + 4 + 3);
  }

  @Test
  public void remove() throws Exception {
    SsDownloader downloader =
        getSsDownloader(
            TEST_ISM_MANIFEST_URI,
            ImmutableList.of(new StreamKey(0, 0, 0), new StreamKey(0, 0, 1)));

    downloader.download(progressListener);
    downloader.remove();

    assertCacheEmpty(cache);
  }

  @Test
  public void remove_withContentBeyondDownloadRange_removesWholeContentInCache() throws Exception {
    // Let downloader1 download the first part of content.
    SsDownloader downloader1 =
        getSsDownloader(
            TEST_ISM_MANIFEST_URI,
            ImmutableList.of(new StreamKey(0, 0, 0)),
            /* startPositionUs= */ 0,
            /* durationUs= */ 20_000_000);
    downloader1.download(progressListener);
    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                TEST_ISM_MANIFEST_URI,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_1,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_2));
    // Let downloader2 download the rest of content and then remove, it should remove the whole
    // content, instead of only the part it was asked to download.
    SsDownloader downloader2 =
        getSsDownloader(
            TEST_ISM_MANIFEST_URI,
            ImmutableList.of(new StreamKey(0, 0, 0)),
            /* startPositionUs= */ 20_000_000,
            /* durationUs= */ C.TIME_UNSET);
    downloader2.download(progressListener);
    assertCachedData(
        cache,
        new CacheAsserts.RequestSet(fakeDataSet)
            .subset(
                TEST_ISM_MANIFEST_URI,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_1,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_2,
                TEST_ISM_QUALITY_LEVEL_DIR_1 + TEST_ISM_FRAGMENT_URI_3));

    downloader2.remove();

    assertCacheEmpty(cache);
  }

  private SsDownloader getSsDownloader(String manifestUri, List<StreamKey> keys) {
    return getSsDownloader(
        manifestUri, keys, /* startPositionUs= */ 0, /* durationUs= */ C.TIME_UNSET);
  }

  private SsDownloader getSsDownloader(
      String manifestUri, List<StreamKey> keys, long startPositionUs, long durationUs) {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(new FakeDataSource.Factory().setFakeDataSet(fakeDataSet));
    return new SsDownloader.Factory(cacheDataSourceFactory)
        .setStartPositionUs(startPositionUs)
        .setDurationUs(durationUs)
        .create(new MediaItem.Builder().setUri(manifestUri).setStreamKeys(keys).build());
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
