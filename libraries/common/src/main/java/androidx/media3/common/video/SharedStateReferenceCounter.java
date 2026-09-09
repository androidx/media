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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.concurrent.Executor;

/**
 * Implementation of {@link ReferenceCounter} that tracks the release state of an individual handle
 * and delegates resource release to a {@link FrameSharedState}.
 *
 * <p>Instances of this class are thread-safe.
 */
/* package */ final class SharedStateReferenceCounter implements ReferenceCounter {

  /** A builder for {@link SharedStateReferenceCounter} instances. */
  /* package */ static final class Builder implements ReferenceCounter.Builder {

    @Nullable private final FrameSharedState sharedState;
    private boolean shouldIncrementReferenceCount;

    /**
     * Creates a builder for an unmanaged {@link SharedStateReferenceCounter}.
     *
     * <p>Unmanaged frames are necessary to avoid Garbage Collector (GC) churn. This constructor
     * allows creating unmanaged frames, where {@link #shouldIncrementReferenceCount()} is not
     * needed and calling it fails fast because there is no underlying pool of resources to retain.
     */
    /* package */ Builder() {
      this.sharedState = null;
    }

    /**
     * Creates a builder for a managed {@link SharedStateReferenceCounter}.
     *
     * @param releaseExecutor The {@link Executor} on which {@code releaseCallback} is called.
     * @param releaseCallback The callback invoked when the frame is fully released.
     */
    /* package */ Builder(Executor releaseExecutor, ReleaseCallback releaseCallback) {
      this.sharedState =
          new FrameSharedState(checkNotNull(releaseCallback), checkNotNull(releaseExecutor));
    }

    /**
     * Creates a builder for copying a {@link SharedStateReferenceCounter}.
     *
     * <p>By default, the copied instance shares the underlying {@link FrameSharedState} without
     * incrementing its reference count. Call {@link #shouldIncrementReferenceCount()} before {@link
     * #build()} to increment the reference count for the newly built instance.
     *
     * @param sharedState The {@link FrameSharedState} to share, or {@code null} if unmanaged.
     */
    /* package */ Builder(@Nullable FrameSharedState sharedState) {
      this.sharedState = sharedState;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Configures this builder so that the reference count of the underlying resource is
     * incremented when {@link #build()} is invoked.
     *
     * @throws IllegalStateException If called on an unmanaged builder.
     */
    @CanIgnoreReturnValue
    @Override
    public Builder shouldIncrementReferenceCount() {
      checkState(sharedState != null, "Cannot increment reference count on an unmanaged frame.");
      this.shouldIncrementReferenceCount = true;
      return this;
    }

    /** Builds the {@link SharedStateReferenceCounter}. */
    /* package */ SharedStateReferenceCounter build() {
      if (shouldIncrementReferenceCount) {
        checkNotNull(sharedState).retain();
      }
      return new SharedStateReferenceCounter(this);
    }
  }

  private final Object lock;
  @Nullable private final FrameSharedState sharedState;

  @GuardedBy("lock")
  private boolean isReleased;

  private SharedStateReferenceCounter(Builder builder) {
    this.lock = new Object();
    this.sharedState = builder.sharedState;
    this.isReleased = false;
  }

  /**
   * Checks that the frame has not been released.
   *
   * @param errorMessage The exception message to use if the frame has been released.
   * @throws IllegalStateException If the frame has been released.
   */
  /* package */ void checkNotReleased(String errorMessage) {
    synchronized (lock) {
      checkState(!isReleased, errorMessage);
    }
  }

  /** Returns whether the frame has been released. */
  /* package */ boolean isReleased() {
    synchronized (lock) {
      return isReleased;
    }
  }

  /**
   * Releases this frame handle.
   *
   * <p>If this handle has already been released, {@code releaseFence} is closed silently.
   * Otherwise, the release is delegated to {@link FrameSharedState} (if present), or {@code
   * releaseFence} is closed silently (if unmanaged).
   *
   * @param releaseFence An optional {@link SyncFenceWrapper} that must signal before the underlying
   *     resources can be fully released, or {@code null} if the resources can be released
   *     immediately.
   */
  @Override
  public void release(@Nullable SyncFenceWrapper releaseFence) {
    boolean wasAlreadyReleased;
    synchronized (lock) {
      wasAlreadyReleased = isReleased;
      isReleased = true;
    }
    if (wasAlreadyReleased) {
      closeFenceSilently(releaseFence);
      return;
    }
    if (sharedState != null) {
      sharedState.release(releaseFence);
    } else {
      closeFenceSilently(releaseFence);
    }
  }

  /** Returns the {@link FrameSharedState}, or {@code null} if unmanaged. */
  @Nullable
  /* package */ FrameSharedState getSharedState() {
    return sharedState;
  }

  private static void closeFenceSilently(@Nullable SyncFenceWrapper fence) {
    if (fence == null) {
      return;
    }
    fence.close();
  }
}
