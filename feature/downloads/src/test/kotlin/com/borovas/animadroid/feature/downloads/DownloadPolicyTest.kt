@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.scheduler.Requirements
import com.borovas.animadroid.core.model.DownloadState
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class DownloadPolicyTest {
    @Test fun wifiOnlyRequiresUnmeteredNetwork() {
        val r = DownloadPolicy.requirementsFor(wifiOnly = true)
        assertEquals(Requirements.NETWORK_UNMETERED, r.requirements and Requirements.NETWORK_UNMETERED)
        assertTrue(r.isUnmeteredNetworkRequired)
    }

    @Test fun anyNetworkWhenNotWifiOnly() {
        val r = DownloadPolicy.requirementsFor(wifiOnly = false)
        assertEquals(Requirements.NETWORK, r.requirements)
        assertTrue(r.isNetworkRequired)
        assertTrue(!r.isUnmeteredNetworkRequired)
    }

    private fun http(code: Int) = HttpDataSource.InvalidResponseCodeException(
        code, null, null, emptyMap(), DataSpec(android.net.Uri.EMPTY), ByteArray(0),
    )

    @Test fun describesHttpFailures() {
        assertTrue(DownloadPolicy.describeFailure(http(403)).contains("HTTP 403"))
        assertTrue(DownloadPolicy.describeFailure(http(404)).startsWith("Video not found"))
        assertEquals("Server error (HTTP 503)", DownloadPolicy.describeFailure(http(503)))
        assertEquals("Server returned HTTP 418", DownloadPolicy.describeFailure(http(418)))
    }

    @Test fun describesNetworkFailuresThroughCauseChain() {
        assertEquals("Could not reach the server", DownloadPolicy.describeFailure(IOException("x", UnknownHostException("h"))))
        assertEquals("Connection timed out", DownloadPolicy.describeFailure(IOException(IOException(SocketTimeoutException()))))
        assertEquals("Download failed: disk full", DownloadPolicy.describeFailure(IOException("disk full")))
        assertEquals("Download failed", DownloadPolicy.describeFailure(null))
    }

    private fun part(id: String, state: Int, stop: Int = 0, failure: String? = null) =
        PartState(id, state, 0, 0f, stop, failure)

    @Test fun failedAggregateCarriesFailureText() {
        val agg = DownloadAggregator.aggregate(listOf(part("k", PartState.FAILED, failure = "HTTP 403"), part("k#audio", PartState.COMPLETED)))!!
        assertEquals(DownloadState.FAILED, agg.state)
        assertEquals("HTTP 403", agg.reason)
    }

    @Test fun systemPauseGetsExplanationUserPauseDoesNot() {
        val sys = DownloadAggregator.aggregate(listOf(part("k", PartState.STOPPED, DownloadPolicy.STOP_REASON_SYSTEM)))!!
        assertEquals(DownloadState.PAUSED, sys.state)
        assertEquals(DownloadPolicy.SYSTEM_PAUSE_TEXT, sys.reason)
        val user = DownloadAggregator.aggregate(listOf(part("k", PartState.STOPPED, DownloadPolicy.STOP_REASON_USER)))!!
        assertNull(user.reason)
        val mixed = DownloadAggregator.aggregate(
            listOf(part("k", PartState.STOPPED, DownloadPolicy.STOP_REASON_SYSTEM), part("k#audio", PartState.STOPPED, DownloadPolicy.STOP_REASON_USER)),
        )!!
        assertNull(mixed.reason)
    }

    @Test fun healthyStatesHaveNoReason() {
        assertNull(DownloadAggregator.aggregate(listOf(part("k", PartState.DOWNLOADING)))!!.reason)
        assertNull(DownloadAggregator.aggregate(listOf(part("k", PartState.COMPLETED)))!!.reason)
    }
}
