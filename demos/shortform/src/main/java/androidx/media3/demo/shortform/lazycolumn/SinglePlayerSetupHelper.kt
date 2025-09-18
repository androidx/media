package androidx.media3.demo.shortform.lazycolumn

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.demo.shortform.MediaItemDatabase
import androidx.media3.demo.shortform.lazycolumn.LazyColumnActivity.Companion.LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS
import androidx.media3.demo.shortform.lazycolumn.LazyColumnActivity.Companion.LOAD_CONTROL_MAX_BUFFER_MS
import androidx.media3.demo.shortform.lazycolumn.LazyColumnActivity.Companion.LOAD_CONTROL_MIN_BUFFER_MS
import androidx.media3.demo.shortform.lazycolumn.LazyColumnActivity.Companion.PLAYER_CACHE_DIRECTORY
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.preload.DefaultPreloadManager
import java.io.File

@OptIn(UnstableApi::class)
class SinglePlayerSetupHelper(appContext: Context) {

    var preloadManager: DefaultPreloadManager
    val targetPreloadStatusControl = LazyColumnTargetPreloadStatusControl()
    val mediaItemDatabase = MediaItemDatabase()
    val cache: SimpleCache

    var player: ExoPlayer


    init {
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(
            LOAD_CONTROL_MIN_BUFFER_MS,
            LOAD_CONTROL_MAX_BUFFER_MS,
            LOAD_CONTROL_BUFFER_FOR_PLAYBACK_MS,
            DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
        ).setPrioritizeTimeOverSizeThresholds(true).build()

        val databaseProvider = StandaloneDatabaseProvider(appContext)
        val cacheEvictor = LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024)
        cache = SimpleCache(
            File(appContext.cacheDir, PLAYER_CACHE_DIRECTORY), cacheEvictor, databaseProvider
        )
        val upstreamFactory = DefaultDataSource.Factory(appContext)
        val cacheDataSourceFactory =
            CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val preloadManagerBuilder =
            DefaultPreloadManager.Builder(appContext, targetPreloadStatusControl)
                .setLoadControl(loadControl)
                .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        preloadManager = preloadManagerBuilder.build()
        player = preloadManagerBuilder.buildExoPlayer().apply {
            prepare()
            pauseAtEndOfMediaItems = true
        }
    }

}