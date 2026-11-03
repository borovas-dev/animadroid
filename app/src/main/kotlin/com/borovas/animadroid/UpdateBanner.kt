package com.borovas.animadroid

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.borovas.animadroid.core.remoteconfig.UpdateState

/** Shows nothing unless a newer release exists (or one is downloading / failed after the user asked). */
@Composable
fun UpdateBanner(modifier: Modifier = Modifier, viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showNotes by remember { mutableStateOf(false) }
    val s = state
    val available = when (s) {
        is UpdateState.Available -> s
        is UpdateState.Downloading -> s.available
        is UpdateState.ReadyToInstall -> s.available
        else -> null
    }
    if (available == null) return
    Card(modifier.statusBarsPadding().fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.update_available, available.version), style = MaterialTheme.typography.titleSmall)
            if (showNotes && available.changelog.isNotBlank()) {
                Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                    Text(available.changelog, style = MaterialTheme.typography.bodySmall)
                }
            }
            when (s) {
                is UpdateState.Downloading -> {
                    Text(stringResource(R.string.update_downloading), style = MaterialTheme.typography.bodySmall)
                    if (s.progress >= 0f) LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (available.changelog.isNotBlank()) {
                        TextButton({ showNotes = !showNotes }) { Text(stringResource(R.string.update_changelog)) }
                    }
                    TextButton(viewModel::dismiss) { Text(stringResource(R.string.update_later)) }
                    TextButton({
                        if (s is UpdateState.ReadyToInstall) {
                            if (!installApk(context, s.apk)) {
                                Toast.makeText(context, R.string.update_allow_unknown, Toast.LENGTH_LONG).show()
                            }
                        } else viewModel.download()
                    }) { Text(stringResource(R.string.update_install)) }
                }
            }
        }
    }
}
