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
package androidx.media3.effect;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link HardwareBufferFrame}. */
@RunWith(AndroidJUnit4.class)
public class HardwareBufferFrameTest {

  @Test
  public void releaseWithoutRetain_releasesFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> isReleased.set(true))
            .setInternalFrame(new Object())
            .build();

    frame.release(/* releaseFence= */ null);

    assertThat(isReleased.get()).isTrue();
  }

  @Test
  public void releaseAfterRetain_doesNotReleaseFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> isReleased.set(true))
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame unused = frame.retain();
    frame.release(/* releaseFence= */ null);

    assertThat(isReleased.get()).isFalse();
  }

  @Test
  public void matchingReleaseAndRetainCalls_releasesFrame() {
    AtomicBoolean isReleased = new AtomicBoolean(false);
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> isReleased.set(true))
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame retainedFrame = frame.retain();

    frame.release(/* releaseFence= */ null);
    assertThat(isReleased.get()).isFalse();

    retainedFrame.release(/* releaseFence= */ null);
    assertThat(isReleased.get()).isTrue();
  }

  @Test
  public void release_idempotentOnSameHandle() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releaseCount.incrementAndGet())
            .setInternalFrame(new Object())
            .build();

    frame.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);

    // Second release on the same handle should have no effect.
    frame.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void retainAfterRelease_throwsIllegalStateException() {
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null, directExecutor(), (releaseFence) -> {})
            .setInternalFrame(new Object())
            .build();
    frame.release(/* releaseFence= */ null);

    assertThrows(IllegalStateException.class, frame::retain);
  }

  @Test
  public void release_multipleTimesOnSingleHandle_doesNotReleaseUnderlyingFrameIfRetained() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releaseCount.incrementAndGet())
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame retainedFrame = frame.retain();

    // Releasing the original handle multiple times.
    frame.release(/* releaseFence= */ null);
    frame.release(/* releaseFence= */ null);

    // Should not be released because retainedFrame is still active.
    assertThat(releaseCount.get()).isEqualTo(0);

    // Releasing the retained handle should trigger the final release.
    retainedFrame.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void retain_multipleLevels_releasesOnlyAfterAllReleased() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    HardwareBufferFrame frame =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releaseCount.incrementAndGet())
            .setInternalFrame(new Object())
            .build();

    // Create a chain of retained handles.
    HardwareBufferFrame retained1 = frame.retain();
    HardwareBufferFrame retained2 = retained1.retain();

    frame.release(/* releaseFence= */ null);
    retained1.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(0);

    retained2.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void buildUpon_builtFrameSharesLifetimeWithOriginalFrame() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    HardwareBufferFrame frame1 =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releaseCount.incrementAndGet())
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame frame2 = frame1.buildUpon().build();

    frame1.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);

    // Releasing frame2 should be a no-op as frame is already released.
    frame2.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void buildUpon_originalFrameSharesLifetimeWithBuiltFrame() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    HardwareBufferFrame frame1 =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releaseCount.incrementAndGet())
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame frame2 = frame1.buildUpon().build();

    frame2.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);

    // Releasing frame1 should be a no-op as frame is already released.
    frame1.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
  }

  @Test
  public void buildUpon_multipleBuilds_allFramesShareLifetime() {
    AtomicInteger releaseCount = new AtomicInteger(0);
    HardwareBufferFrame frame1 =
        new HardwareBufferFrame.Builder(
                /* hardwareBuffer= */ null,
                directExecutor(),
                (releaseFence) -> releaseCount.incrementAndGet())
            .setInternalFrame(new Object())
            .build();

    HardwareBufferFrame.Builder builder = frame1.buildUpon();
    HardwareBufferFrame frame2 = builder.build();
    HardwareBufferFrame frame3 = builder.build();

    frame2.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);

    frame1.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
    frame3.release(/* releaseFence= */ null);
    assertThat(releaseCount.get()).isEqualTo(1);
  }
}
