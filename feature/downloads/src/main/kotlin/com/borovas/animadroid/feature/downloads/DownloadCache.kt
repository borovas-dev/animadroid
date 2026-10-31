@file:OptIn(UnstableApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DownloadCacheModule {
    @Provides
    @Singleton
    fun databaseProvider(@ApplicationContext context: Context): DatabaseProvider = StandaloneDatabaseProvider(context)

    /**
     * The single offline store. Lives in the app's files dir (not cacheDir, so the OS never evicts it) and is
     * never evicted automatically: only explicit removal of a download frees space.
     */
    @Provides
    @Singleton
    fun downloadCache(@ApplicationContext context: Context, databaseProvider: DatabaseProvider): Cache =
        SimpleCache(File(context.filesDir, "downloads"), NoOpCacheEvictor(), databaseProvider)
}

/**
 * Builds [CacheDataSource.Factory]s over the shared [Cache]. [forDownload] reads and writes the cache;
 * [forPlayback] only reads it (streaming playback must not silently grow the offline store) and falls back
 * to the network for anything that is not cached. Both send the stream's request [headers] (Referer/UA).
 */
@Singleton
class CacheDataSources @Inject constructor(val cache: Cache) {
    fun forDownload(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http(headers))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun forPlayback(headers: Map<String, String>): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http(headers))
            .setCacheWriteDataSinkFactory(null) // read-only
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private fun http(headers: Map<String, String>): DataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setDefaultRequestProperties(headers)
}
