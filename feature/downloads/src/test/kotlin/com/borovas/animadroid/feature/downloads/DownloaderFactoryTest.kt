@file:OptIn(UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloaderFactoryTest {
    private lateinit var cache: SimpleCache
    private lateinit var dir: File

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        dir = File(ctx.cacheDir, "dl-test-" + System.nanoTime())
        cache = SimpleCache(dir, NoOpCacheEvictor(), StandaloneDatabaseProvider(ctx))
    }

    @After fun tearDown() {
        cache.release()
        dir.deleteRecursively()
    }

    @Test fun hlsRequestsGetHlsDownloaderAndOthersProgressive() {
        val factory = HeaderAwareDownloaderFactory(CacheDataSources(cache))
        val hls = DownloadRequest.Builder("a", Uri.parse("https://cdn/v.m3u8")).setMimeType(MimeTypes.APPLICATION_M3U8)
            .setData(DownloadPayload(mapOf("Referer" to "https://site/")).encode()).build()
        val sub = DownloadRequest.Builder("a#sub0", Uri.parse("https://cdn/en.vtt")).build()
        assertTrue(factory.createDownloader(hls) is HlsDownloader)
        assertTrue(factory.createDownloader(sub) is ProgressiveDownloader)
    }

    @Test fun playbackFactoryIsReadOnlyAndDownloadFactoryWrites() {
        val sources = CacheDataSources(cache)
        // Writing is what makes cache content grow; a playback source must never get a write sink.
        val read = sources.forPlayback(emptyMap()).createDataSource()
        val write = sources.forDownload(emptyMap()).createDataSource()
        val sink = androidx.media3.datasource.cache.CacheDataSource::class.java.getDeclaredField("cacheWriteDataSource").apply { isAccessible = true }
        assertEquals(null, sink.get(read))
        assertTrue(sink.get(write) != null)
    }
}
