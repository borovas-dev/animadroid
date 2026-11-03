package com.borovas.animadroid.core.sync

import com.borovas.animadroid.core.model.Anime
import kotlinx.coroutines.flow.StateFlow

enum class SyncService { ANILIST, MAL }

/** OAuth client ids, injected from BuildConfig (Gradle properties ANILIST_CLIENT_ID / MAL_CLIENT_ID). Blank = not configured. */
data class SyncConfig(val anilistClientId: String = "", val malClientId: String = "") {
    fun clientId(service: SyncService) = if (service == SyncService.ANILIST) anilistClientId else malClientId
    fun isConfigured(service: SyncService) = clientId(service).isNotBlank()
}

data class ServiceState(val configured: Boolean = false, val connected: Boolean = false, val username: String? = null)

data class SyncState(
    val services: Map<SyncService, ServiceState> = SyncService.entries.associateWith { ServiceState() },
    /** Queued (not yet delivered) updates. */
    val pending: Int = 0,
    val syncing: Boolean = false,
    /** Last failure, human readable (not localized); null when the last run was fine. */
    val error: String? = null,
)

interface SyncRepository {
    val state: StateFlow<SyncState>

    /** URL to open in a Custom Tab, or null when [service] is not configured. */
    fun authorizeUrl(service: SyncService): String?

    /** Handles `animadroid://auth/...` redirects. Returns true when [uri] was an auth redirect (successful or not). */
    suspend fun handleRedirect(uri: String): Boolean

    suspend fun disconnect(service: SyncService)

    /** Fire-and-forget: queue the update for every connected service and try to deliver it. [episodeNumber] is the provider's number string. */
    fun onEpisodeWatched(anime: Anime, episodeNumber: String, totalEpisodes: Int?)

    /** Retries everything queued. */
    suspend fun syncNow()
}
