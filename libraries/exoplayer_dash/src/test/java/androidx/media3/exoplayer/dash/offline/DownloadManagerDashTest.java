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
package androidx.media3.exoplayer.dash.offline;

import static androidx.media3.exoplayer.dash.offline.DashDownloadTestData.TEST_ID;
import static androidx.media3.exoplayer.dash.offline.DashDownloadTestData.TEST_MPD;
import static androidx.media3.exoplayer.dash.offline.DashDownloadTestData.TEST_MPD_URI;
import static androidx.media3.test.utils.CacheAsserts.assertCacheEmpty;
import static androidx.media3.test.utils.CacheAsserts.assertCachedData;
import static com.google.common.truth.Truth.assertThat;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.content.Context;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.StreamKey;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource.Factory;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.offline.DefaultDownloadIndex;
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory;
import androidx.media3.exoplayer.offline.DownloadManager;
import androidx.media3.exoplayer.offline.DownloadRequest;
import androidx.media3.exoplayer.scheduler.Requirements;
import androidx.media3.test.utils.CacheAsserts.RequestSet;
import androidx.media3.test.utils.DummyMainThread;
import androidx.media3.test.utils.DummyMainThread.TestRunnable;
import androidx.media3.test.utils.FakeDataSet;
import androidx.media3.test.utils.FakeDataSource;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.TestDownloadManagerListener;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

/** Tests {@link DownloadManager}. */
@RunWith(AndroidJUnit4.class)
public class DownloadManagerDashTest {

  private static final int ASSERT_TRUE_TIMEOUT_MS = 5000;

  private SimpleCache cache;
  private File tempFolder;
  private FakeDataSet fakeDataSet;
  private DownloadManager downloadManager;
  private StreamKey fakeStreamKey1;
  private StreamKey fakeStreamKey2;
  private TestDownloadManagerListener downloadManagerListener;
  private DefaultDownloadIndex downloadIndex;
  private DummyMainThread testThread;

  @Before
  public void setUp() throws Exception {
    testThread = new DummyMainThread();
    Context context = ApplicationProvider.getApplicationContext();
    tempFolder = Util.createTempDirectory(context, "ExoPlayerTest");
    File cacheFolder = new File(tempFolder, "cache");
    cacheFolder.mkdir();
    cache =
        new SimpleCache(
            cacheFolder, new NoOpCacheEvictor(), TestUtil.getInMemoryDatabaseProvider());
    MockitoAnnotations.initMocks(this);
    fakeDataSet =
        new FakeDataSet()
            .setData(TEST_MPD_URI, TEST_MPD)
            .setRandomData("audio_init_data", 10)
            .setRandomData("audio_segment_1", 4)
            .setRandomData("audio_segment_2", 5)
            .setRandomData("audio_segment_3", 6)
            .setRandomData("text_segment_1", 1)
            .setRandomData("text_segment_2", 2)
            .setRandomData("text_segment_3", 3);

    fakeStreamKey1 = new StreamKey(0, 0, 0);
    fakeStreamKey2 = new StreamKey(0, 1, 0);
    downloadIndex = new DefaultDownloadIndex(TestUtil.getInMemoryDatabaseProvider());
    createDownloadManager();
  }

  @After
  public void tearDown() {
    runOnMainThread(() -> downloadManager.release());
    Util.recursiveDelete(tempFolder);
    testThread.release();
  }

