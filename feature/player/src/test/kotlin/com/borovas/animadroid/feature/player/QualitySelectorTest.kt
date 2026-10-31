package dev.borovas.animadroid.feature.player

import dev.borovas.animadroid.core.model.QualityPreference
import dev.borovas.animadroid.core.model.StreamSet
import dev.borovas.animadroid.core.model.StreamVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualitySelectorTest {
    private val v = listOf(
        StreamVariant(1080, "u1080"), StreamVariant(720, "u720"),
        StreamVariant(360, "u360"), StreamVariant(null, "unknown"),
    )
    private fun set(master: String? = "master", variants: List<StreamVariant> = v) =
        StreamSet(master, variants, emptyMap())

    @Test fun bestUsesMaster() {
        assertEquals("master", QualitySelector.resolveUrl(set(), QualityPreference.BEST))
        assertEquals("u1080", QualitySelector.resolveUrl(set(master = null), QualityPreference.BEST))
    }

    @Test fun worstPicksLowestKnown() {
        assertEquals("u360", QualitySelector.resolveUrl(set(), QualityPreference.WORST))
    }

    @Test fun exactMatch() {
        assertEquals("u720", QualitySelector.resolveUrl(set(), QualityPreference.P720))
    }

    @Test fun nearestLowerThenBest() {
        assertEquals("u360", QualitySelector.resolveUrl(set(), QualityPreference.P480))
        assertEquals("u1080", QualitySelector.resolveUrl(set(variants = listOf(StreamVariant(1080, "u1080"))), QualityPreference.P360))
    }

    @Test fun explicitHeightOverridesBest() {
        assertFalse(QualitySelector.useMaster(set(), QualityPreference.BEST, 720))
        assertEquals("u720", QualitySelector.resolveUrl(set(), QualityPreference.BEST, 720))
    }

    @Test fun onlyMasterAndEmpty() {
        assertTrue(QualitySelector.useMaster(set(variants = emptyList()), QualityPreference.P720))
        assertEquals("master", QualitySelector.resolveUrl(set(variants = emptyList()), QualityPreference.P720))
        assertNull(QualitySelector.resolveUrl(set(master = null, variants = emptyList()), QualityPreference.BEST))
    }

    @Test fun subtitleMime() {
        assertEquals("text/vtt", QualitySelector.subtitleMimeType("http://x/a.vtt?x=1"))
        assertEquals("application/x-subrip", QualitySelector.subtitleMimeType("http://x/a.SRT"))
        assertEquals("text/x-ssa", QualitySelector.subtitleMimeType("http://x/a.ass"))
    }
}
