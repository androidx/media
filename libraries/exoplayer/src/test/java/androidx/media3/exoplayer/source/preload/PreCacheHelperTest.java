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
package androidx.media3.exoplayer.source.preload;

import static androidx.media3.common.util.Assertions.checkNotNull;
import static androidx.media3.test.utils.CacheAsserts.assertCacheEmpty;
import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.robolectric.Shadows.shadowOf;

import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.test.utils.FakeMediaSource;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.AtomicDouble;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link PreCacheHelper}. */
@RunWith(AndroidJUnit4.class)
public class PreCacheHelperTest {

  private File testDir;
  private Cache downloadCache;
  private HandlerThread preCacheThread;
  private Looper preCacheLooper;
  private TestPreCacheHelperListener preCacheHelperListener;
  private PreCacheHelper.Factory preCacheHelperFactory;

  @Before
  public void setUp() throws Exception {
    testDir =
        Util.createTempFile(ApplicationProvider.getApplicationContext(), "PreCacheHelperTest");
    assertThat(testDir.delete()).isTrue();
    assertThat(testDir.mkdirs()).isTrue();
    preCacheHelperListener = new TestPreCacheHelperListener();
    downloadCache =
        new SimpleCache(testDir, new NoOpCacheEvictor(), TestUtil.getInMemoryDatabaseProvider());
    preCacheThread = new HandlerThread("preCache");
    preCacheThread.start();
    preCacheLooper = preCacheThread.getLooper();

    preCacheHelperFactory =
        new PreCacheHelper.Factory(
                ApplicationProvider.getApplicationContext(), downloadCache, preCacheLooper)
            .setListener(preCacheHelperListener);
  }

  @After
  public void tearDown() {
    downloadCache.release();
    Util.recursiveDelete(testDir);
    preCacheThread.quit();
  }

