package com.borovas.animadroid

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.borovas.animadroid.feature.downloads.DownloadCoordinator
import com.borovas.animadroid.core.remoteconfig.RemoteConfigRepository
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AnimadroidApp : Application() {
    @Inject lateinit var downloads: Lazy<DownloadCoordinator>
    @Inject lateinit var remoteConfig: Lazy<RemoteConfigRepository>
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Lightweight: the coordinator spins the Media3 manager up on its own scope, so downloads that were
        // queued / waiting for Wi-Fi / interrupted by process death are picked up without opening the Downloads
        // screen (this also runs when the JobScheduler restart job starts the process).
        downloads.get().start()
        // Providers read overrides via remoteConfig.forProvider(id); this just refreshes the cache (ETag, signed).
        appScope.launch { remoteConfig.get().refresh() }
        // Foreground time is reset when the user is back: resume downloads the system paused (Android 15 timeout).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = downloads.get().resumeSystemPaused()
        })
    }
}
