package androidx.media3.database;

import android.content.Context;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;

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
/**
 * @deprecated Use {@link SupportDatabaseProvider} instead.
 */
@Deprecated
public class DefaultDatabaseProvider extends SupportSQLiteOpenHelper.Callback
    implements DatabaseProvider {

  private static final int VERSION = 1;
  private final SupportSQLiteOpenHelper helper;

  private SupportSQLiteOpenHelper createHelper(Context context, SQLiteOpenHelper openHelper) {
    SupportSQLiteOpenHelper.Configuration cfg =
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(openHelper.getDatabaseName())
            .callback(this)
            .build();
    return new FrameworkSQLiteOpenHelperFactory().create(cfg);
  }

  public DefaultDatabaseProvider(Context context, SQLiteOpenHelper openHelper) {
    super(VERSION);
    this.helper = createHelper(context, openHelper);
  }

  @Override
  public SupportSQLiteDatabase getWritableDatabase() {
    return helper.getWritableDatabase();
  }

  @Override
  public void onCreate(SupportSQLiteDatabase supportSQLiteDatabase) {
    // Features create their own tables.
  }

  @Override
  public void onUpgrade(SupportSQLiteDatabase supportSQLiteDatabase, int i, int i1) {
    // Features handle their own upgrades.
  }

  @Override
  public SupportSQLiteDatabase getReadableDatabase() {
    return helper.getReadableDatabase();
  }
}
