package com.borovas.animadroid.feature.settings

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.borovas.animadroid.core.sync.SyncRepository
import com.borovas.animadroid.core.sync.SyncService
import com.borovas.animadroid.core.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TrackingViewModel @Inject constructor(private val sync: SyncRepository) : ViewModel() {
    val state: StateFlow<SyncState> = sync.state
    fun authorizeUrl(service: SyncService) = sync.authorizeUrl(service)
    fun disconnect(service: SyncService) { viewModelScope.launch { sync.disconnect(service) } }
    fun syncNow() { viewModelScope.launch { sync.syncNow() } }
}

private fun openCustomTab(context: Context, url: String) {
    CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
}

/** "Tracking" group content: AniList / MyAnimeList connect, disconnect and "Sync now". Shown inside [Group] by SettingsScreen. */
@Composable
internal fun TrackingContent(vm: TrackingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column {
        for (service in SyncService.entries) {
            val s = state.services.getValue(service)
            val name = if (service == SyncService.ANILIST) "AniList" else "MyAnimeList"
            ListItem(
                headlineContent = { Text(name) },
                supportingContent = {
                    Text(
                        when {
                            !s.configured -> stringResource(R.string.tracking_not_configured)
                            s.connected -> stringResource(R.string.tracking_connected_as, s.username ?: "")
                            else -> stringResource(R.string.tracking_not_connected)
                        },
                    )
                },
                trailingContent = {
                    if (s.configured) {
                        if (s.connected) {
                            TextButton({ vm.disconnect(service) }) { Text(stringResource(R.string.tracking_disconnect)) }
                        } else {
                            TextButton({ vm.authorizeUrl(service)?.let { openCustomTab(context, it) } }) {
                                Text(stringResource(R.string.tracking_connect))
                            }
                        }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        if (state.services.values.any { it.connected }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton({ vm.syncNow() }, enabled = !state.syncing) { Text(stringResource(R.string.tracking_sync_now)) }
                if (state.pending > 0) Text(stringResource(R.string.tracking_pending, state.pending), Modifier.padding(top = 10.dp))
            }
        }
        state.error?.let {
            Text(
                stringResource(R.string.tracking_error, it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.error,
            )
        }
    }
}
