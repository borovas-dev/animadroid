package com.borovas.animadroid.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Progress of a single episode (exact resume position). */
@Serializable
data class EpisodeProgress(
    val providerId: String,
    val animeId: String,
    val episodeNumber: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isFinished: Boolean get() = fraction >= 0.9f
}

/** One row of "Continue watching" (latest progress per show), like ani-cli's ani-hsts. */
@Serializable
data class WatchEntry(val anime: Anime, val last: EpisodeProgress)

/** Implemented in :core:database. */
interface LibraryRepository {
    fun recentSearches(limit: Int = 20): Flow<List<String>>
    suspend fun addSearch(query: String)
    suspend fun removeSearch(query: String)
    suspend fun clearSearches()

    fun continueWatching(): Flow<List<WatchEntry>>
    fun progressForAnime(providerId: String, animeId: String): Flow<List<EpisodeProgress>>
    suspend fun getProgress(providerId: String, animeId: String, episodeNumber: String): EpisodeProgress?
    /** Upserts the anime row (title/cover) and the episode progress. */
    suspend fun saveProgress(anime: Anime, episodeNumber: String, positionMs: Long, durationMs: Long)
    suspend fun removeFromHistory(providerId: String, animeId: String)

    fun favorites(): Flow<List<Anime>>
    fun isFavorite(providerId: String, animeId: String): Flow<Boolean>
    suspend fun setFavorite(anime: Anime, favorite: Boolean)
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class SkipBehavior { AUTO, BUTTON }

/** How ProviderChain picks a stream source: fail over only on errors, or compare the top two sources. */
@Serializable
enum class SourceStrategy { RELIABLE, BEST_QUALITY }

@Serializable
data class AppSettings(
    /** Legacy (pre-0.2) single-provider choice; only read once to migrate to [contentLanguage]. */
    val providerId: String = "hianime",
    /** Language of the content/sources: a code ("en", "pl", "zh", ...) or "auto" = follow [subtitleLanguage] (fallback "en"). */
    val contentLanguage: String = "auto",
    val sourceStrategy: SourceStrategy = SourceStrategy.RELIABLE,
    /** Manual provider order per content language (language -> provider ids); unlisted providers follow in default order. */
    val providerOrder: Map<String, List<String>> = emptyMap(),
    val disabledProviders: Set<String> = emptySet(),
    /** Set once the legacy [providerId] has been mapped to [contentLanguage]. */
    val migratedV2: Boolean = false,
    val mode: TranslationMode = TranslationMode.SUB,
    val quality: QualityPreference = QualityPreference.BEST,
    val aniSkipEnabled: Boolean = true,
    val skipBehavior: SkipBehavior = SkipBehavior.AUTO,
    /** 0..5 seconds, like ani-skip's offset. */
    val skipOffsetSec: Int = 0,
    val autoPlayNext: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val aiRecommendationsEnabled: Boolean = false,
    val downloadWifiOnly: Boolean = true,
    /** Seek-bar thumbnail preview while scrubbing. */
    val scrubPreviewEnabled: Boolean = true,
    /** Also allow the network-hungry frame-decoding preview fallback on metered (mobile) data. Sprite previews are always allowed. */
    val scrubPreviewMobileData: Boolean = false,
    /** ML Kit language code ("zh", "pl", ...) subtitles should be shown in; empty = off. */
    val subtitleLanguage: String = "",
    /** Opt in to on-device machine translation when the provider has no track in [subtitleLanguage]. */
    val translateSubtitles: Boolean = false,
    /**
     * Check GitHub Releases for a newer app version at startup (banner in :app). Default on.
     */
    val checkForUpdates: Boolean = true,
    /** Also offer GitHub pre-release builds (alpha/beta) as updates. */
    val includePrereleases: Boolean = false,
    /** Seek past the intro/opening automatically (once, with an undo prompt). */
    val autoSkipIntro: Boolean = false,
    /** Seek past the outro/ending automatically (once, with an undo prompt). */
    val autoSkipOutro: Boolean = false,
    /** Opt in: resolve the next episode's streams near the end of the current one so it starts instantly. Uses extra data. */
    val prefetchNextEpisode: Boolean = false,
    /** Allow [prefetchNextEpisode] on metered (mobile) networks. */
    val prefetchOnMetered: Boolean = false,
    /** Opt in (experimental): speak the translated subtitles with the system TTS when the show has no dub in the user's language. */
    val dubEnabled: Boolean = false,
)

/** Implemented in :core:datastore. */
interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

/** Implemented in :feature:aniskip. Returns null when not found / disabled. */
interface SkipTimesRepository {
    suspend fun skipTimes(malId: Int, episode: Int): SkipTimes?
}

/** Registry of provider ids, implemented by ProviderChain (:provider:api). */
interface ProviderRegistry {
    val ids: List<String>
}
