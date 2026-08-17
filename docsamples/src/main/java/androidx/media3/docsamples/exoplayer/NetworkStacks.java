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
package androidx.media3.docsamples.exoplayer;

import android.content.Context;
import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.cache.Cache;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.cronet.CronetDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.File;
import java.util.concurrent.Executor;
import org.chromium.net.CronetEngine;

/** Snippets for Network stacks. */
@SuppressWarnings({
  "unused",
  "CheckReturnValue",
  "PrivateConstructorForUtilityClass",
  "EffectivelyPrivate",
  "resource"
})
public class NetworkStacks {

  @OptIn(markerClass = UnstableApi.class)
  private static final class PreferredHttpDataSource {
    private static final class Factory implements DataSource.Factory {
      private final Context context;

      public Factory(Context context) {
        this.context = context;
      }

      @Override
      public DataSource createDataSource() {
        return new DefaultDataSource(context, true);
      }
    }
  }

  public static void configureHttpDataSource(Context context) {
    // [START configure_http_data_source]
    new DefaultDataSource.Factory(
        context, /* baseDataSourceFactory= */ new PreferredHttpDataSource.Factory(context));
    // [END configure_http_data_source]
  }

  public static void useCronetWithFallback(
      CronetEngine cronetEngine, Executor executor, Context context) {
    // [START use_cronet_with_fallback]
    // Given a CronetEngine and Executor, build a CronetDataSource.Factory.
    CronetDataSource.Factory cronetDataSourceFactory =
        new CronetDataSource.Factory(cronetEngine, executor);

    // Wrap the CronetDataSource.Factory in a DefaultDataSource.Factory, which adds
    // in support for requesting data from other sources (such as files, resources,
    // etc).
    DefaultDataSource.Factory dataSourceFactory =
        new DefaultDataSource.Factory(
            context, /* baseDataSourceFactory= */ cronetDataSourceFactory);

    // Inject the DefaultDataSource.Factory when creating the player.
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context).setDataSourceFactory(dataSourceFactory))
            .build();
    // [END use_cronet_with_fallback]
  }

  @OptIn(markerClass = UnstableApi.class)
  public static void cachingMedia(
      HttpDataSource.Factory httpDataSourceFactory,
      Context context,
      File downloadDirectory,
      long maxBytes) {
    // [START caching_media]
    // Note: This should be a singleton in your app.
    DatabaseProvider databaseProvider = new StandaloneDatabaseProvider(context);

    // An on-the-fly cache should evict media when reaching a maximum disk space limit.
    Cache cache =
        new SimpleCache(
            downloadDirectory, new LeastRecentlyUsedCacheEvictor(maxBytes), databaseProvider);

    // Configure the DataSource.Factory with the cache and factory for the desired HTTP stack.
    DataSource.Factory cacheDataSourceFactory =
        new CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory);

    // Inject the DefaultDataSource.Factory when creating the player.
    ExoPlayer player =
        new ExoPlayer.Builder(context)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(context).setDataSourceFactory(cacheDataSourceFactory))
            .build();
    // [END caching_media]
  }
}
