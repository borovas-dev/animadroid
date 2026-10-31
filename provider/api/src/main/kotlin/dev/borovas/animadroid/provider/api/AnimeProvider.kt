package dev.borovas.animadroid.provider.api

import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.Episode
import dev.borovas.animadroid.core.model.StreamSet
import dev.borovas.animadroid.core.model.TranslationMode

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
