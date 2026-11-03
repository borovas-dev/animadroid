@file:OptIn(ExperimentalMaterial3Api::class)

package com.borovas.animadroid.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey

fun EntryProviderScope<NavKey>.healthEntry(onBack: () -> Unit) {
    entry<HealthRoute> {
        val vm: HealthViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        HealthScreen(state, onRun = vm::run, onBack = onBack)
    }
}

@Composable
fun HealthScreen(state: HealthUiState, onRun: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.health_back)) } },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, pad.calculateTopPadding() + 8.dp, 16.dp, pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.health_intro), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.health_chain, state.language, state.chain.joinToString(" > ").ifEmpty { "-" }),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onRun, enabled = !state.running, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (state.running) R.string.health_running else R.string.health_run_again))
                    }
                }
            }
            items(state.providers, key = { it.id }) { p -> ProviderCard(p) }
        }
    }
}

@Composable
private fun ProviderCard(p: ProviderHealth) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium)
            val tags = buildList {
                if (p.disabled) add(stringResource(R.string.health_disabled))
                else if (!p.inChain) add(stringResource(R.string.health_not_in_chain))
            }
            if (tags.isNotEmpty()) Text(tags.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            StepLine(stringResource(R.string.health_step_search), p.search)
            StepLine(stringResource(R.string.health_step_episodes), p.episodes)
            StepLine(stringResource(R.string.health_step_streams), p.streams)
        }
    }
}

@Composable
private fun StepLine(label: String, r: StepResult) {
    val icon = when (r.status) {
        StepStatus.OK -> "✅"
        StepStatus.FAILED -> "❌"
        StepStatus.SKIPPED -> "➖"
        StepStatus.PENDING -> "⏳"
    }
    val detail = when (r.status) {
        StepStatus.OK -> "${r.millis} ms"
        StepStatus.FAILED -> "${r.millis} ms - ${r.message}"
        else -> ""
    }
    Text("$icon $label  $detail".trim(), style = MaterialTheme.typography.bodyMedium)
}
