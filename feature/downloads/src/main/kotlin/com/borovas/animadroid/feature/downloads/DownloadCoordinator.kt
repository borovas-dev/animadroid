@file:OptIn(UnstableApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.content.Context
import androidx.core.net.toUri
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
    private val notifier: DownloadNotifier,
) : DownloadController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Latest known state of every Media3 download part (including finished ones), by part id. */
    private val known = ConcurrentHashMap<String, Download>()
    /** Human-readable failure text per failed part (Media3 only hands the exception to the listener). */
    private val failures = ConcurrentHashMap<String, String>()

    /** Terminal state we already posted a notification for, per episode key (dedupes multi-part episodes). */
    private val notified = ConcurrentHashMap<String, DownloadState>()

    @Volatile private var wifiOnly = true
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
            val prev = known.put(download.request.id, download)
            if (download.state == Download.STATE_FAILED) {
                failures[download.request.id] = DownloadPolicy.describeFailure(finalException)
            } else {
                failures.remove(download.request.id)
            }
            syncKey(DownloadIds.keyOf(download.request.id), notify = prev?.state != download.state)
            ensureTicker()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            known.remove(download.request.id)
            failures.remove(download.request.id)
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
            if (waitingForRequirements) notifier.waitingForWifi(wifiOnly) else notifier.clearWaiting()
        }
    }

    private fun createManager(): DownloadManager {
        val m = DownloadManager(context, DefaultDownloadIndex(databaseProvider), HeaderAwareDownloaderFactory(sources))
        m.maxParallelDownloads = 2
        m.minRetryCount = 3
        m.requirements = DownloadPolicy.requirementsFor(wifiOnly = true) // safe default until the setting is read
        m.addListener(listener)
        // DownloadManager is created PAUSED; DownloadService.onCreate() resumes it. When the process is restarted
        // headless by [DownloadWatchdogService] (no foreground service is allowed to start from the background) no
        // service exists, so without this the restored downloads would sit "Queued" forever.
        m.resumeDownloads()
        // A "Waiting for Wi-Fi" notification from the previous process would never be cleared otherwise (the new
        // manager starts with waitingForRequirements=false and only reports changes); it is re-posted if still true.
        notifier.clearWaiting()
        m.downloadIndex.getDownloads().use { c -> while (c.moveToNext()) c.download.let { known[it.request.id] = it } }
        scope.launch {
            settings.settings.map { it.downloadWifiOnly }.distinctUntilChanged().collect { wifiOnly ->
                this@DownloadCoordinator.wifiOnly = wifiOnly
                // Applied live; DownloadManager also re-registers the service's restart job for the new requirements.
                withContext(Dispatchers.Main) { m.requirements = DownloadPolicy.requirementsFor(wifiOnly) }
            }
        }
        reconcileJob = scope.launch { reconcile() }
        return m
    }

    /**
     * Cheap entry point for Application.onCreate: creates the manager off the critical path so persisted
     * downloads are picked up (and the service restarted if work is pending) without opening the Downloads screen.
     */
    fun start() {
        scope.launch(Dispatchers.Main) { manager }
    }

    /** Called from [AnimadroidDownloadService.onTimeout]: pause everything active, tell the user, let the service stop. */
    fun pauseForSystemTimeout() {
        manager.currentDownloads
            .filter { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }
            .forEach { manager.setStopReason(it.request.id, DownloadPolicy.STOP_REASON_SYSTEM) }
        notifier.pausedBySystem()
    }

    /** Clears the system stop reason (app came to foreground, or the user tapped the notification). */
    fun resumeSystemPaused() {
        scope.launch(Dispatchers.Main) {
            val paused = manager.currentDownloads.filter { it.state == Download.STATE_STOPPED && it.stopReason == DownloadPolicy.STOP_REASON_SYSTEM }
            notifier.clearPausedBySystem()
            if (paused.isEmpty()) return@launch
            paused.forEach { manager.setStopReason(it.request.id, Download.STOP_REASON_NONE) }
            startService()
        }
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
                    repository.updateProgress(
                        item.providerId, item.animeId, item.episodeNumber, DownloadState.FAILED, item.bytes, item.percent,
                        "Interrupted before the download could start",
                    )
                }
            } else {
                parts.forEach { if (it.state == Download.STATE_FAILED) failures.putIfAbsent(it.request.id, item.failureReason ?: "Download failed") }
                DownloadAggregator.aggregate(parts.map(::partState))?.let { notified[key] = it.state }
                syncKey(key)
                if (parts.any { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING }) pending = true
            }
        }
        if (pending) {
            ensureTicker()
            withContext(Dispatchers.Main) { startService() }
        }
    }

    /** True while a [DownloadWatchdogService] job hosts the transfers; the ticker must not re-arm (it would stop that job). */
    @Volatile var hostedByJob = false
        private set

    /**
     * Used by the watchdog job: waits until the manager exists and nothing is queued/downloading (or everything is
     * waiting for requirements). Keeping the job alive keeps this process alive without a foreground service, which
     * Android 12+ forbids starting from the background.
     */
    suspend fun awaitIdle() {
        hostedByJob = true
        try {
            withContext(Dispatchers.Main) { manager }
            reconcileJob?.join()
            while (true) {
                val busy = withContext(Dispatchers.Main) {
                    !manager.isWaitingForRequirements && manager.currentDownloads.any {
                        it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_RESTARTING
                    }
                }
                if (!busy) return
                delay(2000)
            }
        } finally {
            hostedByJob = false
        }
    }

    private fun startService() {
        runCatching { androidx.media3.exoplayer.offline.DownloadService.start(context, AnimadroidDownloadService::class.java) }
    }

    private fun partsOf(key: String): List<Download> = known.values.filter { DownloadIds.keyOf(it.request.id) == key }

    private fun partState(d: Download) = PartState(
        d.request.id, d.state, d.bytesDownloaded, d.percentDownloaded, d.stopReason, failures[d.request.id],
    )

    private fun syncKey(key: String, notify: Boolean = false) {
        val agg = DownloadAggregator.aggregate(partsOf(key).map(::partState)) ?: return
        val p = DownloadIds.parse(key) ?: return
        val terminal = agg.state == DownloadState.COMPLETED || agg.state == DownloadState.FAILED
        val announce = notify && terminal && notified.put(key, agg.state) != agg.state
        if (!terminal) notified.remove(key)
        scope.launch {
            repository.updateProgress(p.providerId, p.animeId, p.episodeNumber, agg.state, agg.bytes, agg.percent, agg.reason)
            if (announce) {
                val row = repository.get(p.providerId, p.animeId, p.episodeNumber) ?: return@launch
                if (agg.state == DownloadState.COMPLETED) notifier.completed(key, row.title, row.episodeNumber)
                else notifier.failed(key, row.title, row.episodeNumber, agg.reason ?: "Download failed")
            }
        }
    }

    /** Media3 does not notify listeners on byte progress; poll the live downloads once a second while any is active. */
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            var lastArm = 0L
            while (true) {
                val current = withContext(Dispatchers.Main) { manager.currentDownloads }
                current.forEach { known[it.request.id] = it }
                current.map { DownloadIds.keyOf(it.request.id) }.distinct().forEach(::syncKey)
                if (current.none { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }) {
                    if (!hostedByJob) DownloadWatchdog.cancel(context)
                    break
                }
                val now = System.currentTimeMillis()
                if (!hostedByJob && now - lastArm >= DownloadWatchdog.REARM_INTERVAL_MS) {
                    DownloadWatchdog.arm(context, wifiOnly)
                    lastArm = now
                }
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
                DownloadRequest.Builder(part.id, part.url.toUri())
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
            val reason = "Could not start: ${e.message ?: "unknown error"}"
            repository.updateProgress(anime.providerId, anime.id, episode.number, DownloadState.FAILED, 0, 0f, reason)
            notifier.failed(key, anime.title, episode.number, reason)
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
        const val STOP_REASON_PAUSED = DownloadPolicy.STOP_REASON_USER
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadsBindsModule {
    @Binds
    @Singleton
    abstract fun bindController(impl: DownloadCoordinator): DownloadController
}
