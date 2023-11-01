/*
 * Copyright (C) 2018 The Android Open Source Project
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
package androidx.media3.database;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.SupportSQLiteQuery;
import androidx.sqlite.db.SupportSQLiteQueryBuilder;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;

/**
 * An {@link SQLiteOpenHelper} that provides instances of a standalone database.
 *
 * <p>Suitable for use by applications that do not already have their own database, or that would
 * prefer to keep tables used by media library components isolated in their own database. Other
 * applications should prefer to use {@link DefaultDatabaseProvider} with their own {@link
 * SQLiteOpenHelper}.
 */
// TODO: Make this class final when ExoDatabaseProvider is removed.
@UnstableApi
public class StandaloneDatabaseProvider extends SupportSQLiteOpenHelper.Callback implements
    DatabaseProvider {

  /** The file name used for the standalone database. */
  public static final String DATABASE_NAME = "exoplayer_internal.db";

  private static final int VERSION = 1;
  private static final String TAG = "SADatabaseProvider";

  public final SupportSQLiteOpenHelper openHelper;

  /**
   * Provides instances of the database located by passing {@link #DATABASE_NAME} to {@link
   * Context#getDatabasePath(String)}.
   *
   * @param context Any context.
   */
  public StandaloneDatabaseProvider(Context context) {
    this(context, DATABASE_NAME);
  }

  public StandaloneDatabaseProvider(Context context, @Nullable String name) {
    super(VERSION);
    SupportSQLiteOpenHelper.Configuration configuration = SupportSQLiteOpenHelper.Configuration.builder(
        context).name(name).callback(this).build();
    FrameworkSQLiteOpenHelperFactory factory = new FrameworkSQLiteOpenHelperFactory();
    this.openHelper = factory.create(configuration);
  }

  @Override
  public SupportSQLiteDatabase getWritableDatabase() {
    return this.openHelper.getWritableDatabase();
  }

  @Override
  public SupportSQLiteDatabase getReadableDatabase() {
    return this.openHelper.getReadableDatabase();
  }

  @Override
  public void onCreate(SupportSQLiteDatabase supportSQLiteDatabase) {
    // Features create their own tables.
  }

  @Override
  public void onUpgrade(SupportSQLiteDatabase supportSQLiteDatabase, int i, int i1) {
    // Features handle their own upgrades.
  }

  @VisibleForTesting
  public void close() {
    this.openHelper.close();
  }

  @Override
  public void onDowngrade(SupportSQLiteDatabase db, int oldVersion, int newVersion) {
    wipeDatabase(db);
  }

  /**
   * Makes a best effort to wipe the existing database. The wipe may be incomplete if the database
   * contains foreign key constraints.
   */
  private static void wipeDatabase(SupportSQLiteDatabase db) {
    String[] columns = {"type", "name"};
    SupportSQLiteQuery query = SupportSQLiteQueryBuilder.builder("sqlite_master").columns(columns)
        .create();
    try (Cursor cursor = db.query(query)) {
      while (cursor.moveToNext()) {
        String type = cursor.getString(0);
        String name = cursor.getString(1);
        if (!"sqlite_sequence".equals(name)) {
          // If it's not an SQL-controlled entity, drop it
          String sql = "DROP " + type + " IF EXISTS " + name;
          try {
            db.execSQL(sql);
          } catch (SQLException e) {
            Log.e(TAG, "Error executing " + sql, e);
          }
        }
      }
    }
  }
}
