@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package com.borovas.animadroid.feature.recommend

import com.borovas.animadroid.core.designsystem.R as DR
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import com.borovas.animadroid.core.designsystem.AnimadroidTheme
import com.borovas.animadroid.core.designsystem.Cover
import com.borovas.animadroid.core.designsystem.EmptyState
import com.borovas.animadroid.core.designsystem.PosterCarousel
import com.borovas.animadroid.core.model.Anime

fun EntryProviderScope<NavKey>.recommendEntry(
    onAnimeClick: (Anime) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
) {
    entry<RecommendRoute> {
        val vm: RecommendViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        RecommendScreen(state, vm::refresh, onAnimeClick, onOpenSettings, onBack)
    }
}

@Composable
fun RecommendScreen(
    state: RecommendUiState,
    onRefresh: () -> Unit,
    onAnimeClick: (Anime) -> Unit,
    onOpenSettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.recommend_title)) },
                navigationIcon = { IconButton(onBack) { Icon(Icons.Rounded.ArrowBack, stringResource(DR.string.common_back)) } },
                actions = {
                    if (state.modelReady) IconButton(onRefresh, enabled = !state.generating) { Icon(Icons.Rounded.Refresh, stringResource(R.string.recommend_refresh)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            if (state.generating) {
                LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Text(
                    stringResource(R.string.recommend_thinking),
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                )
            }
            state.error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
            when {
                !state.modelReady && state.model != null -> Column(Modifier.padding(16.dp)) {
                    ModelMissingCard(state.model, onOpenSettings)
                }
                state.items.isEmpty() && !state.generating -> EmptyState(
                    Icons.Rounded.AutoAwesome, stringResource(R.string.recommend_empty_title),
                    stringResource(R.string.recommend_empty_body),
                )
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.items, key = { it.anime.providerId + it.anime.id }) { r -> RecommendRow(r) { onAnimeClick(r.anime) } }
                }
            }
        }
    }
}

