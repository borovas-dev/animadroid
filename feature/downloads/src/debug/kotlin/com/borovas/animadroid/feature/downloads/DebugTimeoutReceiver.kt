package com.borovas.animadroid.feature.downloads

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo

/**
 * Debug-only (lives in the debug source set, absent from release): invokes the running service's real
 * [AnimadroidDownloadService.onTimeout] exactly as Android 15 would after the ~6h `dataSync` limit, so the cutoff
 * handling can be verified on an emulator without waiting hours.
 */
class DebugTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = AnimadroidDownloadService.instance
        if (service == null) {
            android.util.Log.w("DebugTimeout", "download service not running")
            return
        }
        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
