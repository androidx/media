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

import static android.os.Build.VERSION.SDK_INT;
import static com.google.common.base.Preconditions.checkNotNull;

import android.hardware.SyncFence;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import java.time.Duration;

/** A wrapper for {@link SyncFence} that exists on all API levels. */
@ExperimentalApi // TODO: b/449956776 - Remove once FrameConsumer API is finalized.
public final class SyncFenceWrapper {

  private final Object syncFence;

  private SyncFenceWrapper(Object syncFence) {
    this.syncFence = checkNotNull(syncFence);
  }

  /** Returns a {@link SyncFenceWrapper} that wraps the given {@code syncFence}. */
  @RequiresApi(33)
  public static SyncFenceWrapper of(SyncFence syncFence) {
    return new SyncFenceWrapper(syncFence);
  }

  /**
   * Returns the wrapped {@link SyncFence}.
   *
   * @throws IllegalStateException if called on API level &lt; 33.
   */
  @RequiresApi(33)
  public SyncFence asSyncFence() {
    return (SyncFence) syncFence;
  }

  /** Waits for the fence to signal. */
  public boolean await(Duration timeout) {
    if (SDK_INT >= 33) {
      return Api33.await((SyncFence) syncFence, timeout);
    }
    return true;
  }

  /** Waits forever for the fence to signal. */
  public void awaitForever() {
    if (SDK_INT >= 33) {
      Api33.awaitForever((SyncFence) syncFence);
    }
  }

  /** Closes the fence. */
  public void close() {
    if (SDK_INT >= 33) {
      Api33.close((SyncFence) syncFence);
    }
  }

  @RequiresApi(33)
  private static final class Api33 {
    static boolean await(SyncFence fence, Duration timeout) {
      return fence.await(timeout);
    }

    static void awaitForever(SyncFence fence) {
      fence.awaitForever();
    }

    static void close(SyncFence fence) {
      fence.close();
    }
  }
}