@Composable
private fun RecommendRow(r: Recommendation, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(24.dp)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Cover(r.anime.coverUrl, r.anime.title, Modifier.size(width = 72.dp, height = 104.dp).clip(RoundedCornerShape(16.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.anime.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (r.reason.isNotBlank()) Text(r.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModelMissingCard(model: ModelState?, onOpenSettings: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null)
                Column {
                    Text(stringResource(R.string.recommend_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (model) {
                            is ModelState.Downloading -> stringResource(R.string.recommend_model_downloading, model.fraction?.let { "${(it * 100).toInt()}%" } ?: stringResource(R.string.recommend_model_waiting))
                            is ModelState.Error -> model.message
                            else -> stringResource(R.string.recommend_model_missing)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            FilledTonalButton(onOpenSettings) { Text(stringResource(R.string.recommend_setup)) }
        }
    }
}

/** Home-screen section: carousel of picks with the model's one-line reason. Shown only when the feature is enabled. */
@Composable
fun RecommendCard(
    onAnimeClick: (Anime) -> Unit,
    onOpenSettings: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm: RecommendViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    RecommendSection(state, vm::refresh, onAnimeClick, onOpenSettings, onSeeAll, modifier)
}

@Composable
internal fun RecommendSection(
    state: RecommendUiState,
    onRefresh: () -> Unit,
    onAnimeClick: (Anime) -> Unit,
    onOpenSettings: () -> Unit,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            state.model == null -> Unit
            !state.modelReady -> ModelMissingCard(state.model, onOpenSettings)
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.recommend_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    if (state.items.isNotEmpty()) TextButton(onSeeAll) { Text(stringResource(R.string.recommend_see_all)) }
                    IconButton(onRefresh, enabled = !state.generating) { Icon(Icons.Rounded.Refresh, stringResource(R.string.recommend_refresh)) }
                }
                if (state.generating) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.recommend_thinking),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (state.items.isNotEmpty()) {
                    PosterCarousel(state.items, Modifier.height(280.dp), contentPadding = PaddingValues(0.dp)) { r, mod ->
                        RecommendPoster(r, { onAnimeClick(r.anime) }, mod)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendPoster(r: Recommendation, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = onClick)) {
        Cover(r.anime.coverUrl, r.anime.title, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)), startY = 200f)))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.anime.title, color = Color.White, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (r.reason.isNotBlank()) Text(r.reason, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------------------------------------------------------------- Settings section

/** Onboarding / management block rendered under the Settings switch when the recommender is enabled. */
@Composable
fun RecommendSettingsSection(modifier: Modifier = Modifier) {
    val vm: RecommendSettingsViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshRequirements() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importFile) }
    RecommendSettingsContent(
        state, vm::download, vm::cancel, vm::delete, vm::setWifiOnly, vm::setToken,
        onPickFile = { picker.launch(arrayOf("*/*")) }, modifier,
    )
}

@Composable
internal fun RecommendSettingsContent(
    state: RecommendSettingsUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onToken: (String) -> Unit,
    onPickFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.recommend_about, formatBytes(ModelSpec.SIZE_BYTES), ModelSpec.REPO),
            style = MaterialTheme.typography.bodyMedium,
        )
        when (val m = state.model) {
            is ModelState.Ready -> {
                Text(stringResource(R.string.recommend_model_installed, formatBytes(m.sizeBytes)), style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onDelete, Modifier.fillMaxWidth()) { Text(stringResource(R.string.recommend_delete_model)) }
            }
            is ModelState.Downloading -> {
                val label = when (m.phase) {
                    ModelState.Downloading.Phase.WAITING_FOR_NETWORK -> stringResource(if (state.wifiOnly) R.string.recommend_phase_wait_wifi else R.string.recommend_phase_wait_net)
                    ModelState.Downloading.Phase.DOWNLOADING -> stringResource(R.string.recommend_phase_downloading, formatBytes(m.bytes), formatBytes(m.total))
                    ModelState.Downloading.Phase.VERIFYING -> stringResource(R.string.recommend_phase_verifying)
                    ModelState.Downloading.Phase.IMPORTING -> stringResource(R.string.recommend_phase_importing, formatBytes(m.bytes))
                }
                Text(label, style = MaterialTheme.typography.titleSmall)
                m.fraction?.let { LinearWavyProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
                    ?: LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text(stringResource(R.string.recommend_cancel)) }
            }
            else -> {
                if (m is ModelState.Error) Text(m.message, color = MaterialTheme.colorScheme.error)
                state.problems.forEach { Text(it.localized(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(state.wifiOnly, onWifiOnly)
                    Text(stringResource(R.string.recommend_wifi_only), Modifier.align(Alignment.CenterVertically))
                }
                Button(onDownload, Modifier.fillMaxWidth(), enabled = state.problems.isEmpty()) {
                    Text(if (m is ModelState.Error) stringResource(R.string.recommend_retry_download) else stringResource(R.string.recommend_download_model, formatBytes(ModelSpec.SIZE_BYTES)))
                }
                OutlinedButton(onPickFile, Modifier.fillMaxWidth()) { Text(stringResource(R.string.recommend_use_file)) }
                var token by remember { mutableStateOf(state.token) }
                OutlinedTextField(
                    token, { token = it; onToken(it) }, Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.recommend_hf_token)) },
                    supportingText = { Text(stringResource(R.string.recommend_hf_token_hint)) },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RecommendPreview() = AnimadroidTheme(dynamicColor = false) {
    val items = listOf(
        Recommendation(Anime("1", "hianime", "Mushishi", "x"), "Quiet episodic stories like the ones you finish."),
        Recommendation(Anime("2", "hianime", "Haibane Renmei", "x"), "Gentle, atmospheric and thoughtful."),
    )
    Column(Modifier.padding(16.dp)) {
        RecommendSection(RecommendUiState(ModelState.Ready(1), items), {}, {}, {}, {})
        RecommendSettingsContent(RecommendSettingsUiState(), {}, {}, {}, {}, {}, {})
    }
}

@Composable
private fun RequirementProblem.localized(): String = when (this) {
    is RequirementProblem.NotEnoughRam -> stringResource(R.string.recommend_problem_ram, "%.1f".format(totalBytes / 1_073_741_824.0))
    RequirementProblem.Unsupported32BitDevice -> stringResource(R.string.recommend_problem_32bit)
    is RequirementProblem.NotEnoughSpace -> stringResource(R.string.recommend_problem_space, formatBytes(neededBytes), formatBytes(freeBytes))
}
