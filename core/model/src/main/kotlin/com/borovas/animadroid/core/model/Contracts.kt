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

@Serializable
data class AppSettings(
    val providerId: String = "hianime",
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

/** Registry of providers, implemented in :app via Hilt multibinding. */
interface ProviderRegistry {
    val ids: List<String>
}
