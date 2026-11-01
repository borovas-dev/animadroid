package com.borovas.animadroid.feature.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPlanTest {
    @Test fun wrapperTagsAreKeptAroundTranslation() {
        val plan = CuePlan.of("<i>Line one\nline two</i>")
        assertEquals(1, plan.units.size)
        assertEquals("Line one line two", plan.units[0].body)
        assertEquals("<i>BONJOUR</i>", plan.render(listOf("BONJOUR")))
    }

    @Test fun dialogueKeepsOneUnitPerLine() {
        val plan = CuePlan.of("- Hi\n<i>- Bye</i>")
        assertEquals(listOf("- Hi", "- Bye"), plan.units.map { it.body })
        assertEquals("- A\n<i>- B</i>", plan.render(listOf("- A", "- B")))
    }

    @Test fun interiorTagsAreStripped() {
        assertEquals("a b c", CuePlan.of("a <i>b</i> c").units[0].body)
    }

    @Test fun onlyLettersNeedTranslating() {
        assertFalse(needsTranslation("♪ ... ♪"))
        assertTrue(needsTranslation("♪ la ♪"))
    }

    @Test fun batchOrderRunsAheadOfPlayback() {
        val cues = (0 until 10).map { SubtitleCue(it * 1000L, it * 1000L + 900, "x") }
        val chunks = CueBatcher.chunks(cues.size, 4)
        assertEquals(listOf(0..3, 4..7, 8..9), chunks)
        assertEquals(0, CueBatcher.nextChunk(cues, chunks, emptySet(), 0))
        assertEquals(1, CueBatcher.nextChunk(cues, chunks, setOf(0), 0))
        // user seeked to cue 8: that chunk goes first, earlier ones afterwards
        assertEquals(2, CueBatcher.nextChunk(cues, chunks, setOf(0), 8_000))
        assertEquals(1, CueBatcher.nextChunk(cues, chunks, setOf(0, 2), 8_000))
        assertNull(CueBatcher.nextChunk(cues, chunks, setOf(0, 1, 2), 0))
    }

    @Test fun activeCueLookup() {
        val cues = listOf(SubtitleCue(0, 5000, "long"), SubtitleCue(1000, 2000, "short"), SubtitleCue(6000, 7000, "late"))
        assertEquals("short", cues.activeAt(1500)?.text)
        assertEquals("long", cues.activeAt(3000)?.text)
        assertNull(cues.activeAt(5500))
        assertEquals("late", cues.activeAt(6000)?.text)
        assertNull(cues.activeAt(7000))
    }
}
