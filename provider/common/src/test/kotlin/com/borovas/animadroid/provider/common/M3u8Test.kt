package com.borovas.animadroid.provider.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class M3u8Test {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun masterPlaylist() {
        val m = M3u8.parseMaster(res("master.m3u8"), "https://hls.example.com/p/a/master.m3u8")
        assertEquals(listOf(1080, 720, 360), m.variants.map { it.quality })
        assertEquals("https://hls.example.com/p/a/1080/index.m3u8", m.variants[0].url)
        assertEquals("https://cdn.example.net/abs/720/index.m3u8", m.variants[1].url)
        assertEquals("https://hls.example.com/p/a/audio/ja/index.m3u8", m.externalAudioUrl)
        assertFalse(m.variants.any { it.url.contains("iframes") })
    }
}
