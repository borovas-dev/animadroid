package com.borovas.animadroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.remoteconfig.UpdateChecker
import com.borovas.animadroid.core.remoteconfig.UpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@HiltViewModel
class UpdateViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val settings: SettingsRepository,
) : ViewModel() {
    val state: StateFlow<UpdateState> = checker.state

    init {
        viewModelScope.launch {
            val s = settings.settings.first()
            if (s.checkForUpdates) checker.check(s.includePrereleases)
        }
    }

    fun download() { viewModelScope.launch { checker.download() } }
    fun dismiss() = checker.dismiss()
}

/**
 * Launches the system package installer for [apk]. Returns false when the user first has to grant "install unknown
 * apps" to animadroid: the matching settings screen is opened instead and the user taps Install again.
 */
fun installApk(context: Context, apk: File): Boolean {
    if (!context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
    context.startActivity(
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    return true
}
