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
package androidx.media3.exoplayer.offline;

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.PlaceholderDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/** Unit tests for {@link DefaultDownloaderFactory}. */
@RunWith(AndroidJUnit4.class)
public final class DefaultDownloaderFactoryTest {

  @Test
  public void createProgressiveDownloader_downloadRequestWithByteRange() throws Exception {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(Mockito.mock(Cache.class))
            .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY);
    DownloaderFactory factory =
        new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);

    Downloader downloader =
        factory.createDownloader(
            new DownloadRequest.Builder(/* id= */ "id", Uri.parse("https://www.test.com/download"))
                .setByteRange(/* offset= */ 10, /* length= */ 20)
                .build());

    assertThat(downloader).isInstanceOf(ProgressiveDownloader.class);
    ProgressiveDownloader progressiveDownloader = (ProgressiveDownloader) downloader;
    assertThat(progressiveDownloader.dataSpec.position).isEqualTo(10);
    assertThat(progressiveDownloader.dataSpec.length).isEqualTo(20);
  }

  @Test
  public void createProgressiveDownloader_downloadRequestWithoutByteRange() throws Exception {
    CacheDataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(Mockito.mock(Cache.class))
            .setUpstreamDataSourceFactory(PlaceholderDataSource.FACTORY);
    DownloaderFactory factory =
        new DefaultDownloaderFactory(cacheDataSourceFactory, /* executor= */ Runnable::run);

    Downloader downloader =
        factory.createDownloader(
            new DownloadRequest.Builder(/* id= */ "id", Uri.parse("https://www.test.com/download"))
                .build());

    assertThat(downloader).isInstanceOf(ProgressiveDownloader.class);
    ProgressiveDownloader progressiveDownloader = (ProgressiveDownloader) downloader;
    assertThat(progressiveDownloader.dataSpec.position).isEqualTo(0);
    assertThat(progressiveDownloader.dataSpec.length).isEqualTo(C.LENGTH_UNSET);
  }
}
