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
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.GlTextureInfo;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for {@link GlTextureFrame}. */
@RunWith(AndroidJUnit4.class)
public class GlTextureFrameTest {

  private static final int TEST_TIMEOUT_MS = 1000;
  private static final GlTextureInfo TEXTURE_INFO =
      new GlTextureInfo(
          /* texId= */ 1, /* fboId= */ 1, /* rboId= */ 1, /* width= */ 1, /* height= */ 1);

  @Test
  public void releaseWithoutRetain_releasesFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();

    frame.release(/* releaseFence= */ null);

    assertThat(isReleased.get()).isTrue();
  }

  @Test
  public void releaseAfterRetain_doesNotReleaseFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();

    frame.retain();
    frame.release(/* releaseFence= */ null);

    assertThat(isReleased.get()).isFalse();
  }

  @Test
  public void matchingReleaseAndRetainCalls_releasesFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();

    frame.retain();
    frame.retain();
    frame.release(/* releaseFence= */ null);
    frame.release(/* releaseFence= */ null);
    frame.release(/* releaseFence= */ null);

    assertThat(isReleased.get()).isTrue();
  }

  @Test
  public void release_afterRelease_doesNotThrow() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(
                TEXTURE_INFO, directExecutor(), (u) -> releaseCount.incrementAndGet())
            .build();

    frame.release(/* releaseFence= */ null);

    assertThat(releaseCount.get()).isEqualTo(1);

    frame.release(/* releaseFence= */ null);

    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void retainAfterRelease_throwsIllegalStateException() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    GlTextureFrame frame =
        new GlTextureFrame.Builder(TEXTURE_INFO, directExecutor(), (u) -> isReleased.set(true))
            .build();
    frame.release(/* releaseFence= */ null);

    assertThrows(IllegalStateException.class, frame::retain);
  }

  @Test
  public void release_concurrently_callsReleaseCallbackOnlyOnce() throws Exception {
    int threadCount = 100;
    AtomicInteger releaseCallbackCount = new AtomicInteger(0);
    ListeningExecutorService executor =
        MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(threadCount));
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(threadCount);
    List<Exception> exceptions = new ArrayList<>();
    List<ListenableFuture<?>> futures = new ArrayList<>();
    GlTextureFrame frame =
        new GlTextureFrame.Builder(
                TEXTURE_INFO, directExecutor(), (u) -> releaseCallbackCount.incrementAndGet())
            .build();
    for (int i = 0; i < threadCount - 1; i++) {
      frame.retain();
    }
    for (int i = 0; i < threadCount; i++) {
      futures.add(
          executor.submit(
              () -> {
                try {
                  startLatch.await();
                  frame.release(/* releaseFence= */ null);
                } catch (Exception e) {
                  synchronized (exceptions) {
                    exceptions.add(e);
                  }
                  if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                  }
                } finally {
                  doneLatch.countDown();
                }
              }));
    }

    startLatch.countDown();

    assertThat(doneLatch.await(TEST_TIMEOUT_MS, MILLISECONDS)).isTrue();
    assertThat(releaseCallbackCount.get()).isEqualTo(1);
    Futures.allAsList(futures).get();
    assertThat(exceptions).isEmpty();

    executor.shutdown();
  }
}
