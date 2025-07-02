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
package androidx.media3.exoplayer.dash;

import static androidx.media3.test.utils.robolectric.RobolectricUtil.runMainLooperUntil;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.os.HandlerThread;
import android.os.Looper;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.source.preload.PreCacheHelper;
import androidx.media3.test.utils.TestUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class DashPreCacheHelperTest {

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Cache downloadCache;
  private HandlerThread preCacheThread;
  private Looper preCacheLooper;

  @Before
  public void setUp() throws Exception {
    File testDir = temporaryFolder.newFile("PreCacheHelperTest");
    assertThat(testDir.delete()).isTrue();
    assertThat(testDir.mkdirs()).isTrue();
    downloadCache =
        new SimpleCache(testDir, new NoOpCacheEvictor(), TestUtil.getInMemoryDatabaseProvider());
    preCacheThread = new HandlerThread("preCache");
    preCacheThread.start();
    preCacheLooper = preCacheThread.getLooper();
  }

  @After
  public void tearDown() {
    downloadCache.release();
    preCacheThread.quit();
  }

  @Test
  public void preCache_succeeds() throws Exception {
    PreCacheHelper.Listener preCacheHelperListener = mock(PreCacheHelper.Listener.class);
    AtomicBoolean preCacheCompleted = new AtomicBoolean();
    doAnswer(
            invocation -> {
              preCacheCompleted.set(true);
              return null;
            })
        .when(preCacheHelperListener)
        .onPreCacheProgress(any(), anyLong(), anyLong(), eq(100f));
    PreCacheHelper preCacheHelper =
        new PreCacheHelper.Factory(
                ApplicationProvider.getApplicationContext(), downloadCache, preCacheLooper)
            .setListener(preCacheHelperListener)
            .create(MediaItem.fromUri("asset:///media/dash/multi-track/sample.mpd"));

    preCacheHelper.preCache(/* startPositionMs= */ 0, /* durationMs= */ 2000L);
    shadowOf(preCacheLooper).idle();
    runMainLooperUntil(preCacheCompleted::get);
    shadowOf(Looper.getMainLooper()).idle();

    verify(preCacheHelperListener).onPrepared(any(), any());
    verify(preCacheHelperListener, never()).onPrepareError(any(), any());
    verify(preCacheHelperListener, never()).onDownloadError(any(), any());

    preCacheHelper.release(/* removeCachedContent= */ true);
    shadowOf(preCacheLooper).idle();
  }
}
