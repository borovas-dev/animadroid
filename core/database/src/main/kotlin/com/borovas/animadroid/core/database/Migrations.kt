package com.borovas.animadroid.core.database

import androidx.room3.migration.Migration
import androidx.sqlite.execSQL

/** v1 -> v2: adds the `download` table (offline downloads). */
val MIGRATION_1_2 = Migration(1, 2) { connection ->
    connection.execSQL(
        "CREATE TABLE IF NOT EXISTS `download` (`providerId` TEXT NOT NULL, `animeId` TEXT NOT NULL, " +
            "`episodeNumber` TEXT NOT NULL, `episodeId` TEXT NOT NULL, `title` TEXT NOT NULL, `coverUrl` TEXT, " +
            "`mode` TEXT NOT NULL, `quality` INTEGER, `state` TEXT NOT NULL, `bytes` INTEGER NOT NULL, " +
            "`percent` REAL NOT NULL, `createdAt` INTEGER NOT NULL, `streamSetJson` TEXT, " +
            "PRIMARY KEY(`providerId`, `animeId`, `episodeNumber`))",
    )
}

/** v2 -> v3: stores why a download failed (or was paused by the system). */
val MIGRATION_2_3 = Migration(2, 3) { connection ->
    connection.execSQL("ALTER TABLE `download` ADD COLUMN `failureReason` TEXT")
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
