package com.borovas.animadroid.provider.api

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.SourceStrategy
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.model.SubtitleTrack
import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeSettings(initial: AppSettings) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings = state
    override suspend fun update(transform: (AppSettings) -> AppSettings) { state.value = transform(state.value) }
}

private class FakeProvider(
    override val id: String,
    override val contentLanguages: Set<String>,
    override val gfwBlocked: Boolean = false,
    override val priority: Int = 100,
    var results: List<Anime> = emptyList(),
    var streamDelayMs: Long = 0,
    var failure: Exception? = null,
    var height: Int? = 720,
    var subs: List<SubtitleTrack> = emptyList(),
    var hardsub: String? = null,
) : AnimeProvider {
    override val displayName = id
    var streamCalls = 0
    override suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        failure?.let { throw it }
        return results
    }
    override suspend fun episodes(anime: Anime, mode: TranslationMode) = listOf(Episode("$id-ep1", "1"), Episode("$id-ep2", "2"))
    override suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): StreamSet {
        streamCalls++
        if (streamDelayMs > 0) delay(streamDelayMs)
        failure?.let { throw it }
        return StreamSet(
            masterUrl = null,
            variants = height?.let { listOf(StreamVariant(it, "https://$id/${episode.id}.m3u8")) } ?: emptyList(),
            headers = emptyMap(), subtitles = subs, hardsubLanguage = hardsub,
        )
    }
}

class ProviderChainTest {
    private fun anime(provider: String, id: String = "a", title: String = "Naruto", mal: Int? = null, alts: Map<String, String> = emptyMap()) =
        Anime(id, provider, title, malId = mal, alternates = alts)

    private fun chain(vararg p: AnimeProvider, settings: AppSettings = AppSettings(), timeout: Long = 20_000, device: String = "en") =
        ProviderChain(p.associateBy { it.id }, FakeSettings(settings), timeout) { device }

    @Test fun chainOrdersByLanguageThenEnglishFallback() {
        val en1 = FakeProvider("hianime", setOf("en"), priority = 0)
        val en2 = FakeProvider("anidb", setOf("en"), priority = 1)
        val pl = FakeProvider("shinden", setOf("pl"))
        val c = chain(en2, pl, en1)
        assertEquals(listOf("hianime", "anidb"), c.chainFor(AppSettings(contentLanguage = "en")).map { it.id })
        assertEquals(listOf("shinden", "hianime", "anidb"), c.chainFor(AppSettings(contentLanguage = "pl")).map { it.id })
        // auto follows the subtitle language
        assertEquals(listOf("shinden", "hianime", "anidb"), c.chainFor(AppSettings(subtitleLanguage = "pl")).map { it.id })
        assertEquals(listOf("hianime", "anidb"), c.chainFor(AppSettings()).map { it.id })
    }

    @Test fun remotelyDisabledProvidersAreSkipped() {
        val a = FakeProvider("a", setOf("en"), priority = 0)
        val b = FakeProvider("b", setOf("en"), priority = 1)
        val c = ProviderChain(listOf(a, b).associateBy { it.id }, FakeSettings(AppSettings()), availability = { it != "a" })
        assertEquals(listOf("b"), c.chainFor(AppSettings(contentLanguage = "en")).map { it.id })
    }

    @Test fun manualOrderAndDisabled() {
        val a = FakeProvider("a", setOf("en"), priority = 0)
        val b = FakeProvider("b", setOf("en"), priority = 1)
        val c = chain(a, b)
        assertEquals(listOf("b", "a"), c.chainFor(AppSettings(contentLanguage = "en", providerOrder = mapOf("en" to listOf("b")))).map { it.id })
        assertEquals(listOf("b"), c.chainFor(AppSettings(contentLanguage = "en", disabledProviders = setOf("a"))).map { it.id })
    }

