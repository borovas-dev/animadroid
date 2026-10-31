@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.borovas.animadroid.feature.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButtonColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import com.borovas.animadroid.core.model.SkipTimes
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}

@Composable
internal fun PlayerControls(
    state: PlayerUiState,
    positionFlow: StateFlow<PlaybackPosition>,
    locked: Boolean,
    onBack: () -> Unit,
    onLockToggle: () -> Unit,
    onSettings: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    scrubPreview: ScrubPreviewSource? = null,
) {
    val iconColors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
    Box(
        modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color.Black.copy(0.6f), Color.Transparent, Color.Transparent, Color.Black.copy(0.7f))),
        ).safeDrawingPadding(),
    ) {
        // Top bar
        Row(
            Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!locked) {
                IconButton(onBack, colors = iconColors) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text(
                    state.title, color = Color.White, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
            IconButton(onLockToggle, colors = iconColors) {
                Icon(if (locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen, if (locked) "Unlock" else "Lock")
            }
            if (!locked) {
                IconButton(onSettings, colors = iconColors) { Icon(Icons.Rounded.Tune, "Quality, subtitles and speed") }
            }
        }

        if (!locked) {
            // Center transport
            Row(
                Modifier.align(Alignment.Center),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                IconButton(onPrevious, enabled = state.hasPrevious, colors = iconColors, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous episode", Modifier.size(36.dp))
                }
                FilledIconToggleButton(
                    checked = state.isPlaying,
                    onCheckedChange = { onPlayPause() },
                    shapes = IconButtonDefaults.toggleableShapes(),
                    modifier = Modifier.size(84.dp),
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Pause" else "Play",
                        Modifier.size(44.dp),
                    )
                }
                IconButton(onNext, enabled = state.hasNext, colors = iconColors, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next episode", Modifier.size(36.dp))
                }
            }

            // Bottom seek bar
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp)) {
                SeekBar(positionFlow, state.skipTimes, onSeek, onInteraction, scrubPreview)
            }
        }
    }
}

@Composable
private fun SeekBar(
    positionFlow: StateFlow<PlaybackPosition>,
    skipTimes: SkipTimes?,
    onSeek: (Long) -> Unit,
    onInteraction: () -> Unit,
    scrubPreview: ScrubPreviewSource? = null,
) {
    val pos by positionFlow.collectAsState()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = pos.durationMs
    val fraction = dragging ?: if (duration > 0) (pos.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragging != null) (fraction * duration).toLong() else pos.positionMs
    val segmentColor = MaterialTheme.colorScheme.tertiary

    // A held (but still) thumb must not let the controls auto-hide mid-scrub.
    val scrubbing = dragging != null
    if (scrubbing) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) { kotlinx.coroutines.delay(1_000); onInteraction() }
        }
    }

    Column {
        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
            if (dragging != null) {
                ScrubPreviewBubble(scrubPreview, shownMs, fraction, Modifier.align(Alignment.TopStart))
            }
            // OP/ED markers drawn behind the (translucent) track.
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(8.dp)) {
                if (duration <= 0) return@Canvas
                listOfNotNull(skipTimes?.opening, skipTimes?.ending).forEach { seg ->
                    val x0 = (seg.startSec * 1000 / duration).toFloat().coerceIn(0f, 1f) * size.width
                    val x1 = (seg.endSec * 1000 / duration).toFloat().coerceIn(0f, 1f) * size.width
                    drawRoundRect(segmentColor, Offset(x0, 0f), Size((x1 - x0).coerceAtLeast(2f), size.height), CornerRadius(4f, 4f))
                }
            }
            Slider(
                value = fraction,
                onValueChange = { dragging = it; onInteraction() },
                onValueChangeFinished = {
                    dragging?.let { onSeek((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.White.copy(alpha = 0.85f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                    thumbColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownMs), color = Color.White, style = MaterialTheme.typography.labelLarge)
            Text(formatTime(duration), color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

@Composable
internal fun PlayerSettingsSheet(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onQuality: (Int?) -> Unit,
    onSubtitle: (TextTrackOption?) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.qualityOptions.size > 1) {
                Text("Quality", style = MaterialTheme.typography.titleMedium)
                ChipRow {
                    state.qualityOptions.forEach { q ->
                        FilterChip(selected = state.selectedHeight == q.height, onClick = { onQuality(q.height) }, label = { Text(q.label) })
                    }
                }
            }
            Text("Subtitles", style = MaterialTheme.typography.titleMedium)
            ChipRow {
                FilterChip(selected = state.textOff, onClick = { onSubtitle(null) }, label = { Text("Off") })
                state.textTracks.forEach { t ->
                    FilterChip(selected = !state.textOff && t.selected, onClick = { onSubtitle(t) }, label = { Text(t.label) })
                }
            }
            Text("Speed", style = MaterialTheme.typography.titleMedium)
            ChipRow {
                speeds.forEach { s ->
                    FilterChip(selected = state.speed == s, onClick = { onSpeed(s) }, label = { Text(if (s == 1f) "Normal" else "${s}x") })
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}
