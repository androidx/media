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
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.exoplayer.source.MediaSource;
import com.google.common.base.Supplier;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** Supplies a {@link MediaSource.Factory}. */
@UnstableApi
public interface MediaSourceFactorySupplier extends Supplier<MediaSource.Factory> {

  /**
   * Sets the {@link Cache} that will be used by the supplied {@link MediaSource.Factory}.
   *
   * @return This supplier, for convenience.
   */
  @CanIgnoreReturnValue
  MediaSourceFactorySupplier setCache(@Nullable Cache cache);

  /**
   * Sets the {@link DataSource.Factory} that will be used by the supplied {@link
   * MediaSource.Factory}.
   *
   * @return This supplier, for convenience.
   */
  @CanIgnoreReturnValue
  MediaSourceFactorySupplier setDataSourceFactory(@Nullable DataSource.Factory dataSourceFactory);
}
