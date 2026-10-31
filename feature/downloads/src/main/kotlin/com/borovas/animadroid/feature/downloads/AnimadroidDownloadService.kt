@file:OptIn(UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface DownloadServiceEntryPoint {
    fun coordinator(): DownloadCoordinator
}

/**
 * Foreground service (type dataSync) that hosts the shared [DownloadManager] and shows one ongoing progress
 * notification with Pause all / Resume all / Cancel all actions.
 */
class AnimadroidDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {
    private val coordinator: DownloadCoordinator
        get() = EntryPointAccessors.fromApplication(applicationContext, DownloadServiceEntryPoint::class.java).coordinator()

    override fun getDownloadManager(): DownloadManager = coordinator.manager

    // JobScheduler-backed: restarts this service when the manager's requirements (Wi-Fi / network) are met again
    // after the process was killed, the device rebooted or the service stopped while waiting. See [DownloadPolicy].
    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    /**
     * Android 15+ stops `dataSync` foreground services after ~6h and calls this; failing to stop within seconds
     * crashes the app. Pause what is running with a system stop reason, tell the user how to resume, then stop.
     * (Media3's default would only stopSelf() and leave the downloads silently dead-in-the-water.)
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        coordinator.pauseForSystemTimeout()
        super.onTimeout(startId, fgsType)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> coordinator.pauseAll()
            ACTION_RESUME_ALL -> coordinator.resumeAll()
            ACTION_CANCEL_ALL -> coordinator.cancelAll()
            ACTION_RESUME_SYSTEM -> coordinator.resumeSystemPaused()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val mains = downloads.filter { DownloadIds.isMain(it.request.id) && it.state != Download.STATE_REMOVING }
        val active = mains.filter { it.state == Download.STATE_DOWNLOADING || it.state == Download.STATE_QUEUED || it.state == Download.STATE_RESTARTING }
        val paused = mains.filter { it.state == Download.STATE_STOPPED }
        val first = (active.firstOrNull { it.state == Download.STATE_DOWNLOADING } ?: active.firstOrNull() ?: paused.firstOrNull())
        val label = first?.let { DownloadPayload.decode(it.request.data).label }.orEmpty()
        val percents = active.map { it.percentDownloaded }.filter { it >= 0f }
        val avg = if (percents.isEmpty()) -1f else percents.average().toFloat()

        val title = when {
            active.isEmpty() && paused.isNotEmpty() -> "Downloads paused"
            active.size > 1 -> "Downloading ${active.size} episodes"
            else -> "Downloading episode"
        }
        val text = when {
            notMetRequirements != 0 && active.isNotEmpty() -> "Waiting for Wi-Fi"
            label.isNotEmpty() -> label
            else -> ""
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launch)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (active.isNotEmpty()) b.setProgress(100, avg.coerceAtLeast(0f).toInt(), avg < 0f)
        if (active.isNotEmpty()) b.addAction(0, "Pause all", action(ACTION_PAUSE_ALL, 1))
        if (paused.isNotEmpty()) b.addAction(0, "Resume all", action(ACTION_RESUME_ALL, 2))
        b.addAction(0, "Cancel all", action(ACTION_CANCEL_ALL, 3))
        return b.build()
    }

    private fun action(name: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, AnimadroidDownloadService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    internal companion object {
        const val JOB_ID = 4710
        const val ACTION_RESUME_SYSTEM = "com.borovas.animadroid.downloads.RESUME_SYSTEM"
        const val NOTIFICATION_ID = 4711
        const val CHANNEL_ID = "downloads"
        const val ACTION_PAUSE_ALL = "com.borovas.animadroid.downloads.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.borovas.animadroid.downloads.RESUME_ALL"
        const val ACTION_CANCEL_ALL = "com.borovas.animadroid.downloads.CANCEL_ALL"
    }
}
