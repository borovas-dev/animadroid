package com.borovas.animadroid.core.remoteconfig

import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

class UpdateTest {
    @Test fun semverCompare() {
        assertTrue(isNewerVersion("v0.2.0", "0.1.0"))
        assertTrue(isNewerVersion("1.0.0", "0.9.9"))
        assertTrue(isNewerVersion("0.1.10", "0.1.9"))
        assertFalse(isNewerVersion("0.1.0", "0.1.0"))
        assertFalse(isNewerVersion("v0.1", "0.1.0"))
        assertFalse(isNewerVersion("0.0.9", "0.1.0"))
        assertTrue(isNewerVersion("1.0.0", "1.0.0-rc.1"))
        assertFalse(isNewerVersion("1.0.0-rc.1", "1.0.0"))
        assertTrue(isNewerVersion("1.0.0-rc.2", "1.0.0-rc.1"))
        assertTrue(isNewerVersion("1.0.0-rc.10", "1.0.0-rc.9"))
        assertEquals(SemVer.parse("1.2.3"), SemVer.parse("v1.2.3+build5"))
    }

    @Test fun semverGarbage() {
        assertNull(SemVer.parse("latest"))
        assertNull(SemVer.parse(""))
        assertFalse(isNewerVersion("nightly", "0.1.0"))
        assertFalse(isNewerVersion("1.0.0", "dev"))
    }

    private fun release(tag: String, apk: Boolean = true, pre: Boolean = false): String {
        val apkAsset = if (apk) ",{\"name\":\"app.apk\",\"browser_download_url\":\"https://gh/app.apk\"}" else ""
        return "{\"tag_name\":\"$tag\",\"body\":\"- fixes\",\"html_url\":\"https://gh/r\",\"draft\":false," +
            "\"prerelease\":$pre,\"unknown\":1,\"assets\":[{\"name\":\"notes.txt\",\"browser_download_url\":\"https://gh/n\"}$apkAsset]}"
    }

    @Test fun parseNewer() {
        val s = parseRelease(release("v0.2.0"), "0.1.0") as UpdateState.Available
        assertEquals("0.2.0", s.version); assertEquals("- fixes", s.changelog); assertEquals("https://gh/app.apk", s.apkUrl)
    }

    @Test fun parseIgnores() {
        assertEquals(UpdateState.UpToDate, parseRelease(release("v0.1.0"), "0.1.0"))
        assertEquals(UpdateState.UpToDate, parseRelease(release("v0.2.0", apk = false), "0.1.0"))
        assertEquals(UpdateState.UpToDate, parseRelease(release("v0.2.0", pre = true), "0.1.0"))
    }

    @Test fun prereleaseList() {
        val list = "[" + listOf(release("v0.3.0-alpha1", pre = true), release("v0.2.0"), release("v0.3.0-alpha2", pre = true)).joinToString(",") + "]"
        // opted out: only the stable release counts
        assertEquals("0.2.0", (parseReleaseList(list, "0.1.0", false) as UpdateState.Available).version)
        // opted in: newest by semver, regardless of list order
        assertEquals("0.3.0-alpha2", (parseReleaseList(list, "0.1.0", true) as UpdateState.Available).version)
        // an alpha updates to a later alpha, and to the final release
        assertEquals("0.3.0-alpha2", (parseReleaseList(list, "0.3.0-alpha1", true) as UpdateState.Available).version)
        assertEquals(UpdateState.UpToDate, parseReleaseList(list, "0.3.0-alpha2", true))
        assertEquals("0.2.0", (parseReleaseList(list, "0.2.0-alpha1", false) as UpdateState.Available).version)
    }

    @Test fun checkAndDownload() = runBlocking {
        val server = MockWebServer().also { it.start() }
        val cache = kotlin.io.path.createTempDirectory().toFile()
        try {
            server.enqueue(MockResponse.Builder().code(200).body(
                release("v0.2.0").replace("https://gh/app.apk", server.url("/app.apk").toString()),
            ).build())
            server.enqueue(MockResponse.Builder().code(200).body("APKDATA").build())
            val c = UpdateChecker(OkHttpClient(), "0.1.0", cache, "o/r", server.url("/").toString().trimEnd('/'))
            c.check()
            assertTrue(c.state.value is UpdateState.Available)
            c.download()
            val ready = c.state.value as UpdateState.ReadyToInstall
            assertEquals("APKDATA", ready.apk.readText())
            assertEquals("/repos/o/r/releases/latest", server.takeRequest().url.encodedPath)
        } finally { server.close(); cache.deleteRecursively() }
    }

    @Test fun noReleasesIsUpToDate() = runBlocking {
        val server = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse.Builder().code(404).build())
            val c = UpdateChecker(OkHttpClient(), "0.1.0", File("."), "o/r", server.url("/").toString().trimEnd('/'))
            c.check(); assertEquals(UpdateState.UpToDate, c.state.value)
        } finally { server.close() }
    }
}
