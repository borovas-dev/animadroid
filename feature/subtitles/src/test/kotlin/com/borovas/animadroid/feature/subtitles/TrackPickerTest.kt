package com.borovas.animadroid.feature.subtitles

import com.borovas.animadroid.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackPickerTest {
    private fun t(lang: String?, label: String?, def: Boolean = false) = SubtitleTrack("https://x/$label.vtt", lang, label, def)

    @Test fun languageNormalisation() {
        assertEquals("en", SubtitleLanguages.normalize("eng"))
        assertEquals("zh", SubtitleLanguages.normalize("zh-Hans"))
        assertEquals("zh", SubtitleLanguages.normalize("chi"))
        assertEquals("pl", SubtitleLanguages.normalize("pol"))
        assertEquals("pt", SubtitleLanguages.normalize("pt_BR"))
        assertEquals("en", SubtitleLanguages.normalize("English"))
        assertEquals(null, SubtitleLanguages.normalize("und"))
        assertEquals(null, SubtitleLanguages.normalize(null))
    }

    @Test fun displayNameIsInOwnLanguage() {
        assertEquals("中文", SubtitleLanguages.displayName("zh"))
        assertEquals("Polski", SubtitleLanguages.displayName("pl"))
    }

    @Test fun providedTrackInPreferredLanguageWins() {
        val zh = t("zh", "Chinese")
        val d = TrackPicker.decide(listOf(t("en", "English"), zh), "zh", translate = true)
        assertEquals(SubtitleDecision.UseProvided(zh), d)
    }

    @Test fun labelOnlyTracksAreRecognised() {
        val pl = t(null, "Polish")
        assertEquals(SubtitleDecision.UseProvided(pl), TrackPicker.decide(listOf(pl), "pl", translate = false))
    }

    @Test fun translatesEnglishWhenNoPreferredTrack() {
        val en = t("en", "English")
        val d = TrackPicker.decide(listOf(t("es", "Spanish"), en), "zh", translate = true)
        assertEquals(SubtitleDecision.Translate(en, "en"), d)
    }

    @Test fun unknownSourceLanguageAssumedEnglish() {
        val x = t(null, "Subs", def = true)
        assertEquals(SubtitleDecision.Translate(x, "en"), TrackPicker.decide(listOf(x), "zh", translate = true))
    }

    @Test fun noTranslationWhenDisabledOrUnsupportedOrThumbnailsOnly() {
        val en = t("en", "English")
        assertEquals(SubtitleDecision.None, TrackPicker.decide(listOf(en), "zh", translate = false))
        assertEquals(SubtitleDecision.None, TrackPicker.decide(listOf(en), "zh", translate = true) { false })
        assertEquals(SubtitleDecision.None, TrackPicker.decide(listOf(t(null, "thumbnails")), "zh", translate = true))
        assertEquals(SubtitleDecision.None, TrackPicker.decide(listOf(en), "", translate = true))
    }
}
