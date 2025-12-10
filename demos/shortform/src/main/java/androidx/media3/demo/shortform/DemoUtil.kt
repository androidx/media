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
package androidx.media3.demo.shortform

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object DemoUtil {

  private const val PRECACHE_CONTENT_DIRECTORY = "precache"

  private lateinit var databaseProvider: DatabaseProvider

  private lateinit var downloadDirectory: File

  private lateinit var downloadCache: Cache

  @Synchronized
  fun getDownloadCache(context: Context): Cache {
    if (!::downloadCache.isInitialized) {
      val downloadContentDirectory = File(getDownloadDirectory(context), PRECACHE_CONTENT_DIRECTORY)
      downloadCache =
        SimpleCache(downloadContentDirectory, NoOpCacheEvictor(), getDatabaseProvider(context))
    }
    return downloadCache
  }

  @Synchronized
  private fun getDatabaseProvider(context: Context): DatabaseProvider {
    if (!::databaseProvider.isInitialized) {
      databaseProvider = StandaloneDatabaseProvider(context)
    }
    return databaseProvider
  }

  @Synchronized
  private fun getDownloadDirectory(context: Context): File {
    if (!::databaseProvider.isInitialized) {
      val externalFilesDir = context.getExternalFilesDir(/* type= */ null)
      downloadDirectory = externalFilesDir ?: context.filesDir
    }
    return downloadDirectory
  }
}
