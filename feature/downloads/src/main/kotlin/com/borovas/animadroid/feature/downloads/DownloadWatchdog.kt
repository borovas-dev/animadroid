@file:OptIn(UnstableApi::class)

package com.borovas.animadroid.feature.downloads

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Dead-man's switch for "the process was killed while downloads were pending".
 *
 * Why it exists: Media3's PlatformScheduler job is one-shot and fires immediately when the network already qualifies,
 * so after it ran nothing is left to resurrect us. The system's own START_STICKY restart cannot help either: since
 * Android 12 it runs in the background where startForeground() throws ForegroundServiceStartNotAllowedException
 * (observed on a real kill, so [AnimadroidDownloadService] is START_NOT_STICKY). A JobScheduler job can run
 * network work in the background (but may not start a foreground service, see [DownloadWatchdogService]).
 *
 * While work is pending the coordinator re-arms a persisted job every ~20 s with 45 s minimum latency, so it never
 * fires while we are alive. If the process dies (OOM kill, crash, reboot) the job fires within about a minute
 * (once the network requirement holds) and restarts the service, which resumes the persisted downloads.
 * When nothing is pending the job is cancelled.
 */
object DownloadWatchdog {
    private const val JOB_ID = 4720
    private const val LATENCY_MS = 45_000L
    const val REARM_INTERVAL_MS = 20_000L

    fun arm(context: Context, wifiOnly: Boolean) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, DownloadWatchdogService::class.java))
            .setMinimumLatency(LATENCY_MS)
            .setRequiredNetworkType(if (wifiOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        runCatching { scheduler.schedule(job) }
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
    }
}

/**
 * Fires only if the app died with downloads pending. JobScheduler jobs are NOT an exemption for starting a
 * foreground service from the background (verified on device: startForeground denied), so the job itself hosts the
 * transfers: starting the process (Application.onCreate -> coordinator.start()) resumes the persisted DownloadManager
 * and the job stays running until it is idle. Foreground promotion is attempted as a best effort (works whenever
 * the app happens to be visible). The system may stop the job after its runtime limit; returning true reschedules it.
 */
class DownloadWatchdogService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        runCatching { DownloadService.startForeground(this, AnimadroidDownloadService::class.java) }
        val coordinator = EntryPointAccessors.fromApplication(applicationContext, DownloadServiceEntryPoint::class.java).coordinator()
        work = scope.launch {
            coordinator.awaitIdle()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        return true
    }
}
