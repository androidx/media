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
package androidx.media3.exoplayer.source.preload;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import java.util.Comparator;

/** A comparator of ranking data. */
@UnstableApi
public interface RankingDataComparator<T> extends Comparator<T> {

  /**
   * Notified when the {@link RankingDataComparator} has its state changed and the ranking has
   * become invalid.
   */
  interface InvalidationListener {

    /**
     * Called by a {@link RankingDataComparator} to indicate that its state has changed and the
     * ranking has become invalid.
     */
    void onRankingDataComparatorInvalidated();
  }

  /** Sets the {@link InvalidationListener}. */
  void setInvalidationListener(@Nullable InvalidationListener invalidationListener);
}
