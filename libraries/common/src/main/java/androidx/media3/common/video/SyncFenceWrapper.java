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

import static android.os.Build.VERSION.SDK_INT;

import android.hardware.SyncFence;
import androidx.annotation.RequiresApi;
import androidx.media3.common.util.ExperimentalApi;
import java.time.Duration;

/** A wrapper for {@link SyncFence} that exists on all API levels. */
@ExperimentalApi // TODO: b/498176910 Remove once SyncFenceWrapper is production ready.
public final class SyncFenceWrapper {

  private final SyncFence syncFence;

  @RequiresApi(33)
  private SyncFenceWrapper(SyncFence syncFence) {
    this.syncFence = syncFence;
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
    return syncFence;
  }

  /** Waits for the fence to signal. */
  @RequiresApi(26)
  public boolean await(Duration timeout) {
    if (SDK_INT >= 33) {
      return syncFence.await(timeout);
    }
    return true;
  }

  /** Waits for the fence to signal. */
  public boolean awaitMs(long timeoutMs) {
    if (SDK_INT >= 33) {
      return syncFence.await(Duration.ofMillis(timeoutMs));
    }
    return true;
  }

  /** Waits forever for the fence to signal. */
  public void awaitForever() {
    if (SDK_INT >= 33) {
      syncFence.awaitForever();
    }
  }

  /** Closes the fence. */
  public void close() {
    if (SDK_INT >= 33) {
      syncFence.close();
    }
  }
}
