package com.borovas.animadroid.feature.subtitles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubtitleParserTest {
    @Test fun vttKeepsExactTiming() {
        val vtt = "\uFEFFWEBVTT\n\nNOTE comment\n\n1\n00:00:01.500 --> 00:00:03.250 align:start\n<i>Hello</i>\nworld\n\n01:02:03.004 --> 01:02:04.000\n<c.yellow>Bye</c>\n"
        val cues = SubtitleParser.parse(vtt)
        assertEquals(listOf(SubtitleCue(1500, 3250, "<i>Hello</i>\nworld"), SubtitleCue(3_723_004, 3_724_000, "Bye")), cues)
    }

    @Test fun srtCommaMillisAndCrlf() {
        val srt = "1\r\n00:00:00,040 --> 00:00:02,000\r\n{\\an8}Top line\r\n\r\n2\r\n00:00:02,500 --> 00:00:04,000\r\nTwo\r\n"
        val cues = SubtitleParser.parse(srt)
        assertEquals(40L, cues[0].startMs)
        assertEquals("Top line", cues[0].text)
        assertEquals(2500L, cues[1].startMs)
    }

    @Test fun assStripsOverridesAndKeepsCommasInText() {
        val ass = """
            [Script Info]
            Title: x

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.10,0:00:07.00,Default,,0,0,0,,{\i1\pos(10,20)}Well, hello\Nthere{\i0}
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,{\p1}m 0 0 l 10 10{\p0}
            Dialogue: 0,0:00:03.00,0:00:04.00,Default,,0,0,0,,Plain
        """.trimIndent()
        val cues = SubtitleParser.parse(ass)
        assertEquals(listOf(SubtitleCue(3000, 4000, "Plain"), SubtitleCue(5100, 7000, "Well, hello\nthere")), cues)
    }

    @Test fun timeParsing() {
        assertEquals(61_500L, SubtitleParser.parseTime("01:01.500"))
        assertEquals(3_600_000L + 5_000L + 120L, SubtitleParser.parseTime("1:00:05.12"))
        assertNull(SubtitleParser.parseTime("garbage"))
    }

    @Test fun emptyOrBrokenInputGivesNoCues() {
        assertEquals(emptyList<SubtitleCue>(), SubtitleParser.parse(""))
        assertEquals(emptyList<SubtitleCue>(), SubtitleParser.parse("WEBVTT\n\nnot a cue"))
    }
}
