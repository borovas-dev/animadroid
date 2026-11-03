package com.borovas.animadroid.feature.details

import com.borovas.animadroid.core.model.TranslationMode.DUB
import com.borovas.animadroid.core.model.TranslationMode.SUB
import org.junit.Assert.assertEquals
import org.junit.Test

class AvailableModesTest {
    @Test fun table() {
        assertEquals(listOf(SUB, DUB), availableModesFor(null, null, false))
        assertEquals(listOf(SUB), availableModesFor(12, 0, false))
        assertEquals(listOf(SUB), availableModesFor(12, null, false))
        assertEquals(listOf(SUB, DUB), availableModesFor(12, 0, true))
        assertEquals(listOf(SUB, DUB), availableModesFor(12, 12, false))
        assertEquals(listOf(DUB), availableModesFor(0, 5, true))
        assertEquals(listOf(SUB, DUB), availableModesFor(0, 0, true))
    }
}
