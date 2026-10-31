package com.borovas.animadroid.feature.downloads

import androidx.media3.exoplayer.offline.Download
import com.borovas.animadroid.core.model.DownloadState
import com.borovas.animadroid.core.model.QualityPreference
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.StreamVariant
import com.borovas.animadroid.core.model.SubtitleTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPlanningTest {
    private val variants = listOf(
        StreamVariant(1080, "https://cdn/1080.m3u8"), StreamVariant(720, "https://cdn/720.m3u8"), StreamVariant(360, "https://cdn/360.m3u8"),
    )
    private val set = StreamSet(
        masterUrl = "https://cdn/master.m3u8", variants = variants, headers = mapOf("Referer" to "https://site/"),
        externalAudioUrl = "https://cdn/audio.m3u8",
        subtitles = listOf(SubtitleTrack("https://cdn/en.vtt?x=1", "en", "English", true), SubtitleTrack("https://cdn/en.vtt?x=1", "en", "dup", false), SubtitleTrack("https://cdn/de.srt", "de", null, false)),
    )

    @Test fun stateConstantsMatchMedia3() {
        assertEquals(Download.STATE_QUEUED, PartState.QUEUED)
        assertEquals(Download.STATE_STOPPED, PartState.STOPPED)
        assertEquals(Download.STATE_DOWNLOADING, PartState.DOWNLOADING)
        assertEquals(Download.STATE_COMPLETED, PartState.COMPLETED)
        assertEquals(Download.STATE_FAILED, PartState.FAILED)
        assertEquals(Download.STATE_REMOVING, PartState.REMOVING)
        assertEquals(Download.STATE_RESTARTING, PartState.RESTARTING)
    }

    @Test fun planPicksVariantPerPreference() {
        assertEquals(1080, DownloadPlanner.plan(set, QualityPreference.BEST)!!.quality)
        assertEquals(360, DownloadPlanner.plan(set, QualityPreference.WORST)!!.quality)
        assertEquals(720, DownloadPlanner.plan(set, QualityPreference.P720)!!.quality)
        assertEquals(360, DownloadPlanner.plan(set, QualityPreference.P480)!!.quality) // nearest lower
        assertEquals(360, DownloadPlanner.plan(set.copy(variants = variants.takeLast(1)), QualityPreference.P1080)!!.quality)
    }

    @Test fun planSnapshotIsSingleRenditionWithoutMaster() {
        val plan = DownloadPlanner.plan(set, QualityPreference.P720)!!
        assertNull(plan.snapshot.masterUrl)
        assertEquals(listOf("https://cdn/720.m3u8"), plan.snapshot.variants.map { it.url })
        assertEquals(set.headers, plan.snapshot.headers)
        assertEquals(set.externalAudioUrl, plan.snapshot.externalAudioUrl)
        assertEquals(set.subtitles, plan.snapshot.subtitles)
    }

    @Test fun planFallsBackToMasterAndFailsWhenEmpty() {
        val master = DownloadPlanner.plan(set.copy(variants = emptyList()), QualityPreference.BEST)!!
        assertEquals("https://cdn/master.m3u8", master.snapshot.masterUrl)
        assertNull(master.quality)
        assertNull(DownloadPlanner.plan(set.copy(variants = emptyList(), masterUrl = null), QualityPreference.BEST))
    }

    @Test fun partsAreMainThenAudioThenDedupedSubtitles() {
        val key = DownloadIds.episodeKey("hianime", "a", "1")
        val parts = DownloadPlanner.parts(key, DownloadPlanner.plan(set, QualityPreference.P720)!!.snapshot)
        assertEquals(
            listOf(key, "$key#audio", "$key#sub0", "$key#sub1"), parts.map { it.id },
        )
        assertEquals(listOf(true, true, false, false), parts.map { it.isHls })
        assertTrue(DownloadIds.isMain(parts[0].id))
        assertFalse(DownloadIds.isMain(parts[1].id))
        assertTrue(parts.all { DownloadIds.keyOf(it.id) == key })
    }

    @Test fun idsRoundTripEvenWithOddCharacters() {
        val key = DownloadIds.episodeKey("p|1", "a/b#c?", "12.5")
        // '#' inside ids is a documented limitation only for animeId; numbers/providers are safe.
        val parsed = DownloadIds.parse(DownloadIds.keyOf(DownloadIds.episodeKey("hianime", "slug-123", "12.5")))!!
        assertEquals(Triple("hianime", "slug-123", "12.5"), Triple(parsed.providerId, parsed.animeId, parsed.episodeNumber))
        assertNotNull(key)
    }

    @Test fun payloadRoundTrip() {
        val p = DownloadPayload(mapOf("Referer" to "https://x/", "User-Agent" to "UA"), "Show · Episode 1")
        assertEquals(p, DownloadPayload.decode(p.encode()))
        assertEquals(DownloadPayload(), DownloadPayload.decode(null))
        assertEquals(DownloadPayload(), DownloadPayload.decode("garbage".encodeToByteArray()))
    }

    private fun part(id: String, state: Int, bytes: Long = 0, pct: Float = 0f) = PartState(id, state, bytes, pct)

    @Test fun aggregateStates() {
        val m = "k"
        val a = "k#audio"
        assertNull(DownloadAggregator.aggregate(emptyList()))
        assertEquals(DownloadState.QUEUED, DownloadAggregator.aggregate(listOf(part(m, PartState.QUEUED)))!!.state)
        val dl = DownloadAggregator.aggregate(listOf(part(m, PartState.DOWNLOADING, 100, 40f), part(a, PartState.QUEUED, 10)))!!
        assertEquals(DownloadState.DOWNLOADING, dl.state)
        assertEquals(110, dl.bytes)
        assertEquals(0.4f, dl.percent, 1e-6f)
        assertEquals(DownloadState.PAUSED, DownloadAggregator.aggregate(listOf(part(m, PartState.STOPPED, 5, 10f)))!!.state)
        assertEquals(DownloadState.FAILED, DownloadAggregator.aggregate(listOf(part(m, PartState.COMPLETED), part(a, PartState.FAILED)))!!.state)
    }

    @Test fun completedNeedsEveryPartAndMain() {
        val m = "k"
        val a = "k#audio"
        val partial = DownloadAggregator.aggregate(listOf(part(m, PartState.COMPLETED, 1000, 100f), part(a, PartState.DOWNLOADING, 10, 50f)))!!
        assertEquals(DownloadState.DOWNLOADING, partial.state)
        assertEquals(0.99f, partial.percent, 1e-6f) // never shows 100% before sidecars are done
        val done = DownloadAggregator.aggregate(listOf(part(m, PartState.COMPLETED, 1000, 100f), part(a, PartState.COMPLETED, 10, 100f)))!!
        assertEquals(DownloadState.COMPLETED, done.state)
        assertEquals(1f, done.percent, 0f)
        // sidecar-only snapshot (main not known yet) must not look complete
        assertEquals(DownloadState.QUEUED, DownloadAggregator.aggregate(listOf(part(a, PartState.COMPLETED)))!!.state)
    }

    @Test fun unknownPercentAndRemovingParts() {
        val agg = DownloadAggregator.aggregate(listOf(part("k", PartState.DOWNLOADING, 0, -1f)))!!
        assertEquals(0f, agg.percent, 0f)
        assertNull(DownloadAggregator.aggregate(listOf(part("k", PartState.REMOVING))))
    }
}
