package com.borovas.animadroid.feature.player

import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.CloudflareChallengeException
import com.borovas.animadroid.provider.api.ProviderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DubPolicyTest {
    @Test fun shouldRunTable() {
        data class Case(val enabled: Boolean, val mode: TranslationMode, val recorded: Boolean, val cues: Boolean, val lang: String?, val expected: Boolean)
        val dub = TranslationMode.DUB
        listOf(
            Case(true, dub, false, true, "pl", true),
            Case(false, dub, false, true, "pl", false), // switch off: SUB fallback shows subtitles, no TTS
            Case(true, TranslationMode.SUB, false, true, "pl", false),
            Case(true, dub, true, true, "pl", false),
            Case(true, dub, false, false, "pl", false),
            Case(true, dub, false, true, null, false),
            Case(true, dub, false, true, "", false),
        ).forEach { assertEquals(it.toString(), it.expected, DubPolicy.shouldRun(it.enabled, it.mode, it.recorded, it.cues, it.lang)) }
    }

    @Test fun targetLanguageResolution() {
        assertEquals("pl", DubPolicy.targetLanguage("pl", "en"))
        assertEquals("pt", DubPolicy.targetLanguage("pt-BR", "en"))
        assertEquals("en", DubPolicy.targetLanguage("", "en"))
        assertEquals("pl", DubPolicy.targetLanguage("", "pl"))
        assertEquals("zh", DubPolicy.targetLanguage("  ", "zh-Hans"))
        assertEquals("en", DubPolicy.targetLanguage("", ""))
    }

    @Test fun dubKnownMissing() {
        assertTrue(DubPolicy.dubKnownMissing(TranslationMode.DUB, 0))
        assertFalse(DubPolicy.dubKnownMissing(TranslationMode.DUB, null))
        assertFalse(DubPolicy.dubKnownMissing(TranslationMode.DUB, 12))
        assertFalse(DubPolicy.dubKnownMissing(TranslationMode.SUB, 0))
    }

    @Test fun languageMismatch() {
        assertFalse(DubPolicy.dubLanguageMismatch(true, setOf("pl"), "pl")) // Shinden dub for pl target: real dub
        assertTrue(DubPolicy.dubLanguageMismatch(true, setOf("pl"), "en"))
        assertTrue(DubPolicy.dubLanguageMismatch(true, setOf("en"), "pl")) // HiAnime English dub is not a Polish dub
        assertFalse(DubPolicy.dubLanguageMismatch(false, setOf("en"), "pl")) // switch off: play the dub as before
        assertFalse(DubPolicy.dubLanguageMismatch(true, null, "pl"))
    }

    @Test fun onlyNoDubErrorsFallBack() {
        assertTrue(DubPolicy.isNoDubFailure(ProviderException("No Polish dub/lektor players")))
        assertFalse(DubPolicy.isNoDubFailure(CloudflareChallengeException("https://x")))
        assertFalse(DubPolicy.isNoDubFailure(java.io.IOException("offline")))
    }

    @Test fun duckNeverExceedsUserVolume() {
        assertEquals(0.25f, DubPolicy.volume(1f, true), 0f)
        assertEquals(1f, DubPolicy.volume(1f, false), 0f)
        assertEquals(0f, DubPolicy.volume(0f, true), 0f)
        assertEquals(0f, DubPolicy.volume(0f, false), 0f)
    }

    @Test fun extrapolation() {
        assertEquals(1500L, DubPolicy.extrapolate(1000, 100, 600, true, 1f))
        assertEquals(2000L, DubPolicy.extrapolate(1000, 100, 600, true, 2f))
        assertEquals(1000L, DubPolicy.extrapolate(1000, 100, 600, false, 1f))
        assertEquals(1000L, DubPolicy.extrapolate(1000, 600, 100, true, 1f))
    }
}
