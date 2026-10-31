package dev.borovas.animadroid.feature.recommend

import dev.borovas.animadroid.core.model.Anime
import dev.borovas.animadroid.core.model.EpisodeProgress
import dev.borovas.animadroid.core.model.WatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun entry(id: String, title: String, frac: Float, ep: String = "3") =
    WatchEntry(Anime(id, "hianime", title, "c"), EpisodeProgress("hianime", id, ep, (frac * 1000).toLong(), 1000, 0))

class PromptBuilderTest {
    @Test fun includesTitlesProgressAndFavorites() {
        val p = PromptBuilder.user(
            listOf(entry("1", "Frieren", 0.95f), entry("2", "Bleach", 0.3f)),
            listOf(Anime("9", "hianime", "Monster")),
        )
        assertTrue(p.contains("- Frieren (finished episode 3)"))
        assertTrue(p.contains("- Bleach (stopped at 30% of episode 3)"))
        assertTrue(p.contains("Favorites"))
        assertTrue(p.contains("- Monster"))
        assertTrue(p.contains("[{\"title\""))
        assertTrue(p.contains("8 different anime"))
    }

    @Test fun capsHistoryAndSanitizesTitles() {
        val many = (1..100).map { entry("$it", "Show $it", 0.5f) }
        val p = PromptBuilder.user(many, emptyList())
        assertEquals(PromptBuilder.MAX_WATCHED, p.lines().count { it.startsWith("- Show") })
        val evil = PromptBuilder.user(listOf(entry("1", "A \"quoted\"\nnewline " + "x".repeat(200), 0.5f)), emptyList())
        assertFalse(evil.contains("\"quoted\""))
        assertTrue(evil.lines().none { it.startsWith("newline") })
    }

    @Test fun emptyHistoryIsHandled() {
        assertTrue(PromptBuilder.user(emptyList(), emptyList()).contains("(nothing yet)"))
    }
}

class SuggestionParserTest {
    @Test fun cleanArray() {
        val r = SuggestionParser.parse("""[{"title":"Mushishi","reason":"Calm."},{"title":"Monster","reason":"Tense."}]""")
        assertEquals(listOf("Mushishi", "Monster"), r.map { it.title })
        assertEquals("Calm.", r[0].reason)
    }

    @Test fun codeFenceAndProse() {
        val r = SuggestionParser.parse("Sure! Here you go:\n```json\n[{\"title\": \"Haibane Renmei\", \"reason\": \"Gentle.\"}]\n```\nEnjoy!")
        assertEquals(listOf("Haibane Renmei"), r.map { it.title })
    }

    @Test fun proseWithoutFences() {
        val r = SuggestionParser.parse("Recommendations: [{\"title\":\"A\",\"reason\":\"r\"}] hope that helps [1]")
        assertEquals(listOf("A"), r.map { it.title })
    }

    @Test fun trailingCommasAndAltKeys() {
        val r = SuggestionParser.parse("""[{"name":"A","why":"x",},{"title":"B"},]""")
        assertEquals(listOf("A", "B"), r.map { it.title })
        assertEquals("", r[1].reason)
    }

    @Test fun truncatedOutputSalvagesCompleteObjects() {
        val r = SuggestionParser.parse("""[{"title":"A","reason":"has a } brace"},{"title":"B","reason":"ok"},{"title":"C","reas""")
        assertEquals(listOf("A", "B"), r.map { it.title })
        assertEquals("has a } brace", r[0].reason)
    }

    @Test fun dedupesAndDropsBlank() {
        val r = SuggestionParser.parse("""[{"title":"A"},{"title":"a"},{"title":"  "},{"reason":"no title"}]""")
        assertEquals(listOf("A"), r.map { it.title })
    }

    @Test fun garbageGivesEmpty() {
        assertTrue(SuggestionParser.parse("I cannot help with that.").isEmpty())
        assertTrue(SuggestionParser.parse("").isEmpty())
    }
}

class HistorySnapshotTest {
    @Test fun signatureCapturesSignalShowsOnly() {
        val s = HistorySnapshot(listOf(entry("1", "A", 0.95f), entry("2", "B", 0.05f), entry("3", "C", 0.5f)), listOf(Anime("9", "hianime", "F")))
        assertEquals(listOf("hianime:1", "hianime:3", "hianime:9"), s.signature)
        assertEquals(setOf("hianime:1", "hianime:2", "hianime:3", "hianime:9"), s.excludedIds())
    }

    @Test fun meaningfulChangeThreshold() {
        assertFalse(HistorySnapshot.isMeaningfullyChanged(listOf("a", "b"), listOf("a", "b", "c")))
        assertTrue(HistorySnapshot.isMeaningfullyChanged(listOf("a", "b"), listOf("a", "b", "c", "d")))
        assertTrue(HistorySnapshot.isMeaningfullyChanged(listOf("a", "b"), listOf("a", "c")))
    }

    @Test fun normalizeTitle() {
        assertEquals("sousou no frieren", normalizeTitle("Sousou no Frieren (TV)"))
    }
}
