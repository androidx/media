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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class SharedStateReferenceCounterTest {

  private static final long TEST_TIMEOUT_MS = 500L;

  @Test
  public void checkNotReleased_afterRelease_throwsIllegalStateException() {
    SharedStateReferenceCounter counter = new SharedStateReferenceCounter.Builder().build();
    counter.release(/* releaseFence= */ null);

    IllegalStateException exception =
        assertThrows(
            IllegalStateException.class,
            () -> counter.checkNotReleased("Cannot use released frame."));
    assertThat(exception).hasMessageThat().isEqualTo("Cannot use released frame.");
  }

  @Test
  public void checkNotReleased_beforeRelease_doesNotThrow() {
    SharedStateReferenceCounter counter = new SharedStateReferenceCounter.Builder().build();

    counter.checkNotReleased("Cannot use released frame.");
  }

  @Test
  public void shouldIncrementReferenceCount_withoutReleaseCallback_throwsIllegalStateException() {
    SharedStateReferenceCounter.Builder builder = new SharedStateReferenceCounter.Builder();

    assertThrows(IllegalStateException.class, builder::shouldIncrementReferenceCount);
  }

  @Test
  public void release_withoutReleaseCallbackAndNullFence_marksReleased() {
    SharedStateReferenceCounter counter = new SharedStateReferenceCounter.Builder().build();

    counter.release(/* releaseFence= */ null);

    assertThat(counter.isReleased()).isTrue();
  }

  @Test
  public void release_withoutReleaseCallbackAndWithFence_closesFence() {
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    SharedStateReferenceCounter counter = new SharedStateReferenceCounter.Builder().build();

    counter.release(mockReleaseFence);

    assertThat(counter.isReleased()).isTrue();
    verify(mockReleaseFence).close();
  }

  @Test
  public void release_withoutReleaseCallbackWhenAlreadyReleased_closesFence() {
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    SharedStateReferenceCounter counter = new SharedStateReferenceCounter.Builder().build();
    counter.release(/* releaseFence= */ null);

    counter.release(mockReleaseFence);

    assertThat(counter.isReleased()).isTrue();
    verify(mockReleaseFence).close();
  }

  @Test
  public void release_withReleaseCallbackAndNullFence_delegatesToCallback() {
    FakeReleaseCallback callback = new FakeReleaseCallback();
    SharedStateReferenceCounter counter =
        new SharedStateReferenceCounter.Builder(directExecutor(), callback).build();

    counter.release(/* releaseFence= */ null);

    assertThat(counter.isReleased()).isTrue();
    assertThat(callback.getReleaseCount()).isEqualTo(1);
  }

  @Test
  public void release_withReleaseCallbackAndWithFence_awaitsFenceAndDelegatesToCallback()
      throws Exception {
    CountDownLatch callbackLatch = new CountDownLatch(1);
    FakeReleaseCallback fakeCallback = new FakeReleaseCallback();
    ReleaseCallback callback =
        fence -> {
          fakeCallback.release(fence);
          callbackLatch.countDown();
        };
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    when(mockReleaseFence.awaitMs(anyLong())).thenReturn(true);
    SharedStateReferenceCounter counter =
        new SharedStateReferenceCounter.Builder(directExecutor(), callback).build();

    counter.release(mockReleaseFence);

    assertWithMessage("Release callback timed out")
        .that(callbackLatch.await(TEST_TIMEOUT_MS, MILLISECONDS))
        .isTrue();
    assertThat(counter.isReleased()).isTrue();
    verify(mockReleaseFence).awaitMs(anyLong());
    verify(mockReleaseFence).close();
    assertThat(fakeCallback.getReleaseCount()).isEqualTo(1);
  }

  @Test
  public void release_alreadyReleasedWithCallback_closesFenceWithoutInvokingCallback() {
    FakeReleaseCallback callback = new FakeReleaseCallback();
    SyncFenceWrapper mockReleaseFence = mock(SyncFenceWrapper.class);
    SharedStateReferenceCounter counter =
        new SharedStateReferenceCounter.Builder(directExecutor(), callback).build();
    counter.release(/* releaseFence= */ null);

    counter.release(mockReleaseFence);

    assertThat(counter.isReleased()).isTrue();
    verify(mockReleaseFence).close();
    assertThat(callback.getReleaseCount()).isEqualTo(1);
  }

  @Test
  public void release_oneHandleWhileOtherRetained_doesNotInvokeCallback() {
    FakeReleaseCallback callback = new FakeReleaseCallback();
    SharedStateReferenceCounter counter1 =
        new SharedStateReferenceCounter.Builder(directExecutor(), callback).build();
    SharedStateReferenceCounter counter2 =
        new SharedStateReferenceCounter.Builder(counter1.getSharedState())
            .shouldIncrementReferenceCount()
            .build();

    counter1.release(/* releaseFence= */ null);

    assertThat(counter1.isReleased()).isTrue();
    assertThat(counter2.isReleased()).isFalse();
    assertThat(callback.getReleaseCount()).isEqualTo(0);
  }

  @Test
  public void release_oneHandleMultipleTimesWhileOtherRetained_doesNotInvokeCallback() {
    FakeReleaseCallback callback = new FakeReleaseCallback();
    SharedStateReferenceCounter counter1 =
        new SharedStateReferenceCounter.Builder(directExecutor(), callback).build();
    SharedStateReferenceCounter counter2 =
        new SharedStateReferenceCounter.Builder(counter1.getSharedState())
            .shouldIncrementReferenceCount()
            .build();

    counter1.release(/* releaseFence= */ null);
    counter1.release(/* releaseFence= */ null);

    assertThat(counter1.isReleased()).isTrue();
    assertThat(counter2.isReleased()).isFalse();
    assertThat(callback.getReleaseCount()).isEqualTo(0);
  }

  @Test
  public void release_allHandlesSharingCallback_invokesCallback() {
    FakeReleaseCallback callback = new FakeReleaseCallback();
    SharedStateReferenceCounter counter1 =
        new SharedStateReferenceCounter.Builder(directExecutor(), callback).build();
    SharedStateReferenceCounter counter2 =
        new SharedStateReferenceCounter.Builder(counter1.getSharedState())
            .shouldIncrementReferenceCount()
            .build();

    counter1.release(/* releaseFence= */ null);
    counter2.release(/* releaseFence= */ null);

    assertThat(counter1.isReleased()).isTrue();
    assertThat(counter2.isReleased()).isTrue();
    assertThat(callback.getReleaseCount()).isEqualTo(1);
  }

  private static final class FakeReleaseCallback implements ReleaseCallback {
    private final AtomicInteger releaseCount = new AtomicInteger();

    @Override
    public void release(@Nullable SyncFenceWrapper releaseFence) {
      releaseCount.incrementAndGet();
    }

    int getReleaseCount() {
      return releaseCount.get();
    }
  }
}
