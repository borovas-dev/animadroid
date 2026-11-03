package com.borovas.animadroid.feature.search

import com.borovas.animadroid.core.model.Anime
import org.junit.Assert.assertEquals
import org.junit.Test

class DubModeVisibilityTest {
    private fun anime(sub: Int?, dub: Int?) = Anime(id = "a", providerId = "p", title = "T", subEpisodes = sub, dubEpisodes = dub)

    @Test fun voiceOverOnShowsEverything() {
        assertEquals(true, dubModeVisible(anime(12, null), providerKnowsDubs = true, voiceOverEnabled = true))
        assertEquals(true, dubModeVisible(anime(12, 0), providerKnowsDubs = true, voiceOverEnabled = true))
        assertEquals(true, dubModeVisible(anime(37, null), providerKnowsDubs = false, voiceOverEnabled = true))
    }

    @Test fun voiceOverOffHidesShowsWithoutDubWhenProviderKnows() {
        assertEquals(false, dubModeVisible(anime(12, null), providerKnowsDubs = true, voiceOverEnabled = false))
        assertEquals(false, dubModeVisible(anime(12, 0), providerKnowsDubs = true, voiceOverEnabled = false))
        assertEquals(true, dubModeVisible(anime(12, 5), providerKnowsDubs = true, voiceOverEnabled = false))
    }

    @Test fun voiceOverOffKeepsUnknownProviders() {
        // e.g. Shinden never reports a dub count: hiding on null would empty the whole DUB search.
        assertEquals(true, dubModeVisible(anime(37, null), providerKnowsDubs = false, voiceOverEnabled = false))
    }
}
