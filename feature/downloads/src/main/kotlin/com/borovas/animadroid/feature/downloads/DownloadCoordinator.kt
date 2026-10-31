@file:OptIn(UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.offline.ProgressiveDownloader
import androidx.media3.exoplayer.scheduler.Requirements
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.DownloadController
import com.borovas.animadroid.core.model.DownloadItem
import com.borovas.animadroid.core.model.DownloadRepository
import com.borovas.animadroid.core.model.DownloadState
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Creates the right Media3 downloader per request, honouring the per-download headers in the request payload. */
internal class HeaderAwareDownloaderFactory(private val sources: CacheDataSources) : DownloaderFactory {
    private val executor = Executors.newFixedThreadPool(4)

    override fun createDownloader(request: DownloadRequest): Downloader {
        val payload = DownloadPayload.decode(request.data)
        val cacheFactory = sources.forDownload(payload.headers)
        val item = MediaItem.Builder()
            .setUri(request.uri)
            .setMimeType(request.mimeType)
            .setStreamKeys(request.streamKeys)
            .setCustomCacheKey(request.customCacheKey)
            .build()
        return when (androidx.media3.common.util.Util.inferContentTypeForUriAndMimeType(request.uri, request.mimeType)) {
            C.CONTENT_TYPE_HLS -> HlsDownloader(item, cacheFactory, executor)
            else -> ProgressiveDownloader(item, cacheFactory, executor)
        }
    }
}

/**
 * Owns the app's single Media3 [DownloadManager], keeps the Room [DownloadRepository] in sync with it and
 * implements [DownloadController]. The [AnimadroidDownloadService] hosts the manager as a foreground
 * service; all mutating commands go through the service so it is guaranteed to be running.
 */
@Singleton
class DownloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val databaseProvider: DatabaseProvider,
    private val sources: CacheDataSources,
    private val repository: DownloadRepository,
    private val settings: SettingsRepository,
    private val providers: Map<String, @JvmSuppressWildcards AnimeProvider>,
) : DownloadController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Latest known state of every Media3 download part (including finished ones), by part id. */
    private val known = ConcurrentHashMap<String, Download>()
    private var ticker: Job? = null
    private var reconcileJob: Job? = null

    private val _waitingForNetwork = MutableStateFlow(false)

    /** True while queued downloads wait for an unmet requirement (e.g. Wi-Fi only but on mobile data). */
    val waitingForNetwork: StateFlow<Boolean> = _waitingForNetwork.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-shot user-visible errors (e.g. stream lookup failed). */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val manager: DownloadManager by lazy { createManager() }

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            known[download.request.id] = download
            syncKey(DownloadIds.keyOf(download.request.id))
            ensureTicker()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            known.remove(download.request.id)
            val key = DownloadIds.keyOf(download.request.id)
            // Removed from outside the UI (e.g. "cancel all" in the notification): drop the row once no part is left.
            if (known.keys.none { DownloadIds.keyOf(it) == key }) {
                DownloadIds.parse(key)?.let { p -> scope.launch { repository.delete(p.providerId, p.animeId, p.episodeNumber) } }
            } else {
                syncKey(key)
            }
        }

        override fun onRequirementsStateChanged(downloadManager: DownloadManager, requirements: Requirements, notMetRequirements: Int) {
            _waitingForNetwork.value = notMetRequirements != 0
        }

        override fun onWaitingForRequirementsChanged(downloadManager: DownloadManager, waitingForRequirements: Boolean) {
            _waitingForNetwork.value = waitingForRequirements
        }
    }

    private fun createManager(): DownloadManager {
        val m = DownloadManager(context, DefaultDownloadIndex(databaseProvider), HeaderAwareDownloaderFactory(sources))
        m.maxParallelDownloads = 2
        m.minRetryCount = 3
        m.requirements = Requirements(Requirements.NETWORK_UNMETERED) // refined below once settings are read
        m.addListener(listener)
        m.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) c.download.let { known[it.request.id] = it } }
        scope.launch {
            settings.settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                withContext(Dispatchers.Main) {
                    m.requirements = Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)
                }
            }
        }
        reconcileJob = scope.launch { reconcile() }
        return m
    }

    /** Brings Room in line with Media3 after a process restart and restarts the service if work is pending. */
    private suspend fun reconcile() {
        val items = repository.getAll()
        var pending = false
        for (item in items) {
            val key = DownloadIds.episodeKey(item.providerId, item.animeId, item.episodeNumber)
            val parts = known.values.filter { DownloadIds.keyOf(it.request.id) == key }
            if (parts.isEmpty()) {
                // QUEUED rows whose stream lookup was interrupted by process death can never finish.
                if (item.state != DownloadState.COMPLETED && item.state != DownloadState.FAILED) {
                    repository.updateProgress(item.providerId, item.animeId, item.episodeNumber, DownloadState.FAILED, item.bytes, item.percent)
                }
            } else {
                syncKey(key)
                if (parts.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }) pending = true
            }
        }
        if (pending) withContext(Dispatchers.Main) { startService() }
    }

    private fun startService() {
        runCatching { androidx.media3.exoplayer.offline.DownloadService.start(context, AnimadroidDownloadService::class.java) }
    }

    private fun partsOf(key: String): List<Download> = known.values.filter { DownloadIds.keyOf(it.request.id) == key }

    private fun syncKey(key: String) {
        val agg = DownloadAggregator.aggregate(partsOf(key).map { PartState(it.request.id, it.state, it.bytesDownloaded, it.percentDownloaded) }) ?: return
        val p = DownloadIds.parse(key) ?: return
        scope.launch { repository.updateProgress(p.providerId, p.animeId, p.episodeNumber, agg.state, agg.bytes, agg.percent) }
    }

    /** Media3 does not notify listeners on byte progress; poll the live downloads once a second while any is active. */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                val current = withContext(Dispatchers.Main) { manager.currentDownloads }
                current.forEach { known[it.request.id] = it }
                current.map { DownloadIds.keyOf(it.request.id) }.distinct().forEach(::syncKey)
                if (current.none { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }) break
                delay(1000)
            }
        }
    }

    // --- DownloadController -------------------------------------------------------------------------------

    /** Runs on the coordinator's own scope so leaving the calling screen cannot strand a half-queued row. */
    override suspend fun enqueue(anime: Anime, episode: Episode, mode: TranslationMode) {
        scope.launch { enqueueNow(anime, episode, mode) }.join()
    }

    override suspend fun enqueueAll(anime: Anime, episodes: List<Episode>, mode: TranslationMode) {
        scope.launch { episodes.forEach { enqueueNow(anime, it, mode) } }.join()
    }

    private suspend fun enqueueNow(anime: Anime, episode: Episode, mode: TranslationMode) {
        val key = DownloadIds.episodeKey(anime.providerId, anime.id, episode.number)
        val existing = repository.get(anime.providerId, anime.id, episode.number)
        when (existing?.state) {
            DownloadState.COMPLETED, DownloadState.QUEUED, DownloadState.DOWNLOADING -> return
            DownloadState.PAUSED -> if (partsOf(key).isNotEmpty()) { resume(anime.providerId, anime.id, episode.number); return }
            else -> Unit
        }
        manager // make sure the manager exists, and its startup reconcile is done, before we add a row
        reconcileJob?.join()
        val base = DownloadItem(
            providerId = anime.providerId, animeId = anime.id, episodeId = episode.id, episodeNumber = episode.number,
            title = anime.title, coverUrl = anime.coverUrl, mode = mode, state = DownloadState.QUEUED,
            createdAt = existing?.createdAt?.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        repository.upsert(base)
        try {
            val pref = settings.settings.first().quality
            val provider = providers[anime.providerId] ?: error("Unknown provider ${anime.providerId}")
            val set = provider.streams(anime, episode, mode)
            val plan = DownloadPlanner.plan(set, pref) ?: error("No downloadable stream found")
            repository.upsert(base.copy(quality = plan.quality, streamSet = plan.snapshot))
            val payload = DownloadPayload(plan.snapshot.headers, "${anime.title} · Episode ${episode.number}").encode()
            // Drop leftovers of a previous failed attempt so it restarts from a clean state.
            partsOf(key).forEach { sendRemove(it.request.id) }
            val requests = DownloadPlanner.parts(key, plan.snapshot).map { part ->
                DownloadRequest.Builder(part.id, Uri.parse(part.url))
                    .setMimeType(if (part.isHls) MimeTypes.APPLICATION_M3U8 else null)
                    .setData(payload)
                    .build()
            }
            withContext(Dispatchers.Main) {
                requests.forEach {
                    androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(
                        context, AnimadroidDownloadService::class.java, it, /* foreground = */ false,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repository.updateProgress(anime.providerId, anime.id, episode.number, DownloadState.FAILED, 0, 0f)
            _messages.tryEmit("Could not start download: ${e.message ?: "unknown error"}")
        }
    }

    private fun sendRemove(partId: String) {
        androidx.media3.exoplayer.offline.DownloadService.sendRemoveDownload(context, AnimadroidDownloadService::class.java, partId, false)
    }

    private fun setStopReason(providerId: String, animeId: String, episodeNumber: String, reason: Int) {
        val key = DownloadIds.episodeKey(providerId, animeId, episodeNumber)
        partsOf(key).filter { it.state != Download.STATE_COMPLETED && it.state != Download.STATE_FAILED }.forEach {
            androidx.media3.exoplayer.offline.DownloadService.sendSetStopReason(
                context, AnimadroidDownloadService::class.java, it.request.id, reason, false,
            )
        }
    }

    override fun pause(providerId: String, animeId: String, episodeNumber: String) =
        setStopReason(providerId, animeId, episodeNumber, STOP_REASON_PAUSED)

    override fun resume(providerId: String, animeId: String, episodeNumber: String) {
        val key = DownloadIds.episodeKey(providerId, animeId, episodeNumber)
        if (partsOf(key).any { it.state == Download.STATE_FAILED }) return // failures are retried through enqueue()
        setStopReason(providerId, animeId, episodeNumber, Download.STOP_REASON_NONE)
    }

    override fun remove(providerId: String, animeId: String, episodeNumber: String) {
        val key = DownloadIds.episodeKey(providerId, animeId, episodeNumber)
        val parts = partsOf(key)
        parts.forEach { sendRemove(it.request.id) }
        scope.launch { repository.delete(providerId, animeId, episodeNumber) }
    }

    fun pauseAll() = known.values.filter { DownloadIds.isMain(it.request.id) }.forEach {
        DownloadIds.parse(it.request.id)?.let { p -> pause(p.providerId, p.animeId, p.episodeNumber) }
    }

    fun resumeAll() = known.values.filter { DownloadIds.isMain(it.request.id) }.forEach {
        DownloadIds.parse(it.request.id)?.let { p -> resume(p.providerId, p.animeId, p.episodeNumber) }
    }

    /** Cancels everything that has not finished. */
    fun cancelAll() = known.values.filter { DownloadIds.isMain(it.request.id) && it.state != Download.STATE_COMPLETED }.forEach {
        DownloadIds.parse(it.request.id)?.let { p -> remove(p.providerId, p.animeId, p.episodeNumber) }
    }

    companion object {
        const val STOP_REASON_PAUSED = 1
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadsBindsModule {
    @Binds
    @Singleton
    abstract fun bindController(impl: DownloadCoordinator): DownloadController
}
