package dev.borovas.animadroid.core.database

import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.EpisodeProgress
import dev.borovas.animadroid.core.model.LibraryRepository
import dev.borovas.animadroid.core.model.WatchEntry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class RoomLibraryRepository @Inject constructor(
    private val db: AnimadroidDatabase,
) : LibraryRepository {
    private val animeDao get() = db.animeDao()
    private val progressDao get() = db.progressDao()
    private val searchDao get() = db.searchDao()

    override fun recentSearches(limit: Int): Flow<List<String>> = searchDao.recent(limit)

    override suspend fun addSearch(query: String) {
        val q = normalizeQuery(query)
        if (q.isEmpty()) return
        db.useWriterConnection { t ->
            t.immediateTransaction {
                searchDao.upsert(SearchHistoryEntity(q, System.currentTimeMillis()))
                searchDao.trim(MAX_SEARCHES)
            }
        }
    }

    override suspend fun removeSearch(query: String) = searchDao.delete(normalizeQuery(query))
    override suspend fun clearSearches() = searchDao.clear()

    override fun continueWatching(): Flow<List<WatchEntry>> =
        combine(animeDao.watched(), progressDao.latestPerAnime()) { animes, latest ->
            val byKey = latest.groupBy { it.providerId to it.animeId }
                .mapValues { (_, rows) -> rows.maxBy { it.updatedAt } }
            animes.mapNotNull { a ->
                byKey[a.providerId to a.animeId]?.let { WatchEntry(a.toModel(), it.toModel()) }
            }
        }.distinctUntilChanged()

    override fun progressForAnime(providerId: String, animeId: String): Flow<List<EpisodeProgress>> =
        progressDao.forAnime(providerId, animeId).map { rows -> rows.map { it.toModel() } }

    override suspend fun getProgress(providerId: String, animeId: String, episodeNumber: String): EpisodeProgress? =
        progressDao.get(providerId, animeId, episodeNumber)?.toModel()

    override suspend fun saveProgress(anime: Anime, episodeNumber: String, positionMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        val a = anime.toEntity()
        db.useWriterConnection { t ->
            t.immediateTransaction {
                touchWatched(db, a, now)
                progressDao.upsert(EpisodeProgressEntity(anime.providerId, anime.id, episodeNumber, positionMs, durationMs, now))
            }
        }
    }

    override suspend fun removeFromHistory(providerId: String, animeId: String) {
        db.useWriterConnection { t ->
            t.immediateTransaction {
                progressDao.deleteForAnime(providerId, animeId)
                animeDao.clearWatched(providerId, animeId)
            }
        }
    }

    override fun favorites(): Flow<List<Anime>> = animeDao.favorites().map { rows -> rows.map { it.toModel() } }

    override fun isFavorite(providerId: String, animeId: String): Flow<Boolean> =
        animeDao.isFavorite(providerId, animeId).map { it ?: false }.distinctUntilChanged()

    override suspend fun setFavorite(anime: Anime, favorite: Boolean) {
        val a = anime.toEntity()
        val now = System.currentTimeMillis()
        db.useWriterConnection { t ->
            t.immediateTransaction {
                animeDao.insertIgnore(a)
                animeDao.updateFavorite(a.providerId, a.animeId, favorite, if (favorite) now else null, a.title, a.coverUrl, a.synopsis)
            }
        }
    }

    companion object {
        const val MAX_SEARCHES = 50
        fun normalizeQuery(q: String) = q.trim().lowercase()
    }
}

/** Inserts the anime if missing, then refreshes its metadata and last-watched time. Call inside a transaction. */
internal suspend fun touchWatched(db: AnimadroidDatabase, a: AnimeEntity, watchedAt: Long) {
    val dao = db.animeDao()
    dao.insertIgnore(a.copy(lastWatchedAt = watchedAt))
    dao.updateMeta(a.providerId, a.animeId, a.title, a.coverUrl, a.synopsis, a.malId, a.subEpisodes, a.dubEpisodes, watchedAt)
}
