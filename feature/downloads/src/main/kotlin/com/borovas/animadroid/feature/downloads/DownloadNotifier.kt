@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Intent extra that makes MainActivity open the Downloads tab. */
const val EXTRA_OPEN_DOWNLOADS = "com.borovas.animadroid.OPEN_DOWNLOADS"

/**
 * Event notifications that outlive the foreground service: completed, failed, waiting for Wi-Fi and
 * paused-by-system. (The ongoing progress notification belongs to [AnimadroidDownloadService].) Posting silently
 * no-ops without the POST_NOTIFICATIONS permission.
 */
@Singleton
class DownloadNotifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        nm.createNotificationChannel(
            NotificationChannel(EVENTS_CHANNEL, context.getString(R.string.download_events_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.download_events_channel_description) },
        )
    }

    fun completed(key: String, title: String, episode: String) =
        post(key.hashCode(), "Downloaded", "$title · ep $episode", openDownloads())

    fun failed(key: String, title: String, episode: String, reason: String) =
        post(key.hashCode(), "Download failed: $title · ep $episode", reason, openDownloads())

    fun waitingForWifi(wifiOnly: Boolean) {
        post(
            ID_WAITING, if (wifiOnly) "Waiting for Wi-Fi" else "Waiting for network",
            "Downloads resume automatically when ${if (wifiOnly) "Wi-Fi" else "a connection"} is available",
            openDownloads(), ongoing = true,
        )
    }

    fun clearWaiting() = nm.cancel(ID_WAITING)

    /** Tapping starts the service in the foreground (allowed: user interaction) and clears the system stop reason. */
    fun pausedBySystem() {
        val resume = PendingIntent.getForegroundService(
            context, 10,
            Intent(context, AnimadroidDownloadService::class.java)
                .setAction(AnimadroidDownloadService.ACTION_RESUME_SYSTEM)
                .putExtra(androidx.media3.exoplayer.offline.DownloadService.KEY_FOREGROUND, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(ID_SYSTEM_PAUSE, "Downloads paused", DownloadPolicy.SYSTEM_PAUSE_TEXT, resume)
    }

    fun clearPausedBySystem() = nm.cancel(ID_SYSTEM_PAUSE)

    private fun openDownloads(): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            it.putExtra(EXTRA_OPEN_DOWNLOADS, true)
            PendingIntent.getActivity(context, 11, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    @SuppressLint("MissingPermission") // checked inside notify() via areNotificationsEnabled
    private fun post(id: Int, title: String, text: String, tap: PendingIntent?, ongoing: Boolean = false) {
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(context, EVENTS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { nm.notify(id, n) }
    }

    private companion object {
        const val EVENTS_CHANNEL = "download_events"
        const val ID_WAITING = 4712
        const val ID_SYSTEM_PAUSE = 4713
    }
}