  @Test
  public void preCache_succeeds() throws Exception {
    PreCacheHelper preCacheHelper =
        preCacheHelperFactory.create(
            MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4"));

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheHelperListener.percentageDownloaded.get() == 100.0);

    assertThat(preCacheHelperListener.updatedMediaItem.get()).isNotNull();
    assertThat(preCacheHelperListener.prepareError.get()).isNull();
    assertThat(preCacheHelperListener.downloadError.get()).isNull();

    preCacheHelper.stop(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void preCache_preparationFails() throws Exception {
    IOException fakeException =
        ParserException.createForMalformedContainer("Fake preparation error", new IOException());

    FakeMediaSource fakeMediaSource =
        new FakeMediaSource() {
          @Override
          public void maybeThrowSourceInfoRefreshError() throws IOException {
            throw fakeException;
          }
        };
    fakeMediaSource.setAllowPreparation(false);
    PreCacheHelper preCacheHelper = preCacheHelperFactory.create(fakeMediaSource);

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheHelperListener.prepareError.get() != null);
    preCacheHelper.stop(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();

    assertThat(preCacheHelperListener.updatedMediaItem.get()).isNull();
    assertThat(preCacheHelperListener.percentageDownloaded.get()).isEqualTo(0.0);
    assertThat(preCacheHelperListener.prepareError.get()).isSameInstanceAs(fakeException);
    assertThat(preCacheHelperListener.downloadError.get()).isNull();
  }

  @Test
  public void preCache_downloadFails() throws Exception {
    IOException fakeException = new IOException("Fake download error");
    ResolvingDataSource.Resolver resolver =
        dataSpec -> {
          if (!Thread.currentThread()
              .getName()
              .equals(PreCacheHelper.PRECACHE_DOWNLOADER_THREAD_NAME)) {
            return dataSpec;
          } else {
            throw fakeException;
          }
        };
    MediaItem mediaItem = MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4");
    PreCacheHelper preCacheHelper =
        new PreCacheHelper.Factory(
                ApplicationProvider.getApplicationContext(),
                downloadCache,
                new ResolvingDataSource.Factory(
                    new DefaultDataSource.Factory(ApplicationProvider.getApplicationContext()),
                    resolver),
                preCacheLooper)
            .setListener(preCacheHelperListener)
            .create(mediaItem);

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheHelperListener.downloadError.get() != null);
    preCacheHelper.stop(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();

    assertThat(preCacheHelperListener.updatedMediaItem.get()).isNotNull();
    assertThat(preCacheHelperListener.prepareError.get()).isNull();
    assertThat(preCacheHelperListener.downloadError.get()).isSameInstanceAs(fakeException);
  }

  @Test
  public void stopOnly_downloaderCancelledButContentRemains() throws Exception {
    AtomicReference<PreCacheHelper> preCacheHelper = new AtomicReference<>();
    AtomicLong bytesDownloadedOnStop = new AtomicLong();
    TestPreCacheHelperListener preCacheHelperListener =
        new TestPreCacheHelperListener() {
          @Override
          public void onPreCacheProgress(
              MediaItem originalMediaItem,
              long contentLength,
              long bytesDownloaded,
              float percentageDownloaded) {
            this.bytesDownloaded.set(bytesDownloaded);
            if (bytesDownloaded != 0 && bytesDownloaded != contentLength) {
              bytesDownloadedOnStop.set(bytesDownloaded);
              // Stop the caching when it is in the progress, without removing the cached content.
              checkNotNull(preCacheHelper.get()).stop(/* removeCachedContent= */ false);
            }
            this.percentageDownloaded.set(percentageDownloaded);
          }
        };
    preCacheHelperFactory.setListener(preCacheHelperListener);
    preCacheHelper.set(
        preCacheHelperFactory.create(
            MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4")));

    checkNotNull(preCacheHelper.get())
        .preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheHelperListener.bytesDownloaded.get() != 0);

    assertThat(preCacheHelperListener.updatedMediaItem.get()).isNotNull();
    assertThat(preCacheHelperListener.prepareError.get()).isNull();
    assertThat(preCacheHelperListener.bytesDownloaded.get()).isEqualTo(bytesDownloadedOnStop.get());
    assertThat(downloadCache.getCacheSpace())
        .isAtLeast(preCacheHelperListener.bytesDownloaded.get());

    preCacheHelper.get().stop(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }

  @Test
  public void stopAndRemoveCachedContent_downloaderCancelledAndContentRemoved() throws Exception {
    AtomicReference<PreCacheHelper> preCacheHelper = new AtomicReference<>();
    AtomicLong bytesDownloadedOnStop = new AtomicLong();
    TestPreCacheHelperListener preCacheHelperListener =
        new TestPreCacheHelperListener() {
          @Override
          public void onPreCacheProgress(
              MediaItem originalMediaItem,
              long contentLength,
              long bytesDownloaded,
              float percentageDownloaded) {
            this.bytesDownloaded.set(bytesDownloaded);
            if (bytesDownloaded != 0 && bytesDownloaded != contentLength) {
              bytesDownloadedOnStop.set(bytesDownloaded);
              // Stop the caching when it is in the progress, with removing the cached content.
              checkNotNull(preCacheHelper.get()).stop(/* removeCachedContent= */ true);
              shadowOf(preCacheLooper).idle();
            }
            this.percentageDownloaded.set(percentageDownloaded);
          }
        };
    preCacheHelperFactory.setListener(preCacheHelperListener);
    preCacheHelper.set(
        preCacheHelperFactory.create(
            MediaItem.fromUri("asset:///media/mp4/long_1080p_lowbitrate.mp4")));

    checkNotNull(preCacheHelper.get())
        .preCache(/* startPositionMs= */ 0, /* durationMs= */ C.TIME_UNSET);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(() -> preCacheHelperListener.bytesDownloaded.get() != 0);

    assertThat(preCacheHelperListener.updatedMediaItem.get()).isNotNull();
    assertThat(preCacheHelperListener.prepareError.get()).isNull();
    assertThat(preCacheHelperListener.bytesDownloaded.get()).isEqualTo(bytesDownloadedOnStop.get());
    assertCacheEmpty(downloadCache);
  }

  private static class TestPreCacheHelperListener implements PreCacheHelper.Listener {

    public final AtomicReference<MediaItem> updatedMediaItem;
    public final AtomicLong bytesDownloaded;
    public final AtomicDouble percentageDownloaded;
    public final AtomicReference<Exception> prepareError;
    public final AtomicReference<Exception> downloadError;

    private TestPreCacheHelperListener() {
      this.updatedMediaItem = new AtomicReference<>();
      this.bytesDownloaded = new AtomicLong();
      this.percentageDownloaded = new AtomicDouble();
      this.prepareError = new AtomicReference<>();
      this.downloadError = new AtomicReference<>();
    }

    @Override
    public void onPrepared(MediaItem originalMediaItem, MediaItem updatedMediaItem) {
      this.updatedMediaItem.set(updatedMediaItem);
    }

    @Override
    public void onPreCacheProgress(
        MediaItem originalMediaItem,
        long contentLength,
        long bytesDownloaded,
        float percentageDownloaded) {
      this.bytesDownloaded.set(bytesDownloaded);
      this.percentageDownloaded.set(percentageDownloaded);
    }

    @Override
    public void onPrepareError(MediaItem mediaItem, IOException error) {
      this.prepareError.set(error);
    }

    @Override
    public void onDownloadError(MediaItem mediaItem, IOException error) {
      this.downloadError.set(error);
    }
  }
}
