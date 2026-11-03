/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer.codecdb;

import static com.google.common.base.Preconditions.checkNotNull;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import androidx.media3.transformer.R;
import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Utility methods for CodecDB tests. */
/* package */ final class TestUtil {

  /** The path of the CodecDB test database within the test assets. */
  public static final String TEST_DATABASE_ASSET_PATH = "media/codecdb/test_database.sqlite";

  /**
   * Copies the CodecDB test database from the test assets into the app's database directory and
   * opens it read-only.
   */
  public static SQLiteDatabase openTestDatabase(Context context) throws IOException {
    File databaseFile = context.getDatabasePath("codecdb_test_database.sqlite");
    checkNotNull(databaseFile.getParentFile()).mkdirs();
    try (FileOutputStream outputStream = new FileOutputStream(databaseFile)) {
      outputStream.write(
          androidx.media3.test.utils.TestUtil.getByteArray(context, TEST_DATABASE_ASSET_PATH));
    }
    return SQLiteDatabase.openDatabase(
        databaseFile.getPath(), /* factory= */ null, SQLiteDatabase.OPEN_READONLY);
  }

  /**
   * Copies the CodecDB database bundled with the library into the app's database directory and
   * opens it read-only.
   */
  public static SQLiteDatabase openBundledDatabase(Context context) throws IOException {
    File databaseFile = context.getDatabasePath("codecdb_bundled_database_copy.sqlite");
    checkNotNull(databaseFile.getParentFile()).mkdirs();
    try (InputStream inputStream =
            context.getResources().openRawResource(R.raw.media3_codecdb_database);
        FileOutputStream outputStream = new FileOutputStream(databaseFile)) {
      ByteStreams.copy(inputStream, outputStream);
    }
    return SQLiteDatabase.openDatabase(
        databaseFile.getPath(), /* factory= */ null, SQLiteDatabase.OPEN_READONLY);
  }

  private TestUtil() {}
}
