package com.borovas.animadroid.core.database

import androidx.room3.Database
import androidx.room3.RoomDatabase

@Database(
    entities = [AnimeEntity::class, EpisodeProgressEntity::class, SearchHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AnimadroidDatabase : RoomDatabase() {
    abstract fun animeDao(): AnimeDao
    abstract fun progressDao(): ProgressDao
    abstract fun searchDao(): SearchDao
}
