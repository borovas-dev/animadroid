@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.borovas.animadroid.feature.downloads

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.DownloadGroup
import com.borovas.animadroid.core.model.DownloadItem
import com.borovas.animadroid.core.model.DownloadState
import com.borovas.animadroid.core.model.Episode
import kotlinx.serialization.Serializable

@Serializable
data object DownloadsRoute : NavKey

/**
 * [onPlayOffline] receives the anime plus the *playable* downloaded episodes of that anime and the index to
 * start at; the host navigates to the player with the offline flag.
 */
fun EntryProviderScope<NavKey>.downloadsEntry(
    onPlayOffline: (anime: Anime, episodes: List<Episode>, index: Int) -> Unit = { _, _, _ -> },
) {
    entry<DownloadsRoute> {
        val vm: DownloadsViewModel = hiltViewModel()
        val state by vm.state.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
        DownloadsScreen(
            state = state,
            snackbarHost = snackbar,
            onPlay = { group, item ->
                val playable = group.items.filter { it.isPlayable }
                val index = playable.indexOfFirst { it.episodeNumber == item.episodeNumber }
                if (index >= 0) {
                    onPlayOffline(
                        Anime(group.animeId, group.providerId, group.title, coverUrl = group.coverUrl),
                        playable.map { Episode(it.episodeId, it.episodeNumber) },
                        index,
                    )
                }
            },
            onPause = vm::pause,
            onResume = vm::resume,
            onRetry = vm::retry,
            onRemove = vm::remove,
            onRemoveGroup = vm::removeGroup,
        )
    }
}

