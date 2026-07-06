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

import static com.google.common.base.StandardSystemProperty.JAVA_IO_TMPDIR;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.DefaultDatabaseProvider;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.rules.ExternalResource;
import org.junit.rules.RuleChain;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A JUnit {@link Rule} that creates (and cleans up) in-memory databases and {@link SimpleCache}
 * instances.
 */
@UnstableApi
public final class InMemoryDatabaseRule implements TestRule {

  private final RuleImpl ruleImpl;
  private final RuleChain ruleChain;

  private InMemoryDatabaseRule(RuleImpl ruleImpl, RuleChain ruleChain) {
    this.ruleImpl = ruleImpl;
    this.ruleChain = ruleChain;
  }

  public static InMemoryDatabaseRule create() {
    TemporaryFolder temporaryFolder = new TemporaryFolder();
    RuleImpl ruleImpl = new RuleImpl(temporaryFolder);
    ExternalResource tmpDirSetupResource =
        new ExternalResource() {
          @Override
          protected void before() throws Throwable {
            // On some Android environments, particularly physical devices on clean runs, the
            // directory returned by JAVA_IO_TMPDIR may not exist, causing issues with
            // TemporaryFolder.
            String tmpDirProperty = JAVA_IO_TMPDIR.value();
            if (tmpDirProperty != null) {
              File tmpDir = new File(tmpDirProperty);
              if (!tmpDir.exists()) {
                if (!tmpDir.mkdirs()) {
                  throw new IOException("Failed to create java.io.tmpdir: " + tmpDir);
                }
              }
            }
          }
        };
    return new InMemoryDatabaseRule(
        ruleImpl,
        RuleChain.outerRule(tmpDirSetupResource).around(temporaryFolder).around(ruleImpl));
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return ruleChain.apply(base, description);
  }

  /** Returns a {@link SimpleCache} created by this rule. */
  public SimpleCache createSimpleCache() throws IOException {
    return ruleImpl.createCache();
  }

  /** Returns the {@link DatabaseProvider} created by this rule. */
  public DatabaseProvider createDatabaseProvider() {
    return ruleImpl.createDatabaseProvider();
  }

  private static final class RuleImpl extends ExternalResource {
    private final TemporaryFolder tempFolder;
    private final List<SQLiteOpenHelper> sqliteOpenHelpers;
    private final List<SimpleCache> caches;

    private RuleImpl(TemporaryFolder tempFolder) {
      this.tempFolder = tempFolder;
      sqliteOpenHelpers = new ArrayList<>();
      caches = new ArrayList<>();
    }

    @Override
    protected void after() {
      // Dependent resources should always be closed in the reverse order of their creation.
      List<RuntimeException> releaseErrors = new ArrayList<>();
      for (SimpleCache cache : caches) {
        try {
          cache.release();
        } catch (RuntimeException e) {
          releaseErrors.add(e);
        }
      }
      for (SQLiteOpenHelper sqliteOpenHelper : sqliteOpenHelpers) {
        try {
          sqliteOpenHelper.close();
        } catch (RuntimeException e) {
          releaseErrors.add(e);
        }
      }
      if (!releaseErrors.isEmpty()) {
        RuntimeException firstError = releaseErrors.get(0);
        for (int i = 1; i < releaseErrors.size(); i++) {
          firstError.addSuppressed(releaseErrors.get(i));
        }
        throw firstError;
      }
    }

    private SimpleCache createCache() throws IOException {
      SimpleCache simpleCache =
          new SimpleCache(tempFolder.newFolder(), new NoOpCacheEvictor(), createDatabaseProvider());
      caches.add(simpleCache);
      return simpleCache;
    }

    private DatabaseProvider createDatabaseProvider() {
      SQLiteOpenHelper sqLiteOpenHelper = new NoOpSQLiteOpenHelper();
      sqliteOpenHelpers.add(sqLiteOpenHelper);
      return new DefaultDatabaseProvider(sqLiteOpenHelper);
    }
  }

  private static final class NoOpSQLiteOpenHelper extends SQLiteOpenHelper {

    private NoOpSQLiteOpenHelper() {
      super(/* context= */ null, /* name= */ null, /* factory= */ null, /* version= */ 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
      // Do nothing.
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
      // Do nothing.
    }
  }
}
