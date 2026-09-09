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
package androidx.media3.transformer;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Log;
import androidx.media3.common.video.AsyncFrame;
import androidx.media3.common.video.Frame;
import androidx.media3.common.video.ReferenceCounter;
import androidx.media3.common.video.SyncFenceWrapper;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Manages {@link Frame} instances currently in flight downstream, releasing them when processed if
 * they implement {@link ReferenceCounter}.
 *
 * <p>All methods must be called on the playback thread.
 */
/* package */ final class InFlightFrameManager {

  private static final String TAG = "InFlightFrameManager";

  private final Object lock = new Object();

  // TODO(b/518679527): Verify that inFlightFrames is only accessed on the playback thread, and
  //  remove this lock.
  @GuardedBy("lock")
  private final Set<Frame> inFlightFrames = new HashSet<>();

  /**
   * Tracks a list of {@link AsyncFrame} instances during an attempt to queue them, removing them
   * from tracking if queuing fails or throws an exception.
   *
   * @param queueAction The action attempting to queue the frames.
   * @param packet The packet of {@link AsyncFrame} instances.
   * @return {@code true} if the packet was successfully queued, {@code false} otherwise.
   */
  @CanIgnoreReturnValue
  <T extends List<AsyncFrame>> boolean trackIfSuccessful(Predicate<T> queueAction, T packet) {
    synchronized (lock) {
      for (int i = 0; i < packet.size(); i++) {
        inFlightFrames.add(packet.get(i).frame);
      }
    }
    boolean success = false;
    try {
      success = queueAction.apply(packet);
      return success;
    } finally {
      if (!success) {
        synchronized (lock) {
          for (int i = 0; i < packet.size(); i++) {
            inFlightFrames.remove(packet.get(i).frame);
          }
        }
      }
    }
  }

  /**
   * Called when a downstream frame has been processed. Releases the matching {@link
   * ReferenceCounter} if applicable.
   */
  void onFrameProcessed(Frame frame, @Nullable SyncFenceWrapper releaseFence) {
    // If FrameProcessor passes decorated frame, fetching the frame will silently fail.
    // TODO(b/539914074): throw if frame is not found, and/or document expectations for decorated
    //  frames.
    boolean wasTracked;
    synchronized (lock) {
      wasTracked = inFlightFrames.remove(frame);
    }
    if (wasTracked) {
      TransformerUtil.releaseIfNeeded(frame, releaseFence);
    } else {
      if (releaseFence != null) {
        releaseFence.close();
      }
      Log.d(TAG, "onFrameProcessed: Frame not found: " + frame);
    }
  }

  /** Releases all currently in-flight frames. */
  void releaseAll() {
    ImmutableList<Frame> framesToRelease;
    synchronized (lock) {
      framesToRelease = ImmutableList.copyOf(inFlightFrames);
      inFlightFrames.clear();
    }
    TransformerUtil.releaseIfNeeded(framesToRelease);
  }
}
