package com.borovas.animadroid.core.sync

import com.borovas.animadroid.core.model.Anime
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface SyncScheduler { fun scheduleRetry() }

const val REDIRECT_ANILIST = "animadroid://auth/anilist"
const val REDIRECT_MAL = "animadroid://auth/mal"

/** Fraction of an episode after which it counts as watched. */
const val WATCHED_FRACTION = 0.85f

class SyncRepositoryImpl(
    private val config: SyncConfig,
    private val anilist: AniListClient,
    private val mal: MalClient,
    private val tokens: TokenStore,
    private val queue: QueueStore,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SyncRepository {
    private val _state = MutableStateFlow(SyncState())
    override val state: StateFlow<SyncState> = _state
    private val mutex = Mutex()
    private var pendingMalVerifier: String? = null
    private var pendingMalState: String? = null

    init { scope.launch { refreshState() } }

    suspend fun refreshState(error: String? = _state.value.error, syncing: Boolean = false) {
        _state.value = SyncState(
            services = SyncService.entries.associateWith {
                val t = tokens.get(it)
                ServiceState(config.isConfigured(it), t != null, t?.username)
            },
            pending = queue.load().size, syncing = syncing, error = error,
        )
    }

    override fun authorizeUrl(service: SyncService): String? {
        if (!config.isConfigured(service)) return null
        return when (service) {
            SyncService.ANILIST -> AniListClient.authorizeUrl(config.anilistClientId)
            SyncService.MAL -> {
                val verifier = Pkce.verifier().also { pendingMalVerifier = it }
                val st = Pkce.state().also { pendingMalState = it }
                mal.authorizeUrl(config.malClientId, Pkce.challenge(verifier), st, REDIRECT_MAL)
            }
        }
    }

    override suspend fun handleRedirect(uri: String): Boolean {
        val u = runCatching { URI(uri) }.getOrNull() ?: return false
        if (u.scheme != "animadroid" || u.host != "auth") return false
        val params = parse(u.rawFragment) + parse(u.rawQuery)
        val error = params["error"]
        try {
            when (u.path) {
                "/anilist" -> {
                    val token = params["access_token"] ?: throw IOException(error ?: "no token")
                    val ttl = params["expires_in"]?.toLongOrNull()
                    val name = runCatching { anilist.viewerName(token) }.getOrNull()
                    tokens.put(SyncService.ANILIST, Tokens(token, null, ttl?.let { clock() + it * 1000 } ?: 0, name))
                }
                "/mal" -> {
                    val verifier = pendingMalVerifier
                    if (error != null || verifier == null || params["state"] != pendingMalState) throw IOException(error ?: "invalid state")
                    val code = params["code"] ?: throw IOException("no code")
                    val t = mal.exchange(config.malClientId, code, verifier, REDIRECT_MAL)
                    val name = runCatching { mal.userName(t.access_token) }.getOrNull()
                    tokens.put(SyncService.MAL, Tokens(t.access_token, t.refresh_token, clock() + t.expires_in * 1000, name))
                    pendingMalVerifier = null; pendingMalState = null
                }
                else -> return false
            }
            refreshState(error = null)
        } catch (e: Exception) {
            refreshState(error = e.message ?: "sign-in failed")
        }
        return true
    }

    private fun parse(s: String?): Map<String, String> = s.orEmpty().split('&').filter { '=' in it }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }

    override suspend fun disconnect(service: SyncService) {
        mutex.withLock {
            tokens.clear(service)
            queue.save(queue.load().mapNotNull { it.copy(services = it.services - service).takeIf { i -> i.services.isNotEmpty() } })
        }
        refreshState()
    }

    override fun onEpisodeWatched(anime: Anime, episodeNumber: String, totalEpisodes: Int?) {
        val malId = anime.malId ?: return
        val ep = episodeNumber.toDoubleOrNull()?.takeIf { it >= 1 && it % 1.0 == 0.0 }?.toInt() ?: return
        scope.launch {
            record(malId, ep, totalEpisodes)
            syncNow()
        }
    }

    /** Queues [episode] of [malId] for every connected service, coalescing with an existing entry for the show. */
    suspend fun record(malId: Int, episode: Int, total: Int?) {
        mutex.withLock {
            val services = SyncService.entries.filter { tokens.get(it) != null }.toSet()
            if (services.isEmpty()) return
            val items = queue.load().toMutableList()
            val i = items.indexOfFirst { it.malId == malId }
            if (i >= 0) {
                val old = items[i]
                items[i] = PendingUpdate(malId, maxOf(old.episode, episode), total ?: old.total, old.services + services)
            } else {
                items += PendingUpdate(malId, episode, total, services)
            }
            queue.save(items)
        }
        refreshState()
    }

    private sealed interface Outcome {
        data object Done : Outcome
        data class Retry(val reason: String) : Outcome
        data class Drop(val reason: String?) : Outcome
    }

    override suspend fun syncNow() {
        var error: String? = null
        var retry = false
        mutex.withLock {
            _state.update { it.copy(syncing = true) }
            val out = mutableListOf<PendingUpdate>()
            for (item in queue.load()) {
                val remaining = item.services.toMutableSet()
                for (svc in item.services) {
                    when (val r = deliver(svc, item)) {
                        Outcome.Done -> remaining -= svc
                        is Outcome.Drop -> { remaining -= svc; if (r.reason != null) error = r.reason }
                        is Outcome.Retry -> { retry = true; error = r.reason }
                    }
                }
                if (remaining.isNotEmpty()) out += item.copy(services = remaining)
            }
            queue.save(out)
        }
        refreshState(error = error)
        if (retry) scheduler.scheduleRetry()
    }

    private suspend fun deliver(svc: SyncService, item: PendingUpdate): Outcome {
        if (tokens.get(svc) == null) return Outcome.Drop(null)
        return try {
            when (svc) {
                SyncService.ANILIST -> {
                    val t = tokens.get(svc)!!.access
                    val e = anilist.lookup(t, item.malId) ?: return Outcome.Drop(null)
                    if ((e.progress ?: 0) < item.episode) {
                        val total = item.total ?: e.episodes
                        anilist.save(t, e.mediaId, item.episode, total != null && item.episode >= total)
                    }
                }
                SyncService.MAL -> malAuthed { t ->
                    val e = mal.entry(t, item.malId) ?: return@malAuthed Outcome.Drop(null)
                    if ((e.watched ?: 0) < item.episode) {
                        val total = item.total ?: e.episodes
                        mal.update(t, item.malId, item.episode, total != null && item.episode >= total)
                    }
                    Outcome.Done
                }.let { return it }
            }
            Outcome.Done
        } catch (e: SyncHttpException) {
            when {
                e.unauthorized -> { tokens.clear(svc); Outcome.Drop("${svc.name}: signed out (token rejected)") }
                e.transient -> Outcome.Retry(e.message.orEmpty())
                else -> Outcome.Drop(e.message)
            }
        } catch (e: IOException) {
            Outcome.Retry(e.message ?: "network error")
        }
    }

    /** Runs [block] with a valid MAL token: refreshes proactively when expired, and once more on a 401. */
    private suspend fun <T> malAuthed(block: suspend (String) -> T): T {
        var t = malToken(force = false)
        return try {
            block(t)
        } catch (e: SyncHttpException) {
            if (!e.unauthorized) throw e
            t = malToken(force = true)
            block(t)
        }
    }

    private suspend fun malToken(force: Boolean): String {
        val cur = tokens.get(SyncService.MAL) ?: throw SyncHttpException(401, "not signed in")
        if (!force && (cur.expiresAt == 0L || clock() < cur.expiresAt - 60_000)) return cur.access
        val refresh = cur.refresh ?: throw SyncHttpException(401, "no refresh token")
        val n = try {
            mal.refresh(config.malClientId, refresh)
        } catch (e: SyncHttpException) {
            // MAL answers 400/401 for a revoked or expired refresh token.
            throw if (e.code == 400 || e.code == 401) SyncHttpException(401, "refresh rejected") else e
        }
        tokens.put(SyncService.MAL, cur.copy(access = n.access_token, refresh = n.refresh_token ?: refresh, expiresAt = clock() + n.expires_in * 1000))
        return n.access_token
    }
}
