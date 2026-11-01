package com.borovas.animadroid.provider.shinden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vidara, YourUpload and CDA (embed_cda.html) fixtures are real captures (2026-11-01). The sibnet/jwplayer/vk pages and the legacy-`file` CDA page below are SYNTHETIC: those hosts had no
 * working video among the sampled live episodes, so the pages are hand-written from each host's documented shape, not captured.
 */
class HostExtractorsTest {
    @Test fun sibnetShell() {
        val html = """<script>player.src([{src: "/v/abc123def/4567890.mp4", type: "video/mp4"}]);</script>"""
        assertEquals("/v/abc123def/4567890.mp4", SibnetExtractor.parse(html))
        assertNull(SibnetExtractor.parse("<html>removed</html>"))
    }

    @Test fun jwPlayerSources() {
        val html = """jwplayer("vplayer").setup({sources:[{file:"https://cdn.example/hls/master.m3u8?t=1",type:"hls"}]});"""
        assertEquals("https://cdn.example/hls/master.m3u8?t=1", JwPlayerExtractor.parse(html, "https://streamwish.to/e/xyz"))
    }

    @Test fun jwPlayerHlsKeysPreferHighestAndResolveRelative() {
        val html = """var links={"hls2":"/stream/a.m3u8","hls4":"/stream/b.m3u8?x=1\/y"};"""
        assertEquals("https://filemoon.sx/stream/b.m3u8?x=1/y", JwPlayerExtractor.parse(html, "https://filemoon.sx/e/abc"))
    }

    @Test fun jwPlayerPackedScript() {
        // eval(function(p,a,c,k,e,d){...}('0.1({2:[{3:"https://cdn.example/v.mp4"}]})',4,4,'jwplayer|setup|sources|file'.split('|')))
        val html = """eval(function(p,a,c,k,e,d){}('0.1({2:[{3:"https://cdn.example/v.mp4"}]})',4,4,'jwplayer|setup|sources|file'.split('|')))"""
        assertEquals("https://cdn.example/v.mp4", JwPlayerExtractor.parse(html, "https://streamwish.to/e/1"))
        assertNull(JwPlayerExtractor.parse("<html></html>", "https://streamwish.to/e/1"))
    }

    private fun rot(s: String) = s.map { if (it.code in 33..126) (33 + (it.code + 14) % 94).toChar() else it }.joinToString("")

    @Test fun cdaDecryptsScrambledFile() {
        val target = "vfm123.cda.pl/xyz/abc"
        assertEquals("https://$target.mp4", CdaExtractor.decryptFile(rot(target)))
        assertEquals("https://vf.cda.pl/a.mp4/upstream", CdaExtractor.decryptFile(rot("vf.2cda.pl/a/upstream")))
    }

    @Test fun cdaPlayerDataAttribute() {
        val file = java.net.URLEncoder.encode(rot("vfm123.cda.pl/xyz/abc"), "UTF-8")
        val json = """{"video":{"file":"$file","quality":"720p","ts":1}}""".replace("\"", "&quot;")
        val src = CdaExtractor.parse("""<div id="mediaplayer" player_data="$json"></div>""")!!
        assertEquals("https://vfm123.cda.pl/xyz/abc.mp4", src.url)
        assertEquals(720, src.height)
        assertTrue(!src.hls)
        assertNull(CdaExtractor.parse("<html></html>"))
    }

    @Test fun cdaRealEmbedUsesHlsManifest() {
        val src = CdaExtractor.parse(res("embed_cda.html"))!!
        assertTrue(src.hls)
        assertEquals("https://vwaw620.cda.pl/Jg7FOlU3MwWTHqfsXKg_cg/1793560813/16na/aaab22bf775/33/13442650raw/13442650.m3u8", src.url)
        assertEquals(1080, src.height)
    }

    @Test fun vkVariantsAndHls() {
        val html = """{"url240":"https:\/\/cs.vk.me\/240.mp4?a=1&b=2","url720":"https:\/\/cs.vk.me\/720.mp4","hls":"https:\/\/vk.me\/m.m3u8"}"""
        val (mp4, hls) = VkExtractor.parse(html)
        assertEquals(listOf(720, 240), mp4.map { it.quality })
        assertEquals("https://cs.vk.me/240.mp4?a=1&b=2", mp4[1].url)
        assertEquals("https://vk.me/m.m3u8", hls)
    }

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun vidaraStreamingUrlFromRealApiShape() {
        val url = VidaraExtractor.parseStreamingUrl(res("vidara_stream.json"))!!
        assertTrue(url.startsWith("https://s6-s251765.97bf1.com/hls/") && url.contains("master.m3u8"))
        assertNull(VidaraExtractor.parseStreamingUrl("""{"error":"not found"}"""))
        assertNull(VidaraExtractor.parseStreamingUrl("not json"))
    }

    @Test fun youruploadOgVideoFromRealEmbed() {
        assertEquals("https://vidcache.net:8161/a20261101lQL2pQ62w26/video.mp4", YouruploadExtractor.parse(res("embed_yourupload.html")))
        assertEquals("https://x.net/v.mp4", YouruploadExtractor.parse("""file: 'https://x.net/v.mp4'"""))
        assertNull(YouruploadExtractor.parse("<html></html>"))
    }

    @Test fun registryRoutesHostsAndNames() {
        assertTrue(HostRegistry.byHost("video.sibnet.ru") === SibnetExtractor)
        assertTrue(HostRegistry.byHost("ebd.cda.pl") === CdaExtractor)
        assertTrue(HostRegistry.byHost("vk.com") === VkExtractor)
        assertTrue(HostRegistry.byHost("hgcloud.to") == null)
        assertNotNull(HostRegistry.byHost("streamwish.to"))
        assertTrue(HostRegistry.byPlayerName("Streamwish") === JwPlayerExtractor)
        assertTrue(HostRegistry.byPlayerName("Cda") === CdaExtractor)
        assertTrue(HostRegistry.byPlayerName("Vidara") === VidaraExtractor)
        assertTrue(HostRegistry.byHost("www.yourupload.com") === YouruploadExtractor)
        assertTrue(HostRegistry.byPlayerName("Mega") == null && HostRegistry.byPlayerName("Voesx") == null)
    }
}
