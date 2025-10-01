/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.common;

import android.view.View;
import android.view.ViewGroup;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.ListenableFuture;

/** Provider of a view to be displayed in the player UI. */
@UnstableApi
public interface ViewProvider {
  /**
   * Returns a {@link ListenableFuture} with the view.
   *
   * <p>If the view cannot be provided, the future may fail with an exception. Consumers should
   * handle the failure gracefully, for example by not showing the view.
   *
   * <p>The callback of the returned {@link ListenableFuture} may be called on a different thread
   * than the caller's thread. If the caller wants to update the UI in the callbacks, it is
   * responsible for forwarding the callback to the UI thread.
   *
   * @param viewGroup The parent {@link ViewGroup} into which the returned view will be inserted.
   * @return A {@link ListenableFuture} that will resolve to the {@link View}.
   */
  ListenableFuture<View> getView(ViewGroup viewGroup);
}
