package dev.borovas.animadroid.core.database

import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import dev.borovas.animadroid.core.model.Anime
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DatabaseTest {
    private lateinit var db: AnimadroidDatabase
    private lateinit var repo: RoomLibraryRepository

    private fun newDb() = Room.inMemoryDatabaseBuilder<AnimadroidDatabase>()
        .setDriver(AndroidSQLiteDriver()).build()

    @Before fun setUp() {
        db = newDb()
        repo = RoomLibraryRepository(db)
    }

    @After fun tearDown() = db.close()

    private val a = Anime("a1", "hianime", "Alpha", coverUrl = "c")
    private val b = Anime("b1", "hianime", "Beta")

    @Test fun searchesNormalizedDedupedCapped() = runBlocking {
        repo.addSearch("  Naruto ")
        repo.addSearch("naruto")
        assertEquals(listOf("naruto"), repo.recentSearches().first())
        repeat(60) { repo.addSearch("q$it"); Thread.sleep(2) }
        assertEquals(50, repo.recentSearches(100).first().size)
        assertEquals("q59", repo.recentSearches().first().first())
        repo.removeSearch("Q59")
        assertFalse("q59" in repo.recentSearches(100).first())
        repo.clearSearches()
        assertTrue(repo.recentSearches().first().isEmpty())
    }

    @Test fun continueWatchingOrderAndLast() = runBlocking {
        repo.saveProgress(a, "1", 10, 100); Thread.sleep(5)
        repo.saveProgress(a, "2", 20, 100); Thread.sleep(5)
        repo.saveProgress(b, "7", 5, 50)
        var list = repo.continueWatching().first()
        assertEquals(listOf("b1", "a1"), list.map { it.anime.id })
        assertEquals("2", list[1].last.episodeNumber)
        Thread.sleep(5)
        repo.saveProgress(a, "1", 30, 100)
        list = repo.continueWatching().first()
        assertEquals(listOf("a1", "b1"), list.map { it.anime.id })
        assertEquals("1", list[0].last.episodeNumber)
        assertEquals(30, repo.getProgress("hianime", "a1", "1")!!.positionMs)
        assertEquals(2, repo.progressForAnime("hianime", "a1").first().size)
        repo.removeFromHistory("hianime", "a1")
        assertEquals(listOf("b1"), repo.continueWatching().first().map { it.anime.id })
        assertNull(repo.getProgress("hianime", "a1", "1"))
    }

    @Test fun favoritesSurviveProgress() = runBlocking {
        repo.setFavorite(a, true)
        assertTrue(repo.isFavorite("hianime", "a1").first())
        repo.saveProgress(a, "1", 1, 2)
        assertEquals(listOf("a1"), repo.favorites().first().map { it.id })
        assertEquals(1, repo.continueWatching().first().size)
        repo.setFavorite(a, false)
        assertFalse(repo.isFavorite("hianime", "a1").first())
        assertFalse(repo.isFavorite("hianime", "zzz").first())
    }

    @Test fun backupRoundTripAndMerge() = runBlocking {
        repo.saveProgress(a, "1", 10, 100)
        repo.setFavorite(b, true)
        repo.addSearch("x")
        val out = ByteArrayOutputStream()
        BackupManager(db).exportJson(out, """{"s":1}""")
        val bytes = out.toByteArray()

        val db2 = newDb()
        try {
            val res = BackupManager(db2).importJson(ByteArrayInputStream(bytes))
            assertEquals("""{"s":1}""", res.settingsJson)
            val r2 = RoomLibraryRepository(db2)
            assertEquals(10, r2.getProgress("hianime", "a1", "1")!!.positionMs)
            assertEquals(listOf("b1"), r2.favorites().first().map { it.id })
            assertEquals(listOf("x"), r2.recentSearches().first())
            // merge: older incoming must not overwrite newer local
            Thread.sleep(5)
            r2.saveProgress(a, "1", 99, 100)
            BackupManager(db2).importJson(ByteArrayInputStream(bytes))
            assertEquals(99, r2.getProgress("hianime", "a1", "1")!!.positionMs)
        } finally {
            db2.close()
        }
    }

    @Test fun aniCliImport() = runBlocking {
        val text = "5\tabc123\tOne Piece (1000 episodes)\n12\tdef\tBleach\n\nbad line\n"
        val n = BackupManager(db).importAniCliHistory(ByteArrayInputStream(text.toByteArray()), now = 1000)
        assertEquals(2, n)
        val list = repo.continueWatching().first()
        assertEquals(2, list.size)
        val op = list.first { it.anime.id == "abc123" }
        assertEquals("One Piece (1000 episodes)", op.anime.title)
        assertEquals("5", op.last.episodeNumber)
        assertEquals(1, op.last.positionMs)
        assertEquals(1, op.last.durationMs)
        assertTrue(op.last.isFinished)
    }
}
