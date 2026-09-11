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

import static com.google.common.base.Preconditions.checkState;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Util;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * Holds the shared reference count and release state across retained {@link ReferenceCounter}
 * instances.
 *
 * <p>Separating the shared state from individual {@link ReferenceCounter} handles allows each
 * instance to track its own released state locally, so that an individual instance can be safely
 * released multiple times without decrementing the global reference count more than once.
 */
/* package */ final class FrameSharedState {

  private static final long RELEASE_TIMEOUT_MS = 500;
  private static final ExecutorService FENCE_WAIT_EXECUTOR =
      Util.newSingleThreadExecutor("FrameSharedState:FenceWaitThread");

  private final Object lock;
  private final ReleaseCallback callback;
  private final Executor executor;

  @GuardedBy("lock")
  private int refCount;

  @GuardedBy("lock")
  private final List<SyncFenceWrapper> releaseFences;

  /* package */ FrameSharedState(ReleaseCallback callback, Executor executor) {
    this.lock = new Object();
    this.callback = callback;
    this.executor = executor;
    this.refCount = 1;
    this.releaseFences = new ArrayList<>();
  }

  /** Increments the reference count. */
  /* package */ void retain() {
    synchronized (lock) {
      checkState(refCount > 0, "retain() called on a released FrameSharedState.");
      refCount++;
    }
  }

  /**
   * Releases a reference to the shared state.
   *
   * @param releaseFence An optional {@link SyncFenceWrapper} that must signal before the underlying
   *     resources can be released.
   */
  /* package */ void release(@Nullable SyncFenceWrapper releaseFence) {
    List<SyncFenceWrapper> fencesToWaitOn;

    synchronized (lock) {
      if (refCount <= 0) {
        if (releaseFence != null) {
          releaseFence.close();
        }
        return;
      }

      if (releaseFence != null) {
        releaseFences.add(releaseFence);
      }
      if (--refCount != 0) {
        return;
      }

      fencesToWaitOn = new ArrayList<>(releaseFences);
      releaseFences.clear();
    }

    if (fencesToWaitOn.isEmpty()) {
      executor.execute(() -> callback.release(/* releaseFence= */ null));
      return;
    }

    // TODO(b/540859379): Merge fences if possible and forward to frame owner for buffer reuse.
    try {
      FENCE_WAIT_EXECUTOR.execute(
          () -> {
            try {
              for (int i = 0; i < fencesToWaitOn.size(); i++) {
                checkState(fencesToWaitOn.get(i).awaitMs(RELEASE_TIMEOUT_MS));
              }
            } finally {
              closeAll(fencesToWaitOn);
              try {
                executor.execute(() -> callback.release(/* releaseFence= */ null));
              } catch (RejectedExecutionException e) {
                // Executor is shut down, ignore.
              }
            }
          });
    } catch (RejectedExecutionException e) {
      closeAll(fencesToWaitOn);
      throw e;
    }
  }

  private static void closeAll(List<SyncFenceWrapper> fences) {
    for (int i = 0; i < fences.size(); i++) {
      fences.get(i).close();
    }
  }
}
