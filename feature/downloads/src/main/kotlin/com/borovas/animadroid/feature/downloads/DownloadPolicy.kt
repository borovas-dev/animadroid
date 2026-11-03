@file:OptIn(UnstableApi::class)
@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import java.util.Locale
import androidx.annotation.StringRes
import android.content.Context
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

    /** Stored as the pause reason (compared by value); UI and notifications show the localized `downloads_paused_by_system`. */
    const val SYSTEM_PAUSE_TEXT = "Paused by system – tap to resume"

    /** Wi-Fi only -> unmetered network required, otherwise any connected network. */
    fun requirementsFor(wifiOnly: Boolean): Requirements =
        Requirements(if (wifiOnly) Requirements.NETWORK_UNMETERED else Requirements.NETWORK)

    /** Automatic retries of a failed episode before it is left FAILED for the user to retry. */
    const val MAX_AUTO_RETRIES = 3

    private const val BASE_BACKOFF_MS = 30_000L
    private const val MAX_BACKOFF_MS = 10 * 60_000L

    /** Delay before automatic retry number [attempt] (1-based): 30s, 2min, 8min, capped at 10min. */
    fun backoffMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceIn(0, 10) * 2
        return (BASE_BACKOFF_MS shl shift).coerceAtMost(MAX_BACKOFF_MS)
    }

    /** [attemptsSoFar] automatic retries have already been made. */
    fun shouldRetry(attemptsSoFar: Int): Boolean = attemptsSoFar < MAX_AUTO_RETRIES

    /**
     * True when the failure looks like an expired / invalid signed URL (401/403/404/410). Every automatic retry
     * re-resolves the stream through ProviderChain anyway; this only labels the failure.
     */
    fun isExpiredLink(error: Throwable?): Boolean {
        var t = error
        var depth = 0
        while (t != null && depth++ < 8) {
            if (t is HttpDataSource.InvalidResponseCodeException) return t.responseCode in intArrayOf(401, 403, 404, 410)
            t = t.cause
        }
        return false
    }

    /** Turns a Media3 download failure into one short line for the UI and the failure notification. */
    fun describeFailure(error: Throwable?, context: Context? = null): String {
        // Without a context (JVM unit tests) the English format strings are used.
        fun s(@StringRes id: Int, english: String, vararg args: Any): String =
            context?.getString(id, *args) ?: String.format(Locale.ROOT, english, *args)
        var t = error
        var depth = 0
        while (t != null && depth++ < 8) {
            when (t) {
                is HttpDataSource.InvalidResponseCodeException -> return when (t.responseCode) {
                    401, 403 -> s(R.string.downloads_fail_refused, "Server refused access (HTTP %d); the link may have expired", t.responseCode)
                    404, 410 -> s(R.string.downloads_fail_not_found, "Video not found on server (HTTP %d)", t.responseCode)
                    in 500..599 -> s(R.string.downloads_fail_server_error, "Server error (HTTP %d)", t.responseCode)
                    else -> s(R.string.downloads_fail_http, "Server returned HTTP %d", t.responseCode)
                }
                is UnknownHostException, is ConnectException -> return s(R.string.downloads_fail_unreachable, "Could not reach the server")
                is SocketTimeoutException -> return s(R.string.downloads_fail_timeout, "Connection timed out")
            }
            t = t.cause
        }
        val msg = error?.message?.takeIf { it.isNotBlank() }
        return msg?.let { s(R.string.downloads_fail_with_message, "Download failed: %s", it) } ?: s(R.string.downloads_fail_generic, "Download failed")
    }
}
