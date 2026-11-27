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
@file:Suppress("unused_parameter", "unused_variable", "unused", "CheckReturnValue")

package androidx.media3.docsamples.exoplayer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cronet.CronetDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.util.concurrent.Executor
import org.chromium.net.CronetEngine

// Snippets for Network stacks.

object NetworkStacksKt {

  @OptIn(UnstableApi::class)
  private class PreferredHttpDataSource(val context: Context) {
    class Factory(val context: Context) : DataSource.Factory {
      override fun createDataSource(): DataSource {
        return DefaultDataSource(context, true)
      }
    }
  }

  fun configureHttpDataSource(context: Context) {
    // [START configure_http_data_source]
    DefaultDataSource.Factory(
      context,
      /* baseDataSourceFactory= */ PreferredHttpDataSource.Factory(context),
    )
    // [END configure_http_data_source]
  }

  fun useCronetWithFallback(cronetEngine: CronetEngine, executor: Executor, context: Context) {
    // [START use_cronet_with_fallback]
    // Given a CronetEngine and Executor, build a CronetDataSource.Factory.
    val cronetDataSourceFactory = CronetDataSource.Factory(cronetEngine, executor)

    // Wrap the CronetDataSource.Factory in a DefaultDataSource.Factory, which adds
    // in support for requesting data from other sources (such as files, resources,
    // etc).
    val dataSourceFactory =
      DefaultDataSource.Factory(context, /* baseDataSourceFactory= */ cronetDataSourceFactory)

    // Inject the DefaultDataSource.Factory when creating the player.
    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory)
        )
        .build()
    // [END use_cronet_with_fallback]
  }

  @OptIn(UnstableApi::class)
  fun cachingMedia(
    httpDataSourceFactory: HttpDataSource.Factory,
    context: Context,
    downloadDirectory: File,
    maxBytes: Long,
  ) {
    // [START caching_media]
    // Note: This should be a singleton in your app.
    val databaseProvider = StandaloneDatabaseProvider(context)

    // An on-the-fly cache should evict media when reaching a maximum disk space limit.
    val cache =
      SimpleCache(downloadDirectory, LeastRecentlyUsedCacheEvictor(maxBytes), databaseProvider)

    // Configure the DataSource.Factory with the cache and factory for the desired HTTP stack.
    val cacheDataSourceFactory =
      CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(httpDataSourceFactory)

    // Inject the DefaultDataSource.Factory when creating the player.
    val player =
      ExoPlayer.Builder(context)
        .setMediaSourceFactory(
          DefaultMediaSourceFactory(context).setDataSourceFactory(cacheDataSourceFactory)
        )
        .build()
    // [END caching_media]
  }
}
