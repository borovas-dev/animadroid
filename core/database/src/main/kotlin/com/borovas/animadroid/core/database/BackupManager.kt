package com.borovas.animadroid.core.database

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BackupAnime(
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
)

@Serializable
data class BackupProgress(
    val providerId: String,
    val animeId: String,
    val episodeNumber: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Serializable
data class BackupSearch(val query: String, val searchedAt: Long)

@Serializable
data class BackupFile(
    val version: Int = 1,
    val anime: List<BackupAnime> = emptyList(),
    val progress: List<BackupProgress> = emptyList(),
    val searches: List<BackupSearch> = emptyList(),
    /** Opaque settings JSON produced by :core:datastore (optional). */
    val settings: String? = null,
)

data class ImportResult(val anime: Int, val progress: Int, val searches: Int, val settingsJson: String?)

@Singleton
class BackupManager @Inject constructor(private val db: AnimadroidDatabase) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    suspend fun exportJson(out: OutputStream, settingsJson: String? = null) = withContext(Dispatchers.IO) {
        val file = BackupFile(
            anime = db.animeDao().getAll().map {
                BackupAnime(it.providerId, it.animeId, it.title, it.coverUrl, it.synopsis, it.malId, it.subEpisodes,
                    it.dubEpisodes, it.isFavorite, it.favoritedAt, it.lastWatchedAt)
            },
            progress = db.progressDao().getAll().map {
                BackupProgress(it.providerId, it.animeId, it.episodeNumber, it.positionMs, it.durationMs, it.updatedAt)
            },
            searches = db.searchDao().getAll().map { BackupSearch(it.query, it.searchedAt) },
            settings = settingsJson,
        )
        out.write(json.encodeToString(BackupFile.serializer(), file).toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** Merges the backup into the database (newer timestamps win). Result carries the embedded settings blob, if any. */
    suspend fun importJson(input: InputStream): ImportResult = withContext(Dispatchers.IO) {
        val text = input.readBytes().toString(Charsets.UTF_8)
        val file = json.decodeFromString(BackupFile.serializer(), text)
        require(file.version == 1) { "Unsupported backup version ${file.version}" }
        val animeDao = db.animeDao()
        val progressDao = db.progressDao()
        val searchDao = db.searchDao()
        db.useWriterConnection { t ->
            t.immediateTransaction {
                for (b in file.anime) {
                    val incoming = AnimeEntity(b.providerId, b.animeId, b.title, b.coverUrl, b.synopsis, b.malId,
                        b.subEpisodes, b.dubEpisodes, b.isFavorite, b.favoritedAt, b.lastWatchedAt)
                    val existing = animeDao.get(b.providerId, b.animeId)
                    if (existing == null) {
                        animeDao.upsert(incoming)
                    } else {
                        val fav = if ((incoming.favoritedAt ?: -1) > (existing.favoritedAt ?: -1)) incoming else existing
                        val base = if ((incoming.lastWatchedAt ?: -1) > (existing.lastWatchedAt ?: -1)) incoming else existing
                        animeDao.upsert(
                            base.copy(
                                coverUrl = base.coverUrl ?: existing.coverUrl ?: incoming.coverUrl,
                                synopsis = base.synopsis ?: existing.synopsis ?: incoming.synopsis,
                                malId = base.malId ?: existing.malId ?: incoming.malId,
                                isFavorite = fav.isFavorite,
                                favoritedAt = fav.favoritedAt,
                                lastWatchedAt = maxOfNullable(existing.lastWatchedAt, incoming.lastWatchedAt),
                            ),
                        )
                    }
                }
                for (p in file.progress) {
                    val existing = progressDao.get(p.providerId, p.animeId, p.episodeNumber)
                    if (existing == null || p.updatedAt > existing.updatedAt) {
                        progressDao.upsert(EpisodeProgressEntity(p.providerId, p.animeId, p.episodeNumber, p.positionMs, p.durationMs, p.updatedAt))
                    }
                }
                val have = searchDao.getAll().associate { it.query to it.searchedAt }
                for (s in file.searches) {
                    val q = RoomLibraryRepository.normalizeQuery(s.query)
                    if (q.isNotEmpty() && s.searchedAt > (have[q] ?: -1)) searchDao.upsert(SearchHistoryEntity(q, s.searchedAt))
                }
                searchDao.trim(RoomLibraryRepository.MAX_SEARCHES)
            }
        }
        ImportResult(file.anime.size, file.progress.size, file.searches.size, file.settings)
    }

    /**
     * Imports an ani-cli `ani-hsts` file: lines of "ep_no\tanime_id\ttitle". Each becomes a hianime anime with a
     * finished progress row for that episode (existing progress rows are kept). Returns the number of lines imported.
     */
    suspend fun importAniCliHistory(input: InputStream, now: Long = System.currentTimeMillis()): Int =
        withContext(Dispatchers.IO) {
            val lines = input.bufferedReader(Charsets.UTF_8).readLines()
            var count = 0
            db.useWriterConnection { t ->
                t.immediateTransaction {
                    for (line in lines) {
                        val parts = line.trimEnd('\r').split('\t', limit = 3)
                        if (parts.size < 3) continue
                        val ep = parts[0].trim()
                        val id = parts[1].trim()
                        val title = parts[2].trim()
                        if (ep.isEmpty() || id.isEmpty()) continue
                        touchWatched(db, AnimeEntity(ANI_CLI_PROVIDER, id, title.ifEmpty { id }, lastWatchedAt = now), now)
                        db.progressDao().insertIgnore(EpisodeProgressEntity(ANI_CLI_PROVIDER, id, ep, 1, 1, now))
                        count++
                    }
                }
            }
            count
        }

    private fun maxOfNullable(a: Long?, b: Long?): Long? = if (a == null) b else if (b == null) a else maxOf(a, b)

    companion object {
        const val ANI_CLI_PROVIDER = "hianime"
    }
}
