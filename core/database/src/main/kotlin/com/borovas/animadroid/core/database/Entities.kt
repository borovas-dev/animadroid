package com.borovas.animadroid.core.database

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.EpisodeProgress
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Entity(tableName = "anime", primaryKeys = ["providerId", "animeId"])
data class AnimeEntity(
    val providerId: String,
    val animeId: String,
    val title: String,
    val coverUrl: String? = null,
    val synopsis: String? = null,
    val malId: Int? = null,
    val subEpisodes: Int? = null,
    val dubEpisodes: Int? = null,
    val isFavorite: Boolean = false,
    val favoritedAt: Long? = null,
    val lastWatchedAt: Long? = null,
    /** JSON object {providerId: animeId} of [Anime.alternates]; null when none. */
    val alternatesJson: String? = null,
)

@Entity(tableName = "episode_progress", primaryKeys = ["providerId", "animeId", "episodeNumber"])
data class EpisodeProgressEntity(
    val providerId: String,
    val animeId: String,
    val episodeNumber: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long,
)

/** One offline download (episode granularity). [streamSetJson] is the serialized StreamSet snapshot. */
@Entity(tableName = "download", primaryKeys = ["providerId", "animeId", "episodeNumber"])
data class DownloadEntity(
    val providerId: String,
    val animeId: String,
    val episodeNumber: String,
    val episodeId: String,
    val title: String,
    val coverUrl: String? = null,
    val mode: String = "SUB",
    val quality: Int? = null,
    val state: String,
    val bytes: Long = 0,
    val percent: Float = 0f,
    val createdAt: Long,
    val streamSetJson: String? = null,
    val failureReason: String? = null,
)

fun AnimeEntity.toModel() = Anime(
    id = animeId, providerId = providerId, title = title, coverUrl = coverUrl, synopsis = synopsis,
    subEpisodes = subEpisodes, dubEpisodes = dubEpisodes, malId = malId,
    alternates = decodeAlternates(alternatesJson),
)

fun Anime.toEntity() = AnimeEntity(
    providerId = providerId, animeId = id, title = title, coverUrl = coverUrl, synopsis = synopsis,
    malId = malId, subEpisodes = subEpisodes, dubEpisodes = dubEpisodes,
    alternatesJson = encodeAlternates(alternates),
)

fun EpisodeProgressEntity.toModel() = EpisodeProgress(providerId, animeId, episodeNumber, positionMs, durationMs, updatedAt)

private val alternatesJsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

internal fun encodeAlternates(map: Map<String, String>): String? =
    map.takeIf { it.isNotEmpty() }?.let { alternatesJsonFormat.encodeToString<Map<String, String>>(it) }

internal fun decodeAlternates(json: String?): Map<String, String> =
    json?.let { runCatching { alternatesJsonFormat.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap()
