package com.borovas.animadroid.core.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AnimeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(anime: AnimeEntity): Long

    @Upsert
    suspend fun upsert(anime: AnimeEntity)

    @Query("SELECT * FROM anime WHERE providerId = :providerId AND animeId = :animeId")
    suspend fun get(providerId: String, animeId: String): AnimeEntity?

    @Query("SELECT * FROM anime")
    suspend fun getAll(): List<AnimeEntity>

    @Query(
        "UPDATE anime SET title = :title, coverUrl = COALESCE(:coverUrl, coverUrl), synopsis = COALESCE(:synopsis, synopsis), " +
            "malId = COALESCE(:malId, malId), subEpisodes = COALESCE(:sub, subEpisodes), dubEpisodes = COALESCE(:dub, dubEpisodes), " +
            "lastWatchedAt = :watchedAt WHERE providerId = :providerId AND animeId = :animeId",
    )
    suspend fun updateMeta(
        providerId: String, animeId: String, title: String, coverUrl: String?, synopsis: String?,
        malId: Int?, sub: Int?, dub: Int?, watchedAt: Long,
    )

    @Query("UPDATE anime SET lastWatchedAt = NULL WHERE providerId = :providerId AND animeId = :animeId")
    suspend fun clearWatched(providerId: String, animeId: String)

    @Query(
        "UPDATE anime SET isFavorite = :favorite, favoritedAt = :at, title = :title, coverUrl = COALESCE(:coverUrl, coverUrl), " +
            "synopsis = COALESCE(:synopsis, synopsis) WHERE providerId = :providerId AND animeId = :animeId",
    )
    suspend fun updateFavorite(
        providerId: String, animeId: String, favorite: Boolean, at: Long?, title: String, coverUrl: String?, synopsis: String?,
    )

    @Query("SELECT * FROM anime WHERE lastWatchedAt IS NOT NULL ORDER BY lastWatchedAt DESC")
    fun watched(): Flow<List<AnimeEntity>>

    @Query("SELECT * FROM anime WHERE isFavorite = 1 ORDER BY favoritedAt DESC")
    fun favorites(): Flow<List<AnimeEntity>>

    @Query("SELECT isFavorite FROM anime WHERE providerId = :providerId AND animeId = :animeId")
    fun isFavorite(providerId: String, animeId: String): Flow<Boolean?>
}

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(progress: EpisodeProgressEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(progress: EpisodeProgressEntity): Long

    @Query("SELECT * FROM episode_progress WHERE providerId = :providerId AND animeId = :animeId")
    fun forAnime(providerId: String, animeId: String): Flow<List<EpisodeProgressEntity>>

    @Query("SELECT * FROM episode_progress WHERE providerId = :providerId AND animeId = :animeId AND episodeNumber = :episode")
    suspend fun get(providerId: String, animeId: String, episode: String): EpisodeProgressEntity?

    @Query("SELECT * FROM episode_progress")
    suspend fun getAll(): List<EpisodeProgressEntity>

    /** Most recently updated progress row of each anime (ties possible; callers dedupe). */
    @Query(
        "SELECT * FROM episode_progress p WHERE updatedAt = (SELECT MAX(updatedAt) FROM episode_progress q " +
            "WHERE q.providerId = p.providerId AND q.animeId = p.animeId)",
    )
    fun latestPerAnime(): Flow<List<EpisodeProgressEntity>>

    @Query("DELETE FROM episode_progress WHERE providerId = :providerId AND animeId = :animeId")
    suspend fun deleteForAnime(providerId: String, animeId: String)
}

@Dao
interface SearchDao {
    @Upsert
    suspend fun upsert(entry: SearchHistoryEntity)

    @Query("SELECT * FROM search_history")
    suspend fun getAll(): List<SearchHistoryEntity>

    @Query("SELECT `query` FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<String>>

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    @Query("DELETE FROM search_history WHERE `query` NOT IN (SELECT `query` FROM search_history ORDER BY searchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}
