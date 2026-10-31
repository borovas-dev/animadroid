@file:OptIn(UnstableApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.scheduler.Requirements
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Background-download design (rationale; the pure helpers here are unit tested):
 *
 *  - Engine: Media3 DownloadManager hosted by a `dataSync` foreground service.
 *  - Restart after process death / reboot / "waiting for Wi-Fi": Media3's PlatformScheduler (JobScheduler, persisted,
 *    needs RECEIVE_BOOT_COMPLETED). While downloads wait for [requirementsFor] the service stops itself and a
 *    network-constrained job is registered; when the network qualifies the job restarts the service. This is the
 *    mechanism Media3 supports and works on minSdk 31 without extra dependencies.
 *  - Not used: user-initiated data transfer jobs (API 34 `JobInfo.Builder.setUserInitiated(true)`). They lift the
 *    dataSync time limit, but need API 34+ (minSdk is 31), run in a JobService with their own notification and have
 *    no DownloadManager integration, so adopting them would mean replacing Media3's download engine. Instead the
 *    ~6h `dataSync` limit of Android 15+ is handled gracefully: see AnimadroidDownloadService.onTimeout (pause with
 *    [STOP_REASON_SYSTEM], tell the user, resume on notification tap or next app open).
 */
object DownloadPolicy {
    /** Stop reason set by the user pressing pause. */
    const val STOP_REASON_USER = 1

    /** Stop reason set when the system times the foreground service out (Android 15+). */
    const val STOP_REASON_SYSTEM = 2

    const val SYSTEM_PAUSE_TEXT = "Paused by system – tap to resume"

    /** Wi-Fi only -> unmetered network required, otherwise any connected network. */
    fun requirementsFor(wifiOnly: Boolean): Requirements =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    /** Turns a Media3 download failure into one short line for the UI and the failure notification. */
    fun describeFailure(error: Throwable?): String {
        var t = error
        var depth = 0
        while (t != null && depth++ < 8) {
            when (t) {
                is HttpDataSource.InvalidResponseCodeException -> return when (t.responseCode) {
                    401, 403 -> "Server refused access (HTTP ${t.responseCode}); the link may have expired"
                    404, 410 -> "Video not found on server (HTTP ${t.responseCode})"
                    in 500..599 -> "Server error (HTTP ${t.responseCode})"
                    else -> "Server returned HTTP ${t.responseCode}"
                }
                is UnknownHostException, is ConnectException -> return "Could not reach the server"
                is SocketTimeoutException -> return "Connection timed out"
            }
            t = t.cause
        }
        val msg = error?.message?.takeIf { it.isNotBlank() }
        return msg?.let { "Download failed: $it" } ?: "Download failed"
    }
}
