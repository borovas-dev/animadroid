package com.borovas.animadroid

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.borovas.animadroid.core.database.BackupManager
import com.borovas.animadroid.core.datastore.DataStoreSettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Runs backup export/import against SAF uris and reports a one-line result message. */
@HiltViewModel
class BackupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backup: BackupManager,
    private val settings: DataStoreSettingsRepository,
) : ViewModel() {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun export(uri: Uri) = run("Export failed") {
        context.contentResolver.openOutputStream(uri)!!.use { backup.exportJson(it, settings.exportJson()) }
        "Backup exported"
    }

    fun import(uri: Uri) = run("Import failed") {
        val r = context.contentResolver.openInputStream(uri)!!.use { backup.importJson(it) }
        r.settingsJson?.let { settings.importJson(it) }
        "Imported ${r.anime} shows, ${r.progress} progress entries"
    }

    fun importAniCli(uri: Uri) = run("Import failed") {
        val n = context.contentResolver.openInputStream(uri)!!.use { backup.importAniCliHistory(it) }
        "Imported $n entries from ani-cli"
    }

    private fun run(failure: String, block: suspend () -> String) {
        viewModelScope.launch {
            _messages.emit(runCatching { block() }.getOrElse { "$failure: ${it.message}" })
        }
    }
}