    @Test fun zhExcludesGfwBlockedEvenInManualOrder() {
        val xifan = FakeProvider("xifan", setOf("zh"))
        val hi = FakeProvider("hianime", setOf("en"), gfwBlocked = true)
        val anidb = FakeProvider("anidb", setOf("en"))
        val c = chain(xifan, hi, anidb)
        val s = AppSettings(contentLanguage = "zh", providerOrder = mapOf("zh" to listOf("hianime", "xifan")))
        assertEquals(listOf("xifan", "anidb"), c.chainFor(s).map { it.id })
        // other languages keep hianime
        assertTrue(c.chainFor(AppSettings(contentLanguage = "en")).any { it.id == "hianime" })
    }

    @Test fun searchMergesByMalIdThenTitleAndFillsAlternates() = runTest {
        val p1 = FakeProvider("p1", setOf("en"), priority = 0, results = listOf(anime("p1", "x1", "Naruto", mal = 20), anime("p1", "x2", "Only P1")))
        val p2 = FakeProvider("p2", setOf("en"), priority = 1, results = listOf(
            anime("p2", "y1", "NARUTO (TV)", mal = 20), anime("p2", "y2", "only p1"), anime("p2", "y3", "Only P2"),
        ))
        val r = chain(p1, p2).search("q", TranslationMode.SUB)
        assertEquals(listOf("Naruto", "Only P1", "Only P2"), r.map { it.title })
        assertEquals(mapOf("p2" to "y1"), r[0].alternates)
        assertEquals(mapOf("p2" to "y2"), r[1].alternates)
        assertEquals("p1", r[0].providerId)
        assertEquals("p2", r[2].providerId)
    }

    @Test fun searchToleratesFailureButThrowsWhenAllFail() = runTest {
        val ok = FakeProvider("ok", setOf("en"), priority = 0, results = listOf(anime("ok")))
        val bad = FakeProvider("bad", setOf("en"), priority = 1, failure = ProviderException("down"))
        assertEquals(1, chain(ok, bad).search("q", TranslationMode.SUB).size)
        try { chain(bad).search("q", TranslationMode.SUB); fail() } catch (e: ProviderException) { assertEquals("down", e.message) }
    }

    @Test fun streamsFailOverToAlternateMatchingEpisodeNumber() = runTest {
        val p1 = FakeProvider("p1", setOf("en"), priority = 0, failure = ProviderException("boom"))
        val p2 = FakeProvider("p2", setOf("en"), priority = 1)
        val r = chain(p1, p2).streams(anime("p1", alts = mapOf("p2" to "alt")), Episode("p1-ep2", "2"), TranslationMode.SUB)
        assertEquals("p2", r.providerId)
        assertTrue(r.isFallback)
        assertEquals("p2-ep2", r.episode.id)
        assertEquals("alt", r.anime.id)
    }

    @Test fun primaryIsUsedWhenHealthy() = runTest {
        val p1 = FakeProvider("p1", setOf("en"), priority = 0)
        val p2 = FakeProvider("p2", setOf("en"), priority = 1)
        val r = chain(p1, p2).streams(anime("p1", alts = mapOf("p2" to "alt")), Episode("e", "1"), TranslationMode.SUB)
        assertEquals("p1", r.providerId)
        assertFalse(r.isFallback)
        assertEquals(0, p2.streamCalls)
    }

    @Test fun timeoutFallsBack() = runTest {
        val slow = FakeProvider("slow", setOf("en"), priority = 0, streamDelayMs = 60_000)
        val fast = FakeProvider("fast", setOf("en"), priority = 1)
        val r = chain(slow, fast, timeout = 20_000).streams(anime("slow", alts = mapOf("fast" to "f")), Episode("e", "1"), TranslationMode.SUB)
        assertEquals("fast", r.providerId)
        assertTrue(r.isFallback)
    }

    @Test fun emptyVariantsFallBackAndAllFailThrows() = runTest {
        val empty = FakeProvider("empty", setOf("en"), priority = 0, height = null)
        val good = FakeProvider("good", setOf("en"), priority = 1)
        assertEquals("good", chain(empty, good).streams(anime("empty", alts = mapOf("good" to "g")), Episode("e", "1"), TranslationMode.SUB).providerId)
        try {
            chain(empty).streams(anime("empty"), Episode("e", "1"), TranslationMode.SUB); fail()
        } catch (e: ProviderException) { assertTrue(e.message!!.contains("no playable")) }
    }

