package com.borovas.animadroid.core.database

import com.borovas.animadroid.core.model.DownloadGroup
import com.borovas.animadroid.core.model.DownloadItem
import com.borovas.animadroid.core.model.DownloadRepository
import com.borovas.animadroid.core.model.DownloadState
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val SnapshotJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

fun DownloadEntity.toModel() = DownloadItem(
    providerId = providerId, animeId = animeId, episodeId = episodeId, episodeNumber = episodeNumber, title = title,
    coverUrl = coverUrl, mode = runCatching { TranslationMode.valueOf(mode) }.getOrDefault(TranslationMode.SUB),
    quality = quality, state = runCatching { DownloadState.valueOf(state) }.getOrDefault(DownloadState.FAILED),
    bytes = bytes, percent = percent, createdAt = createdAt, failureReason = failureReason,
    streamSet = streamSetJson?.let { runCatching { SnapshotJson.decodeFromString(StreamSet.serializer(), it) }.getOrNull() },
)

fun DownloadItem.toEntity() = DownloadEntity(
    providerId = providerId, animeId = animeId, episodeNumber = episodeNumber, episodeId = episodeId, title = title,
    coverUrl = coverUrl, mode = mode.name, quality = quality, state = state.name, bytes = bytes, percent = percent,
    createdAt = createdAt, failureReason = failureReason, streamSetJson = streamSet?.let { SnapshotJson.encodeToString(StreamSet.serializer(), it) },
)

/** Pure grouping used by [RoomDownloadRepository.groupedByAnime] (also unit tested). */
fun groupDownloads(items: List<DownloadItem>): List<DownloadGroup> =
    items.groupBy { it.providerId to it.animeId }.map { (_, list) ->
        val first = list.first()
        DownloadGroup(
            providerId = first.providerId, animeId = first.animeId, title = first.title,
            coverUrl = list.firstNotNullOfOrNull { it.coverUrl },
            items = list.sortedWith(compareBy({ it.episodeNumber.toDoubleOrNull() ?: Double.MAX_VALUE }, { it.episodeNumber })),
        )
    }.sortedByDescending { g -> g.items.maxOf { it.createdAt } }

@Singleton
class RoomDownloadRepository @Inject constructor(
    private val db: AnimadroidDatabase,
) : DownloadRepository {
    private val dao get() = db.downloadDao()

    override fun downloads(): Flow<List<DownloadItem>> = dao.all().map { l -> l.map { it.toModel() } }
    override fun groupedByAnime(): Flow<List<DownloadGroup>> = downloads().map(::groupDownloads)
    override fun forAnime(providerId: String, animeId: String): Flow<Map<String, DownloadItem>> =
        dao.forAnime(providerId, animeId).map { l -> l.map { it.toModel() }.associateBy { it.episodeNumber } }

    override fun observe(providerId: String, animeId: String, episodeNumber: String): Flow<DownloadItem?> =
        dao.observe(providerId, animeId, episodeNumber).map { it?.toModel() }

    override suspend fun get(providerId: String, animeId: String, episodeNumber: String): DownloadItem? =
        dao.get(providerId, animeId, episodeNumber)?.toModel()

    override suspend fun getAll(): List<DownloadItem> = dao.getAll().map { it.toModel() }
    override suspend fun upsert(item: DownloadItem) = dao.upsert(item.toEntity())

    override suspend fun updateProgress(
        providerId: String, animeId: String, episodeNumber: String, state: DownloadState, bytes: Long, percent: Float,
        failureReason: String?,
    ) = dao.updateProgress(providerId, animeId, episodeNumber, state.name, bytes, percent, failureReason)

    override suspend fun delete(providerId: String, animeId: String, episodeNumber: String) =
        dao.delete(providerId, animeId, episodeNumber)
}
