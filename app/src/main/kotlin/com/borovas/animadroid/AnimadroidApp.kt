package com.borovas.animadroid

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.borovas.animadroid.feature.downloads.DownloadCoordinator
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AnimadroidApp : Application() {
    @Inject lateinit var downloads: Lazy<DownloadCoordinator>

    override fun onCreate() {
        super.onCreate()
        // Lightweight: the coordinator spins the Media3 manager up on its own scope, so downloads that were
        // queued / waiting for Wi-Fi / interrupted by process death are picked up without opening the Downloads
        // screen (this also runs when the JobScheduler restart job starts the process).
        downloads.get().start()
        // Foreground time is reset when the user is back: resume downloads the system paused (Android 15 timeout).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = downloads.get().resumeSystemPaused()
        })
    }
}