    @Test fun cloudflareIsRethrownWhenEverythingFails() = runTest {
        val p = FakeProvider("p", setOf("en"), failure = CloudflareChallengeException("https://x"))
        try { chain(p).streams(anime("p"), Episode("e", "1"), TranslationMode.SUB); fail() } catch (e: CloudflareChallengeException) { }
    }

    @Test fun bestQualityPicksHigherResolution() = runTest {
        val low = FakeProvider("low", setOf("en"), priority = 0, height = 480)
        val high = FakeProvider("high", setOf("en"), priority = 1, height = 1080)
        val s = AppSettings(sourceStrategy = SourceStrategy.BEST_QUALITY)
        val r = chain(low, high, settings = s).streams(anime("low", alts = mapOf("high" to "h")), Episode("e", "1"), TranslationMode.SUB)
        assertEquals("high", r.providerId)
        assertTrue(r.isFallback)
        // Reliable strategy keeps the primary.
        assertEquals("low", chain(low, high).streams(anime("low", alts = mapOf("high" to "h")), Episode("e", "1"), TranslationMode.SUB).providerId)
    }

    @Test fun bestQualityPrefersSubtitlesWhenRequested() = runTest {
        val subbed = FakeProvider("subbed", setOf("en"), priority = 0, height = 720, subs = listOf(SubtitleTrack("u", "en", "English", true)))
        val bare = FakeProvider("bare", setOf("en"), priority = 1, height = 1080)
        val a = anime("bare", alts = mapOf("subbed" to "s"))
        val withSubs = AppSettings(sourceStrategy = SourceStrategy.BEST_QUALITY, subtitleLanguage = "pl", translateSubtitles = true)
        assertEquals("subbed", chain(subbed, bare, settings = withSubs).streams(a, Episode("e", "1"), TranslationMode.SUB).providerId)
        val noSubs = AppSettings(sourceStrategy = SourceStrategy.BEST_QUALITY)
        assertEquals("bare", chain(subbed, bare, settings = noSubs).streams(a, Episode("e", "1"), TranslationMode.SUB).providerId)
    }

    @Test fun bestQualityHardsubInRequestedLanguageCountsAsSubtitled() = runTest {
        val hard = FakeProvider("hard", setOf("zh"), height = 480, hardsub = "zh")
        val plain = FakeProvider("plain", setOf("en"), height = 1080)
        val s = AppSettings(contentLanguage = "zh", sourceStrategy = SourceStrategy.BEST_QUALITY, subtitleLanguage = "zh")
        val r = chain(hard, plain, settings = s).streams(anime("plain", alts = mapOf("hard" to "h")), Episode("e", "1"), TranslationMode.SUB)
        assertEquals("hard", r.providerId)
    }

    @Test fun providerIdsAndLookup() = runTest {
        val c = chain(FakeProvider("a", setOf("en")))
        assertEquals(listOf("a"), c.ids)
        assertEquals("a", c.provider("a")!!.id)
        assertEquals(listOf("a"), c.chain().map { it.id })
        assertEquals("en", c.effectiveLanguage(AppSettings()))
    }

    @Test fun autoLanguageFollowsDeviceLocale() {
        val c = { dev: String -> chain(FakeProvider("a", setOf("en")), FakeProvider("b", setOf("pl")), FakeProvider("c", setOf("zh")), device = dev) }
        assertEquals("pl", c("pl").effectiveLanguage(AppSettings()))
        assertEquals("zh", c("zh").effectiveLanguage(AppSettings()))
        assertEquals("en", c("en").effectiveLanguage(AppSettings()))
        assertEquals("en", c("de").effectiveLanguage(AppSettings()))
        assertEquals("pl", c("PL").effectiveLanguage(AppSettings()))
        // explicit settings win over the device
        assertEquals("zh", c("pl").effectiveLanguage(AppSettings(contentLanguage = "zh")))
        assertEquals("zh", c("pl").effectiveLanguage(AppSettings(subtitleLanguage = "zh")))
        assertEquals(listOf("b", "a"), c("pl").chainFor(AppSettings()).map { it.id })
    }
}
