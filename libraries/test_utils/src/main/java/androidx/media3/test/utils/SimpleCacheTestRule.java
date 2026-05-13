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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkNotNull;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.test.core.app.ApplicationProvider;
import java.io.File;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.Rule;
import org.junit.rules.ExternalResource;

/** A JUnit {@link Rule} that creates a {@link SimpleCache} and an in-memory database. */
@UnstableApi
public final class SimpleCacheTestRule extends ExternalResource {

  private @MonotonicNonNull File tempFolder;
  private @MonotonicNonNull DatabaseProvider databaseProvider;
  private @MonotonicNonNull SimpleCache cache;

  @Override
  protected void before() throws Exception {
    tempFolder =
        Util.createTempDirectory(ApplicationProvider.getApplicationContext(), "TestWithCache");
    databaseProvider = TestUtil.getInMemoryDatabaseProvider();
    cache = new SimpleCache(tempFolder, new NoOpCacheEvictor(), databaseProvider);
  }

  @Override
  protected void after() {
    // Dependent resources should always be closed in the reverse order of their creation.
    try {
      if (cache != null) {
        cache.release();
      }
    } finally {
      try {
        if (databaseProvider != null) {
          databaseProvider.getReadableDatabase().close();
        }
      } finally {
        if (tempFolder != null) {
          Util.recursiveDelete(tempFolder);
        }
      }
    }
  }

  /** Returns the {@link SimpleCache} created by this rule. */
  public SimpleCache getCache() {
    return checkNotNull(cache);
  }

  /** Returns the {@link DatabaseProvider} created by this rule. */
  public DatabaseProvider getDatabaseProvider() {
    return checkNotNull(databaseProvider);
  }
}
