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

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import androidx.annotation.VisibleForTesting;
import androidx.media3.common.util.ExperimentalApi;
import androidx.media3.transformer.R;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Recommends video encoder settings for the device your app is running on.
 *
 * <p>The same video encoder settings can produce noticeably different quality on different Android
 * devices, because each device's chipset has its own hardware encoders. CodecDB contains measured
 * quality data for the video encoders on many Android devices and chipsets. It uses this data to
 * recommend a {@linkplain VideoEncoderConfiguration configuration} (codec, encoder settings and
 * bitrate) that meets your requirements on the current device, and to {@linkplain
 * CodecDbEncoderDataset#predictQualityFor predict the quality} of a configuration you already have.
 * Quality is measured with VMAF, a score from 0 to 100 of how close the encoded video looks to the
 * original.
 *
 * <p>To get a recommendation:
 *
 * <ol>
 *   <li>Call {@link #onDevice(Context)}, then {@link CodecDbDatasetLoader#forEncoding}, to load the
 *       encoder data for the current device.
 *   <li>Describe what you need with {@link VideoEncoderConstraints}: the resolution and frame rate,
 *       plus a target quality or a maximum bitrate. {@link VideoEncoderConstraintsPresets} provides
 *       starting points for common uses such as editing, camera recording, messaging and live
 *       streaming.
 *   <li>Call {@link CodecDbEncoderDataset#findOptimalConfiguration} and use the {@link
 *       VideoEncoderConfiguration#format} and {@link
 *       VideoEncoderConfiguration#videoEncoderSettings} of the returned configuration to configure
 *       the encoder.
 * </ol>
 *
 * <p>CodecDB gives recommendations based on measurements of the exact device model and of the
 * device's chipset. If those measurements can't meet the requested {@link VideoEncoderConstraints},
 * CodecDB falls back to measurements for devices in the same performance tier and to generic data.
 * If none of these meet the constraints, CodecDB returns the result of the fallback passed to
 * {@link CodecDbDatasetLoader#forEncoding}.
 *
 * <h2>Usage example</h2>
 *
 * <pre>{@code
 * CodecDbEncoderDataset dataset = CodecDb.onDevice(context).forEncoding(constraints -> null);
 * VideoEncoderConfiguration config =
 *     dataset.findOptimalConfiguration(
 *         VideoEncoderConstraintsPresets.forEditing(1920, 1080, 30.0)
 *             .buildUpon()
 *             .setTargetQuality(VideoEncoderConstraints.QUALITY_LEVEL_GOOD)
 *             .build());
 * if (config != null) {
 *   // Configure the encoder with config.format and config.videoEncoderSettings.
 * }
 * }</pre>
 *
 * <h2>Threading</h2>
 *
 * <p>{@link #onDevice(Context)} and {@link CodecDbDatasetLoader#forEncoding} read from and write to
 * disk and should not be called on the main thread. A loaded {@link CodecDbEncoderDataset} doesn't
 * change and can be queried from any thread, as long as the fallback passed to {@link
 * CodecDbDatasetLoader#forEncoding} is thread-safe.
 */
@ExperimentalApi // TODO(b/579130160): Promote to @UnstableApi or remove.
public final class CodecDb {

  /**
   * The fork time ({@code DatabaseMetadata.ForkTimeSeconds}) of {@code
   * raw/media3_codecdb_database}. {@link #onDevice(Context)} compares it with the copy of the
   * database on disk to decide whether to unpack the database again.
   *
   * <p>This must match the fork time of the bundled database, so update it whenever the database
   * changes. {@code CodecDbTest} checks this.
   */
  @VisibleForTesting /* package */ static final long DATABASE_FORK_TIME = 1793209866L;

  private static final String CODEC_DB_NAME = "androidx.media3.codecdb";

  private CodecDb() {}

  /**
   * Prepares the CodecDB database for the current device, returning a {@link CodecDbDatasetLoader}
   * that can be used to load encoder datasets.
   *
   * <p>This method performs blocking disk I/O and should not be called on the main thread.
   *
   * <p>The returned loader does not hold a database connection. Each {@link
   * CodecDbDatasetLoader#forEncoding} call opens the read-only database, loads the models it needs
   * into memory, and closes the database before returning, so callers have nothing to clean up.
   *
   * @param context The Android {@link Context} used to access application resources and database
   *     paths.
   * @return A {@link CodecDbDatasetLoader} configured for the current runtime device.
   * @throws IOException If the embedded database cannot be unpacked or read from disk.
   */
  public static CodecDbDatasetLoader onDevice(Context context) throws IOException {
    File dbFile = context.getDatabasePath(CODEC_DB_NAME);

    if (!dbFile.exists()) {
      unpackDatabaseToDisk(context, dbFile);
    }

    SQLiteDatabase db;

    try {
      db = openReadOnly(dbFile);
    } catch (SQLiteException sqle) {

      // If we can't read the database on the filesystem, try to unpack again then read from
      // the newly unpacked file.
      unpackDatabaseToDisk(context, dbFile);
      db = openReadOnly(dbFile);
    }

    boolean isStale;
    try (EmbeddedDatabase embeddedDatabase = new AndroidEmbeddedDatabase(db)) {
      isStale = embeddedDatabase.databaseMetadataDao.getForkTime() != DATABASE_FORK_TIME;
    }

    if (isStale) {
      unpackDatabaseToDisk(context, dbFile);
    }

    return new CodecDbDatasetLoader(
        DeviceIdentifiers.fromRuntime(), () -> new AndroidEmbeddedDatabase(openReadOnly(dbFile)));
  }

  private static SQLiteDatabase openReadOnly(File dbFile) {
    return SQLiteDatabase.openDatabase(
        dbFile.getPath(), /* factory= */ null, SQLiteDatabase.OPEN_READONLY);
  }

  private static void unpackDatabaseToDisk(Context context, File dbFile) throws IOException {
    if (dbFile.exists()) {
      if (!dbFile.delete()) {
        throw new IOException("Unable to delete existing unpacked CodecDB database.");
      }
    }

    try (InputStream is = context.getResources().openRawResource(R.raw.media3_codecdb_database)) {
      if (dbFile.getParentFile() != null) {
        dbFile.getParentFile().mkdirs();
      }
      if (!dbFile.createNewFile()) {
        throw new IOException("Can't unpack CodecDB database.");
      }

      try (OutputStream os = new BufferedOutputStream(new FileOutputStream(dbFile))) {
        byte[] buffer = new byte[256];

        int read;
        while ((read = is.read(buffer)) != -1) {
          os.write(buffer, 0, read);
        }
      }
    }
  }
}
