package com.borovas.animadroid.provider.hianime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HianimeParsersTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun searchParsesTitlesIdsCoversCountsAndSkipsSidebar() {
        val r = HianimeParsers.parseSearch(res("search.html"), "hianime")
        assertEquals(listOf("one-piece-1", "one-piece-film-red-2825"), r.map { it.id })
        assertEquals("One Piece", r[0].title)
        assertEquals("https://cdn.example.net/thumb/one-piece.jpg", r[0].coverUrl)
        assertEquals(1180, r[0].subEpisodes)
        assertEquals(1157, r[0].dubEpisodes)
        assertEquals("One Piece Film: Red & Co", r[1].title)
        assertEquals("https://cdn.example.net/thumb/red.jpg", r[1].coverUrl)
        assertEquals(1, r[1].subEpisodes)
        assertNull(r[1].dubEpisodes)
        assertTrue(r.none { it.id.startsWith("sidebar") })
    }

    @Test fun episodesFromJsonSortedAndFilteredBySlug() {
        val eps = HianimeParsers.parseEpisodes(res("episodes.json"), "one-piece-1")
        assertEquals(listOf("1", "2", "10", "11.5"), eps.map { it.number })
        assertEquals(listOf("1", "2", "10", "115"), eps.map { it.id })
    }

    @Test fun episodesFromRawEscapedHtml() {
        val raw = """<a class=\"ep-item\" data-number=\"3\" data-id=\"33\" href=\"https:\/\/hianime.at\/watch\/x-7?ep=33\">"""
        val eps = HianimeParsers.parseEpisodes(raw, "x-7")
        assertEquals(listOf("3" to "33"), eps.map { it.number to it.id })
    }

    @Test fun embedUrlFromRealServersFixture() {
        assertEquals("https://zokoanime.video/stream/mal/21/1/sub", HianimeParsers.parseEmbedUrl(res("servers.json"), "sub"))
        assertEquals("https://zokoanime.video/stream/mal/21/1/dub", HianimeParsers.parseEmbedUrl(res("servers.json"), "dub"))
        assertEquals("https://zokoanime.video/", HianimeParsers.embedOrigin("https://zokoanime.video/stream/mal/21/1/sub"))
        assertEquals(21, HianimeParsers.malId("https://zokoanime.video/stream/mal/21/1/sub"))
    }

    @Test fun realEmbedBlobDecodes() {
        val blob = HianimeParsers.extractBlob(res("embed.html"))!!
        val cfg = HianimeParsers.parseEmbedConfig(HianimeParsers.deobfuscateBlob(blob))
        assertTrue(cfg.masterUrl!!.endsWith("master.m3u8"))
        assertEquals(1, cfg.subtitles.size)
        assertTrue(cfg.subtitles[0].isDefault)
        assertEquals("en", cfg.subtitles[0].language)
        assertEquals(31.0, cfg.intro!!.startSec, 0.0)
        assertEquals(111.0, cfg.intro!!.endSec, 0.0)
        assertEquals(1376.0, cfg.outro!!.startSec, 0.0)
        assertEquals(1447.0, cfg.outro!!.endSec, 0.0)
    }

    @Test fun embedSkipMissingOrInvalidIsNull() {
        val none = HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8"}""")
        assertEquals(null, none.intro)
        val bad = HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8","skip":{"intro":{"start":10,"end":5},"outro":{"start":0,"end":0}}}""")
        assertEquals(null, bad.intro)
        assertEquals(null, bad.outro)
    }

    @Test fun spriteVttParsedAndEmptyIsNull() {
        val url = "https://hls.example.com/v/a/b/sprite.vtt"
        assertEquals(url, HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8","sprite_vtt":"$url"}""").spriteVttUrl)
        assertEquals(null, HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8","sprite_vtt":""}""").spriteVttUrl)
        assertEquals(null, HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8","sprite_vtt":null}""").spriteVttUrl)
        assertEquals(null, HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8","sprite_vtt":"  "}""").spriteVttUrl)
        assertEquals(null, HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8"}""").spriteVttUrl)
        assertEquals(null, HianimeParsers.parseEmbedConfig("""{"src":"https://a/b.m3u8","sprite_vtt":5}""").spriteVttUrl)
    }

    @Test fun xorRoundTrip() {
        val plain = """{"src":"https://a/b.m3u8"}""".toByteArray()
        assertEquals(String(plain), String(HianimeParsers.xor(HianimeParsers.xor(plain))))
    }

    @Test fun masterPlaylist() {
        val m = HianimeParsers.parseMaster(res("master.m3u8"), "https://hls.example.com/p/a/master.m3u8")
        assertEquals(listOf(1080, 720, 360), m.variants.map { it.quality })
        assertEquals("https://hls.example.com/p/a/1080/index.m3u8", m.variants[0].url)
        assertEquals("https://cdn.example.net/abs/720/index.m3u8", m.variants[1].url)
        assertEquals("https://hls.example.com/p/a/audio/ja/index.m3u8", m.externalAudioUrl)
        assertFalse(m.variants.any { it.url.contains("iframes") })
    }

    @Test fun htmlPayloadHandlesJsonAndRaw() {
        assertEquals("<b>x</b>", HianimeParsers.htmlPayload("""{"html":"<b>x<\/b>"}"""))
        assertEquals("<b class=\"a\">", HianimeParsers.htmlPayload("""<b class=\"a\">"""))
    }
}
