package com.borovas.animadroid.feature.player

import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class SpriteSheetTest {
    private val vtt = "https://hls.example.com/v/a/b/sprite.vtt"

    // Shape of the real embed fixture (Your Name): 5 columns of 160x90 tiles, 10 s interval.
    private val real = """
        WEBVTT

        00:00:00.000 --> 00:00:10.000
        sprite.jpg#xywh=0,0,160,90

        00:00:10.000 --> 00:00:20.000
        sprite.jpg#xywh=160,0,160,90

        01:46:20.000 --> 01:46:30.000
        sprite.jpg#xywh=480,11430,160,90
    """.trimIndent()

    @Test fun parsesRealShapeAndResolvesRelativeUrl() {
        val cues = SpriteVtt.parse(real, vtt)
        assertEquals(3, cues.size)
        assertEquals(SpriteCue(0, 10_000, "https://hls.example.com/v/a/b/sprite.jpg", 0, 0, 160, 90), cues[0])
        assertEquals(10_000, cues[1].startMs)
        assertEquals(160, cues[1].x)
        assertEquals(6_380_000, cues[2].startMs) // 1:46:20
        assertEquals(11430, cues[2].y)
    }

    @Test fun toleratesBomCrlfIdsNotesAndShortTimestamps() {
        val text = "﻿WEBVTT\r\n\r\nNOTE hello\r\n\r\nthumb-1\r\n00:05.500 --> 00:10.000\r\nhttps://cdn.x/s1.jpg#xywh=0,0,100,50\r\n\r\n" +
            "thumb-2\r\n00:10.000 --> 00:15.000\r\n/abs/s2.jpg#xywh=100,0,100,50\r\n"
        val cues = SpriteVtt.parse(text, "https://h.example/dir/p.vtt")
        assertEquals(2, cues.size)
        assertEquals(5_500, cues[0].startMs)
        assertEquals("https://cdn.x/s1.jpg", cues[0].imageUrl)
        assertEquals("https://h.example/abs/s2.jpg", cues[1].imageUrl)
    }

    @Test fun supportsMultipleSpriteImagesAndSortsByStart() {
        val text = """
            WEBVTT

            00:00:10.000 --> 00:00:20.000
            sprite2.jpg#xywh=0,0,160,90

            00:00:00.000 --> 00:00:10.000
            sprite1.jpg#xywh=0,0,160,90
        """.trimIndent()
        val cues = SpriteVtt.parse(text, vtt)
        assertEquals(listOf(0L, 10_000L), cues.map { it.startMs })
        assertEquals(listOf("sprite1.jpg", "sprite2.jpg"), cues.map { it.imageUrl.substringAfterLast('/') })
    }

    @Test fun skipsMalformedCues() {
        val text = """
            WEBVTT

            00:00:00.000 --> 00:00:10.000
            sprite.jpg

            00:00:10.000 --> 00:00:05.000
            sprite.jpg#xywh=0,0,160,90

            00:00:20.000 --> 00:00:30.000
            sprite.jpg#xywh=0,0,0,90

            00:00:30.000 --> 00:00:40.000
            sprite.jpg#xywh=0,0,160,90
        """.trimIndent()
        val cues = SpriteVtt.parse(text, vtt)
        assertEquals(1, cues.size)
        assertEquals(30_000, cues[0].startMs)
        assertEquals(0, SpriteVtt.parse("", vtt).size)
    }

    @Test fun cueLookupBoundaries() {
        val cues = SpriteVtt.parse(real, vtt)
        assertEquals(0, SpriteVtt.cueAt(cues, 0)!!.x)
        assertEquals(0, SpriteVtt.cueAt(cues, 9_999)!!.x)
        assertEquals(160, SpriteVtt.cueAt(cues, 10_000)!!.x) // boundary belongs to the next cue
        assertEquals(160, SpriteVtt.cueAt(cues, 19_999)!!.x)
        assertEquals(160, SpriteVtt.cueAt(cues, 25_000)!!.x) // gap -> previous cue
        assertEquals(480, SpriteVtt.cueAt(cues, 9_999_999)!!.x) // past the end -> last
        assertEquals(0, SpriteVtt.cueAt(cues, -5)!!.x) // before the first -> first
        assertNull(SpriteVtt.cueAt(emptyList(), 0))
    }

    @Test fun cropRectClampsToImage() {
        val cue = SpriteCue(0, 1, "u", 480, 11430, 160, 90)
        assertArrayEquals(intArrayOf(480, 11430, 640, 11520), SpriteVtt.cropRect(cue, 800, 11520))
        assertArrayEquals(intArrayOf(480, 11430, 500, 11500), SpriteVtt.cropRect(cue, 500, 11500)) // truncated sheet
        assertNull(SpriteVtt.cropRect(cue, 400, 11520)) // outside
    }

    @Test fun lruEvictsOldestByWeightAndKeepsRecentlyUsed() {
        val evicted = ArrayList<String>()
        val lru = LruMap<String, Int>(10, { it.toLong() }, { k, _ -> evicted += k })
        lru.put("a", 4); lru.put("b", 4)
        lru["a"] // touch
        lru.put("c", 4) // 12 > 10 -> evict b (oldest)
        assertEquals(listOf("b"), evicted)
        assertEquals(8, lru.size)
        lru.put("huge", 50) // never evicts the entry just inserted, drops the rest
        assertEquals(1, lru.count)
        assertEquals(50, lru["huge"])
    }

    @Test fun quantizeGrid() {
        assertEquals(0, quantize(4_999, 5_000))
        assertEquals(5_000, quantize(5_000, 5_000))
        assertEquals(0, quantize(-3, 5_000))
    }
}
