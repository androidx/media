/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.common.video;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.HardwareBuffer;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** Unit tests for {@link DefaultHardwareBufferFrame}. */
@RunWith(AndroidJUnit4.class)
@Config(minSdk = 26)
public final class DefaultHardwareBufferFrameTest {

  private static final long TEST_TIMEOUT_MS = 5_000L;

  @Test
  public void release_onDirectExecutor_immediatelyExecutesCallback() {
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();

      frame.release(/* releaseFence= */ null);

      verify(mockCallback).release(null);
    }
  }

  @Test
  public void release_withReleaseFence_awaitsAndClosesReleaseFence() throws Exception {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    doAnswer(
            invocation -> {
              callbackLatch.countDown();
              return null;
            })
        .when(mockCallback)
        .release(any());
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    when(mockReleaseFence.awaitMs(anyLong())).thenReturn(true);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();

      frame.release(mockReleaseFence);

      assertWithMessage("Release callback timed out")
          .that(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS))
          .isTrue();
      verify(mockReleaseFence).awaitMs(500);
      verify(mockReleaseFence).close();
      verify(mockCallback).release(null);
    }
  }

  @Test
  public void release_alreadyReleasedFrame_closesReleaseFenceSilently() {
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), (fence) -> {})
              .setContentTimeUs(1_000_000L)
              .build();
      frame.release(/* releaseFence= */ null);

      frame.release(mockReleaseFence);

      verify(mockReleaseFence).close();
    }
  }

  @Test
  public void buildUpon_buildWithoutModifications_returnsNewHandleWithSameProperties() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(
                  hardwareBuffer, directExecutor(), (SyncFenceWrapper fence) -> {})
              .setContentTimeUs(1_000_000L)
              .build();

      DefaultHardwareBufferFrame frame2 = frame1.buildUpon().build();

      assertThat(frame2).isNotSameInstanceAs(frame1);
      assertThat(frame2.getContentTimeUs()).isEqualTo(1_000_000L);
      assertThat(frame2.getHardwareBuffer()).isSameInstanceAs(hardwareBuffer);
    }
  }

  @Test
  public void buildUpon_withModifications_returnsNewHandleWithUpdatedProperties() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(
                  hardwareBuffer, directExecutor(), (SyncFenceWrapper fence) -> {})
              .setContentTimeUs(1_000_000L)
              .build();

      DefaultHardwareBufferFrame frame2 =
          frame1.buildUpon().setMetadata(ImmutableMap.of("key", "value")).build();

      assertThat(frame2).isNotSameInstanceAs(frame1);
      assertThat(frame2.getMetadata()).containsEntry("key", "value");
    }
  }

  @Test
  public void buildUpon_incrementReferenceCountAndReleaseFirstHandle_doesNotReleaseCallback() {
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();
      DefaultHardwareBufferFrame unusedFrame2 =
          frame1.buildUpon().shouldIncrementReferenceCount().build();

      frame1.release(/* releaseFence= */ null);

      verify(mockCallback, never()).release(any());
    }
  }

  @Test
  public void buildUpon_incrementReferenceCountAndReleaseAllHandles_releasesCallback() {
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();
      DefaultHardwareBufferFrame frame2 =
          frame1.buildUpon().shouldIncrementReferenceCount().build();
      frame1.release(/* releaseFence= */ null);

      frame2.release(/* releaseFence= */ null);

      verify(mockCallback).release(null);
    }
  }

  @Test
  public void buildUpon_andReleaseMultipleReferences_awaitsAndClosesAllFences() throws Exception {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    doAnswer(
            invocation -> {
              callbackLatch.countDown();
              return null;
            })
        .when(mockCallback)
        .release(any());
    SyncFenceWrapper mockReleaseFence1 = mock(SyncFenceWrapper.class);
    SyncFenceWrapper mockReleaseFence2 = mock(SyncFenceWrapper.class);
    when(mockReleaseFence1.awaitMs(anyLong())).thenReturn(true);
    when(mockReleaseFence2.awaitMs(anyLong())).thenReturn(true);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();
      DefaultHardwareBufferFrame frame2 =
          frame1.buildUpon().shouldIncrementReferenceCount().build();

      frame1.release(mockReleaseFence1);
      verify(mockCallback, never()).release(any());
      verify(mockReleaseFence1, never()).close();

      frame2.release(mockReleaseFence2);
      assertWithMessage("Release callback timed out")
          .that(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS))
          .isTrue();
      verify(mockReleaseFence1).awaitMs(500);
      verify(mockReleaseFence1).close();
      verify(mockReleaseFence2).awaitMs(500);
      verify(mockReleaseFence2).close();
      verify(mockCallback).release(null);
    }
  }

  @Test
  public void buildUpon_withoutIncrementReferenceCount_doesNotIncrementRefCount() {
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();
      DefaultHardwareBufferFrame unusedFrame2 =
          frame1.buildUpon().setMetadata(ImmutableMap.of("key", "value")).build();

      frame1.release(/* releaseFence= */ null);

      verify(mockCallback).release(null);
    }
  }

  @Test
  public void buildUpon_withoutIncrementReferenceCount_awaitsAndClosesFenceOnFirstRelease()
      throws Exception {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    doAnswer(
            invocation -> {
              callbackLatch.countDown();
              return null;
            })
        .when(mockCallback)
        .release(any());
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    when(mockReleaseFence.awaitMs(anyLong())).thenReturn(true);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame1 =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();
      DefaultHardwareBufferFrame unusedFrame2 =
          frame1.buildUpon().setMetadata(ImmutableMap.of("key", "value")).build();

      frame1.release(mockReleaseFence);

      assertWithMessage("Release callback timed out")
          .that(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS))
          .isTrue();
      verify(mockReleaseFence).awaitMs(500);
      verify(mockReleaseFence).close();
      verify(mockCallback).release(null);
    }
  }

  @Test
  public void buildUpon_afterRelease_throwsIllegalStateException() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), (fence) -> {})
              .setContentTimeUs(1_000_000L)
              .build();
      frame.release(/* releaseFence= */ null);

      assertThrows(IllegalStateException.class, frame::buildUpon);
    }
  }

  @Test
  public void incrementReferenceCount_withoutReferenceCounting_throwsIllegalStateException() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame.Builder builder =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer);

      assertThrows(IllegalStateException.class, builder::shouldIncrementReferenceCount);
    }
  }

  @Test
  public void
      buildUpon_incrementReferenceCountWithoutReferenceCounting_throwsIllegalStateException() {
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer).build();

      DefaultHardwareBufferFrame.Builder builder = frame.buildUpon();
      assertThrows(IllegalStateException.class, () -> builder.shouldIncrementReferenceCount());
    }
  }

  @Test
  public void release_multipleTimes_secondReleaseNoOp() {
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, directExecutor(), mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();
      frame.release(/* releaseFence= */ null);

      frame.release(/* releaseFence= */ null);

      verify(mockCallback).release(null);
    }
  }

  @Test
  public void release_onBackgroundExecutor_executesCallbackOnExecutor() throws Exception {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Thread executorThread = executor.submit(Thread::currentThread).get();
    CountDownLatch callbackLatch = new CountDownLatch(1);
    AtomicReference<Thread> callbackThread = new AtomicReference<>();

    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(
                  hardwareBuffer,
                  executor,
                  (releaseFence) -> {
                    callbackThread.set(Thread.currentThread());
                    callbackLatch.countDown();
                  })
              .build();

      frame.release(/* releaseFence= */ null);

      assertWithMessage("Release callback timed out")
          .that(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS))
          .isTrue();
      assertThat(callbackThread.get()).isSameInstanceAs(executorThread);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  public void release_whenExecutorThrows_throwsException() {
    Executor failingExecutor =
        r -> {
          throw new RejectedExecutionException("Executor shutdown");
        };
    ReleaseCallback mockCallback = mock(ReleaseCallback.class);
    try (HardwareBuffer hardwareBuffer = createHardwareBuffer()) {
      DefaultHardwareBufferFrame frame =
          new DefaultHardwareBufferFrame.Builder(hardwareBuffer, failingExecutor, mockCallback)
              .setContentTimeUs(1_000_000L)
              .build();

      assertThrows(
          RejectedExecutionException.class,
          () -> frame.release(/* releaseFence= */ (SyncFenceWrapper) null));
      verify(mockCallback, never()).release(any());
    }
  }

  private static HardwareBuffer createHardwareBuffer() {
    return HardwareBuffer.create(
        /* width= */ 64,
        /* height= */ 64,
        HardwareBuffer.RGBA_8888,
        /* layers= */ 1,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE);
  }
}
