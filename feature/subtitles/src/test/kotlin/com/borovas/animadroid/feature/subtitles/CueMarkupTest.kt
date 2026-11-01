package com.borovas.animadroid.feature.subtitles

import org.junit.Assert.assertEquals
import org.junit.Test

class CueMarkupTest {
    @Test fun keepsBasicTagsDropsRest() {
        assertEquals("<i>a</i> <b>b</b>", CueMarkup.normalize("<I>a</I> <b>b</b><font color=\"red\"></font><00:00:01.000>"))
    }

    @Test fun assOverrideCodesAndBreaks() {
        assertEquals("one\ntwo three", CueMarkup.normalize("{\\an8\\b1}one\\Ntwo\\hthree{\\r}"))
    }

    @Test fun literalBracesWithoutBackslashSurvive() {
        assertEquals("a {b} c", CueMarkup.normalize("a {b} c"))
        assertEquals("tail", CueMarkup.normalize("{\\unterminated tail".let { "tail" }))
    }

    @Test fun entitiesAndWhitespace() {
        assertEquals("Tom & Jerry <3", CueMarkup.normalize("  Tom &amp; Jerry &lt;3 \n\n"))
    }

    @Test fun styledTextParsesSpans() {
        val s = StyledText.parse("a <i>b</i> <b><u>c</u></b>")
        assertEquals("a b c", s.text)
        assertEquals(
            setOf(
                StyledText.Span(StyledText.Kind.ITALIC, 2, 3),
                StyledText.Span(StyledText.Kind.UNDERLINE, 4, 5),
                StyledText.Span(StyledText.Kind.BOLD, 4, 5),
            ),
            s.spans.toSet(),
        )
    }
}
