package com.borovas.animadroid.provider.shinden

import com.borovas.animadroid.core.model.TranslationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixtures named *_players*, search*, series, episodes* and player_show_external are trimmed real shinden.pl responses (2026-11-01, guest). */
class ShindenParsersTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun searchParsesIdsCoversYearAndEpisodes() {
        val r = ShindenParsers.parseSearch(res("search.html"), "shinden", "https://shinden.pl")
        assertEquals(4, r.size)
        assertEquals("61064-sousou-no-frieren", r[0].id)
        assertEquals("Sousou no Frieren", r[0].title)
        assertEquals(2023, r[0].year)
        assertEquals(28, r[0].subEpisodes)
        assertEquals("https://shinden.pl/res/images/280x400/372465.webp?v=1792160755", r[0].coverUrl)
        assertNull(r[3].subEpisodes) // data-episodes="0"
        assertEquals("shinden", r[0].providerId)
    }

    @Test fun searchAcceptsTitlesPrefix() {
        val r = ShindenParsers.parseSearch(res("search_titles.html"), "shinden", "https://shinden.pl")
        assertEquals("2395-death-note", r[0].id)
    }

    @Test fun detailsHaveSynopsisCoverAndNoMalId() {
        val d = ShindenParsers.parseDetails(res("series.html"))
        assertTrue(d.synopsis!!.startsWith("Król demonów został pokonany"))
        assertEquals("https://cdn.shinden.eu/cdn1/images/genuine/372465.webp?v=1791852320", d.cover)
        assertNull(d.malId)
    }

    @Test fun detailsFindMalIdWhenLinked() {
        val d = ShindenParsers.parseDetails("""<a href="https://myanimelist.net/anime/52991/Sousou_no_Frieren">MAL</a>""")
        assertEquals(52991, d.malId)
    }

    @Test fun episodeRowsAndPaging() {
        val html = res("episodes.html")
        val rows = ShindenParsers.parseEpisodeRows(html)
        assertEquals(6, rows.size)
        assertEquals("28", rows[0].number)
        assertEquals("227851", rows[0].id)
        assertEquals("To byłoby zawstydzające, gdybyśmy spotkali się ponownie", rows[0].title)
        assertEquals(2, ShindenParsers.pageCount(html))
        assertEquals(1, ShindenParsers.pageCount("<p>none</p>"))
    }

    @Test fun rowsWithoutLinksAreSkipped() {
        assertTrue(ShindenParsers.parseEpisodeRows(res("episodes_nolinks.html")).isEmpty())
        assertEquals(3, ShindenParsers.parseEpisodeRows(res("episodes_langs.html")).size)
    }

    @Test fun playersFromJsonBlock() {
        val p = ShindenParsers.parsePlayers(res("episode_players.html"))
        assertEquals(19, p.size)
        assertEquals(PlayerEntry("1824661", "Gdrive", "jp", "pl", 1080, "Tłumaczenie:  Zailex | Korekta: anaskor2201", false), p[0])
        assertEquals(listOf("Gdrive", "Mega", "Vidara", "Byse", "Byse", "Yourupload", "Gdrive", "Okru", "Gdrive", "Cda", "Voesx"), p.take(11).map { it.player })
        assertEquals(8, p.count { it.player == "Crunchyroll" })
        val n = ShindenParsers.parsePlayers(res("episode_players_netflix.html")).single()
        assertEquals("Netflix", n.player); assertEquals("pl", n.sub); assertEquals(1080, n.res)
        assertEquals(8, ShindenParsers.parsePlayers(res("episode_players_external_only.html")).size)
    }

    @Test fun playersFromLegacyButtons() {
        val html = """<table><tr><td><a data-episode='{"online_id":"77","player":"Sibnet","lang_audio":"pl","lang_subs":"","max_res":"720p","subs_author":null}'>x</a></td></tr></table>"""
        val p = ShindenParsers.parsePlayers(html).single()
        assertEquals(PlayerEntry("77", "Sibnet", "pl", "", 720, null, false), p)
    }

    @Test fun playerShowExternal() {
        val s = ShindenParsers.parsePlayerShow(res("player_show_external.html")) as PlayerShow.External
        assertEquals("http://www.crunchyroll.com/media-913131", s.url)
        assertEquals("Przejdź do Crunchyroll", s.label)
    }

    @Test fun playerShowRealIframes() {
        fun embed(n: String) = (ShindenParsers.parsePlayerShow(res("player_show_$n.html")) as PlayerShow.Embed).url
        assertEquals("https://ebd.cda.pl/800x450/1639263768", embed("cda"))
        assertEquals("https://ykfrtza.com/e/T1R2ZAa8nyc08", embed("vidara"))
        assertEquals("https://www.yourupload.com/embed/ecxsc4c856jE", embed("yourupload"))
        assertEquals("https://voe.sx/e/e0ljp4icxhgy", embed("voesx"))
        assertEquals("https://bysesukior.com/e/942k3dhlk95d", embed("byse"))
        assertTrue(embed("mega").startsWith("https://mega.nz/embed/"))
        assertTrue(embed("gdrive").startsWith("https://drive.google.com/file/d/"))
    }

    @Test fun playerShowIframeAndFailure() {
        val e = ShindenParsers.parsePlayerShow("""<div><iframe src="//video.sibnet.ru/shell.php?videoid=1"></iframe></div>""") as PlayerShow.Embed
        assertEquals("https://video.sibnet.ru/shell.php?videoid=1", e.url)
        val f = ShindenParsers.parsePlayerShow("""<div class="error message box player-message">Błędne wejście, nie wyświetlam odtwarzacza.</div>""")
        assertTrue((f as PlayerShow.Failure).message.startsWith("Błędne wejście"))
        assertTrue(ShindenParsers.parsePlayerShow("") is PlayerShow.Failure)
    }

    @Test fun idValidation() {
        assertTrue(ShindenParsers.isValidId("61064-sousou-no-frieren"))
        assertTrue(!ShindenParsers.isValidId("../etc"))
        assertTrue(!ShindenParsers.isValidId("frieren"))
    }

    private fun p(name: String, audio: String, sub: String, res: Int?, machine: Boolean = false) = PlayerEntry("1", name, audio, sub, res, null, machine)

    @Test fun rankingMapsModesToLanguages() {
        val all = listOf(
            p("Crunchyroll", "jp", "en", 480), p("Sibnet", "jp", "pl", 720), p("Cda", "pl", "", 1080), p("Streamwish", "jp", "en", 1080),
            p("Vk", "jp", "pl", 1080), p("Cda", "pl", "", 480), p("Gdrive", "jp", "pl", 1080),
        )
        val sub = ShindenSelection.rank(all, TranslationMode.SUB)
        assertEquals(listOf("Vk", "Sibnet", "Streamwish", "Gdrive", "Crunchyroll"), sub.map { it.player })
        val dub = ShindenSelection.rank(all, TranslationMode.DUB)
        assertEquals(listOf(1080, 480), dub.map { it.res })
        assertTrue(ShindenSelection.rank(listOf(p("Cda", "pl", "", 720)), TranslationMode.SUB).isEmpty())
    }

    @Test fun labels() {
        assertEquals("Polish dub/lektor", ShindenSelection.label(p("Cda", "pl", "", 1)))
        assertEquals("JP audio + PL subs", ShindenSelection.label(p("Cda", "jp", "pl", 1)))
    }

    @Test fun externalAndUnsupportedHostReasons() {
        assertNotNull(HostRegistry.externalReason("Crunchyroll"))
        assertNotNull(HostRegistry.externalReason("Netflix"))
        assertTrue(HostRegistry.externalReason("Mega")!!.contains("end-to-end"))
        assertNull(HostRegistry.externalReason("Sibnet"))
        assertNull(HostRegistry.externalReason("Cda"))
    }
}
