package com.borovas.animadroid.core.database

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import com.borovas.animadroid.core.model.DownloadItem
import com.borovas.animadroid.core.model.DownloadState
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.model.SubtitleTrack
import com.borovas.animadroid.core.model.TranslationMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DownloadTest {
    private lateinit var db: AnimadroidDatabase
    private lateinit var repo: RoomDownloadRepository

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        File.createTempFile("animadroid-migration", ".db").apply { delete(); deleteOnExit() },
        AndroidSQLiteDriver(),
        AnimadroidDatabase::class,
        { AnimadroidDatabase_Impl() },
    )

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder<AnimadroidDatabase>().setDriver(AndroidSQLiteDriver()).build()
        repo = RoomDownloadRepository(db)
    }

    @After fun tearDown() = db.close()

    private val set = StreamSet(
        masterUrl = null,
        variants = listOf(StreamVariant(720, "https://cdn/720.m3u8")),
        headers = mapOf("Referer" to "https://site/"),
        externalAudioUrl = "https://cdn/audio.m3u8",
        subtitles = listOf(SubtitleTrack("https://cdn/en.vtt", "en", "English", true)),
    )

    private fun item(anime: String, ep: String, created: Long, state: DownloadState = DownloadState.QUEUED, cover: String? = null) =
        DownloadItem(
            "hianime", anime, "id-$ep", ep, "Title $anime", cover, TranslationMode.SUB, 720, state, 0, 0f, created, set,
        )

    @Test fun roundTripPreservesSnapshot() = runBlocking {
        repo.upsert(item("a", "1", 10))
        val got = repo.get("hianime", "a", "1")!!
        assertEquals(set, got.streamSet)
        assertEquals(DownloadState.QUEUED, got.state)
        assertEquals(TranslationMode.SUB, got.mode)
        assertNull(repo.get("hianime", "a", "2"))
    }

    @Test fun progressUpdateOnlyTouchesExistingRows() = runBlocking {
        repo.upsert(item("a", "1", 10))
        repo.updateProgress("hianime", "a", "1", DownloadState.DOWNLOADING, 500, 0.25f)
        repo.updateProgress("hianime", "a", "99", DownloadState.DOWNLOADING, 500, 0.25f)
        val got = repo.get("hianime", "a", "1")!!
        assertEquals(DownloadState.DOWNLOADING, got.state)
        assertEquals(500, got.bytes)
        assertEquals(0.25f, got.percent, 0f)
        assertNull(repo.get("hianime", "a", "99"))
        assertNotNull(got.streamSet) // snapshot untouched
    }

    @Test fun groupedByAnimeSortsEpisodesNumericallyAndGroupsNewestFirst() = runBlocking {
        repo.upsert(item("a", "10", 1))
        repo.upsert(item("a", "2", 2, cover = "c"))
        repo.upsert(item("b", "1", 50))
        val groups = repo.groupedByAnime().first()
        assertEquals(listOf("b", "a"), groups.map { it.animeId })
        assertEquals(listOf("2", "10"), groups[1].items.map { it.episodeNumber })
        assertEquals("c", groups[1].coverUrl)
    }

    @Test fun perAnimeAndObserveFlows() = runBlocking {
        repo.upsert(item("a", "1", 1, DownloadState.COMPLETED))
        repo.upsert(item("a", "2", 2))
        repo.upsert(item("b", "1", 3))
        val map = repo.forAnime("hianime", "a").first()
        assertEquals(setOf("1", "2"), map.keys)
        assertTrue(map.getValue("1").isPlayable)
        assertFalse(map.getValue("2").isPlayable)
        assertEquals(DownloadState.COMPLETED, repo.observe("hianime", "a", "1").first()!!.state)
        repo.delete("hianime", "a", "1")
        assertNull(repo.observe("hianime", "a", "1").first())
        assertEquals(2, repo.downloads().first().size)
    }

    @Test fun migration1To2CreatesDownloadTableAndKeepsData() = runBlocking {
        helper.createDatabase(1).apply {
            execSQL("INSERT INTO search_history (`query`, searchedAt) VALUES ('naruto', 5)")
            close()
        }
        val conn = helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2))
        conn.prepare("SELECT `query` FROM search_history").use { st ->
            assertTrue(st.step())
            assertEquals("naruto", st.getText(0))
        }
        conn.execSQL(
            "INSERT INTO download (providerId, animeId, episodeNumber, episodeId, title, mode, state, bytes, percent, createdAt) " +
                "VALUES ('p','a','1','e','T','SUB','QUEUED',0,0.0,1)",
        )
        conn.prepare("SELECT COUNT(*) FROM download").use { st ->
            assertTrue(st.step())
            assertEquals(1L, st.getLong(0))
        }
        conn.close()
    }

    @Test fun migration2To3AddsFailureReasonKeepingRows() = runBlocking {
        helper.createDatabase(2).apply {
            execSQL(
                "INSERT INTO download (providerId, animeId, episodeNumber, episodeId, title, mode, state, bytes, percent, createdAt) " +
                    "VALUES ('p','a','1','e','T','SUB','FAILED',5,0.5,1)",
            )
            close()
        }
        val conn = helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3))
        conn.prepare("SELECT state, bytes, failureReason FROM download").use { st ->
            assertTrue(st.step())
            assertEquals("FAILED", st.getText(0))
            assertEquals(5L, st.getLong(1))
            assertTrue(st.isNull(2))
        }
        conn.execSQL("UPDATE download SET failureReason = 'HTTP 403'")
        conn.close()
    }

    @Test fun failureReasonRoundTripsAndIsClearedByProgressUpdate() = runBlocking {
        repo.upsert(item("a", "1", 1))
        repo.updateProgress("hianime", "a", "1", DownloadState.FAILED, 0, 0f, "Server returned HTTP 403")
        assertEquals("Server returned HTTP 403", repo.get("hianime", "a", "1")!!.failureReason)
        repo.updateProgress("hianime", "a", "1", DownloadState.DOWNLOADING, 10, 0.1f)
        assertNull(repo.get("hianime", "a", "1")!!.failureReason)
    }
}