@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    onPlay: (DownloadGroup, DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onRemove: (DownloadItem) -> Unit,
    onRemoveGroup: (DownloadGroup) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHost: SnackbarHostState = remember { SnackbarHostState() },
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val context = LocalContext.current
    var confirmItem by remember { mutableStateOf<DownloadItem?>(null) }
    var confirmGroup by remember { mutableStateOf<DownloadGroup?>(null) }

    Scaffold(
        modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("Downloads") },
                subtitle = {
                    Text(if (state.groups.isEmpty()) "Nothing saved yet" else "Storage used: " + Formatter.formatShortFileSize(context, state.totalBytes))
                },
                scrollBehavior = scroll,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
    ) { pad ->
        if (state.groups.isEmpty()) {
            EmptyState(
                Icons.Rounded.Download, "No downloads yet",
                "Tap the download button next to an episode to save it for offline viewing.",
                Modifier.padding(top = pad.calculateTopPadding()),
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.waitingForNetwork) item("banner") { WaitingBanner() }
                items(state.groups, key = { it.providerId + "/" + it.animeId }) { group ->
                    GroupCard(
                        group, onPlay = { onPlay(group, it) }, onPause = onPause, onResume = onResume, onRetry = onRetry,
                        onRemove = { item -> if (item.state == DownloadState.COMPLETED) confirmItem = item else onRemove(item) },
                        onRemoveGroup = { confirmGroup = group },
                    )
                }
            }
        }
    }

    confirmItem?.let { item ->
        ConfirmDialog(
            "Delete episode ${item.episodeNumber}?", "The downloaded file of ${item.title} will be removed from this device.",
            onConfirm = { onRemove(item); confirmItem = null }, onDismiss = { confirmItem = null },
        )
    }
    confirmGroup?.let { group ->
        ConfirmDialog(
            "Delete all of ${group.title}?", "All ${group.items.size} downloaded or queued episodes will be removed from this device.",
            onConfirm = { onRemoveGroup(group); confirmGroup = null }, onDismiss = { confirmGroup = null },
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WaitingBanner() {
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Wifi, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(
                "Waiting for Wi-Fi. Downloads continue automatically once connected (change this in Settings).",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

@Composable
private fun GroupCard(
    group: DownloadGroup,
    onPlay: (DownloadItem) -> Unit,
    onPause: (DownloadItem) -> Unit,
    onResume: (DownloadItem) -> Unit,
    onRetry: (DownloadItem) -> Unit,
    onRemove: (DownloadItem) -> Unit,
    onRemoveGroup: () -> Unit,
) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Row(
                Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Cover(group.coverUrl, group.title, Modifier.width(52.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)), ContentScale.Crop)
                Column(Modifier.weight(1f)) {
                    Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${group.items.size} episode${if (group.items.size == 1) "" else "s"} · " + Formatter.formatShortFileSize(context, group.totalBytes),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemoveGroup) { Icon(Icons.Rounded.Delete, "Delete all episodes of ${group.title}") }
            }
            group.items.forEach { item ->
                EpisodeRow(item, onPlay = { onPlay(item) }, onPause = { onPause(item) }, onResume = { onResume(item) }, onRetry = { onRetry(item) }, onRemove = { onRemove(item) })
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    item: DownloadItem,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, item.bytes)
    val pct = (item.percent * 100).toInt()
    val status = when (item.state) {
        DownloadState.QUEUED -> "Queued"
        DownloadState.DOWNLOADING -> "$pct% · $size"
        DownloadState.PAUSED -> item.failureReason ?: "Paused · $pct%"
        DownloadState.COMPLETED -> "Downloaded · $size"
        DownloadState.FAILED -> "Failed: ${item.failureReason ?: "unknown reason"} · tap retry"
    }
    Row(
        Modifier.fillMaxWidth().then(if (item.isPlayable) Modifier.clickable(onClick = onPlay) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Episode ${item.episodeNumber}" + (item.quality?.let { " · ${it}p" } ?: ""),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                status, style = MaterialTheme.typography.bodySmall,
                color = if (item.state == DownloadState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (item.state) {
                DownloadState.DOWNLOADING, DownloadState.PAUSED ->
                    LinearWavyProgressIndicator(
                        progress = { item.percent },
                        modifier = Modifier.fillMaxWidth(),
                        amplitude = { if (item.state == DownloadState.DOWNLOADING) 1f else 0f },
                    )
                DownloadState.QUEUED -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
        }
        when (item.state) {
            DownloadState.COMPLETED -> {
                IconButton(onClick = onPlay) { Icon(Icons.Rounded.PlayArrow, "Play episode ${item.episodeNumber} offline") }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Delete episode ${item.episodeNumber}") }
            }
            DownloadState.PAUSED -> {
                IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Cancel") }
            }
            DownloadState.FAILED -> {
                IconButton(onClick = onRetry) { Icon(Icons.Rounded.Refresh, "Retry") }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove") }
            }
            else -> {
                IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, "Pause") }
                IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Cancel") }
            }
        }
    }
}

private fun sample(ep: String, state: DownloadState, pct: Float, bytes: Long) = DownloadItem(
    "hianime", "frieren", "e$ep", ep, "Frieren: Beyond Journey's End", null, quality = 720, state = state, bytes = bytes, percent = pct, createdAt = ep.toLong(),
)

@Preview(showBackground = true, heightDp = 800)
@Composable
private fun DownloadsPreview() = AnimadroidTheme(dynamicColor = false) {
    val items = listOf(
        sample("1", DownloadState.COMPLETED, 1f, 310_000_000), sample("2", DownloadState.DOWNLOADING, 0.42f, 130_000_000),
        sample("3", DownloadState.PAUSED, 0.2f, 60_000_000), sample("4", DownloadState.QUEUED, 0f, 0),
        sample("5", DownloadState.FAILED, 0f, 0).copy(failureReason = "Server refused access (HTTP 403); the link may have expired"),
    )
    DownloadsScreen(
        DownloadsUiState(
            listOf(DownloadGroup("hianime", "frieren", "Frieren: Beyond Journey's End", null, items)), items.sumOf { it.bytes }, true, false,
        ),
        { _, _ -> }, {}, {}, {}, {}, {},
    )
}

@Preview(showBackground = true)
@Composable
private fun DownloadsEmptyPreview() = AnimadroidTheme(dynamicColor = false) {
    DownloadsScreen(DownloadsUiState(loading = false), { _, _ -> }, {}, {}, {}, {}, {})
}
