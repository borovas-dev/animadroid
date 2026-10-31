package com.borovas.animadroid.provider.api

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode

/** A source of anime, mirroring ani-cli's search → episodes → links pipeline. */
interface AnimeProvider {
    val id: String
    val displayName: String

    suspend fun search(query: String, mode: TranslationMode): List<Anime>

    /** Optional enrichment (cover, synopsis) for the details screen. */
    suspend fun details(anime: Anime): Anime = anime

    /** Episodes sorted ascending. */
    suspend fun episodes(anime: Anime, mode: TranslationMode): List<Episode>

    suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet
}

class ProviderException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Thrown when Cloudflare blocks a request; UI should launch the WebView solver for [url]. */
class CloudflareChallengeException(val url: String) : Exception("Cloudflare challenge at $url")

/**
 * Process-wide signal emitted by the app once the in-app Cloudflare solver obtained a clearance,
 * so screens whose request failed with [CloudflareChallengeException] can retry automatically.
 */
object CloudflareSolved {
    private val _events = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val events: kotlinx.coroutines.flow.SharedFlow<Unit> = _events
    fun notifySolved() { _events.tryEmit(Unit) }
}