  @Ignore("Disabled due to flakiness")
  @Test
  public void saveAndLoadActionFile() throws Throwable {
    // Configure fakeDataSet to block until interrupted when TEST_MPD is read.
    fakeDataSet
        .newData(TEST_MPD_URI)
        .appendReadAction(
            () -> {
              try {
                // Wait until interrupted.
                while (true) {
                  Thread.sleep(100000);
                }
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
            })
        .appendReadData(TEST_MPD)
        .endData();

    // Run DM accessing code on UI/main thread as it should be. Also not to block handling of loaded
    // actions.
    runOnMainThread(
        () -> {
          // Setup an Action and immediately release the DM.
          DownloadRequest request = getDownloadRequest(fakeStreamKey1, fakeStreamKey2);
          downloadManager.addDownload(request);
          downloadManager.release();
        });

    assertCacheEmpty(cache);

    // Revert fakeDataSet to normal.
    fakeDataSet.setData(TEST_MPD_URI, TEST_MPD);

    testThread.runOnMainThread(this::createDownloadManager);

    // Block on the test thread.
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCachedData(cache, fakeDataSet);
  }

  @Test
  public void handleDownloadRequest_downloadSuccess() throws Throwable {
    handleDownloadRequest(fakeStreamKey1, fakeStreamKey2);
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCachedData(cache, new RequestSet(fakeDataSet).useBoundedDataSpecFor("audio_init_data"));
  }

  @Test
  public void handleDownloadRequest_withRequest_downloadSuccess() throws Throwable {
    handleDownloadRequest(fakeStreamKey1);
    handleDownloadRequest(fakeStreamKey2);
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCachedData(cache, new RequestSet(fakeDataSet).useBoundedDataSpecFor("audio_init_data"));
  }

  @Test
  public void handleDownloadRequest_withInferringRequest_success() throws Throwable {
    fakeDataSet
        .newData("audio_segment_2")
        .appendReadAction(() -> handleDownloadRequest(fakeStreamKey2))
        .appendReadData(TestUtil.buildTestData(5))
        .endData();
    handleDownloadRequest(fakeStreamKey1);
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCachedData(cache, new RequestSet(fakeDataSet).useBoundedDataSpecFor("audio_init_data"));
  }

  @Test
  public void handleRemoveAction_blockUntilTaskCompleted_noDownloadedData() throws Throwable {
    handleDownloadRequest(fakeStreamKey1);
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    handleRemoveAction();
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCacheEmpty(cache);
  }

  @Test
  public void handleRemoveAction_beforeDownloadFinish_noDownloadedData() throws Throwable {
    handleDownloadRequest(fakeStreamKey1);
    handleRemoveAction();
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCacheEmpty(cache);
  }

  @Test
  public void handleRemoveAction_withInterfering_noDownloadedData() throws Throwable {
    CountDownLatch downloadInProgressLatch = new CountDownLatch(1);
    fakeDataSet
        .newData("audio_segment_2")
        .appendReadAction(downloadInProgressLatch::countDown)
        .appendReadData(TestUtil.buildTestData(5))
        .endData();
    handleDownloadRequest(fakeStreamKey1);
    assertThat(downloadInProgressLatch.await(ASSERT_TRUE_TIMEOUT_MS, MILLISECONDS)).isTrue();

    handleRemoveAction();
    downloadManagerListener.blockUntilIdleAndThrowAnyFailure();
    assertCacheEmpty(cache);
  }

  private void handleDownloadRequest(StreamKey... keys) {
    DownloadRequest request = getDownloadRequest(keys);
    runOnMainThread(() -> downloadManager.addDownload(request));
  }

  private DownloadRequest getDownloadRequest(StreamKey... keys) {
    ArrayList<StreamKey> keysList = new ArrayList<>();
    Collections.addAll(keysList, keys);
    return new DownloadRequest.Builder(TEST_ID, TEST_MPD_URI)
        .setMimeType(MimeTypes.APPLICATION_MPD)
        .setStreamKeys(keysList)
        .build();
  }

  private void handleRemoveAction() {
    runOnMainThread(() -> downloadManager.removeDownload(TEST_ID));
  }

  private void createDownloadManager() {
    runOnMainThread(
        () -> {
          Factory fakeDataSourceFactory = new FakeDataSource.Factory().setFakeDataSet(fakeDataSet);
          DefaultDownloaderFactory downloaderFactory =
              new DefaultDownloaderFactory(
                  new CacheDataSource.Factory()
                      .setCache(cache)
                      .setUpstreamDataSourceFactory(fakeDataSourceFactory),
                  /* executor= */ Runnable::run);
          downloadManager =
              new DownloadManager(
                  ApplicationProvider.getApplicationContext(), downloadIndex, downloaderFactory);
          downloadManager.setRequirements(new Requirements(0));
          downloadManagerListener = new TestDownloadManagerListener(downloadManager);
          downloadManager.resumeDownloads();
        });
  }

  private void runOnMainThread(TestRunnable r) {
    testThread.runTestOnMainThread(r);
  }
}
