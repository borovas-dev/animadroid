package com.borovas.animadroid.feature.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest

private val FrameWidth = 160.dp
private val FrameHeight = 90.dp

/** Distance the bubble may poke out past the slider box on each side (the seek bar sits in a 16 dp gutter). */
private val EdgeSlack = 12.dp

/** The thumb centre travels inside the track by half the thumb width, matching the OP/ED marker canvas inset. */
private val ThumbInset = 10.dp
private val GapAboveThumb = 14.dp

internal const val SCRUB_BUBBLE_TAG = "scrubPreviewBubble"

/**
 * YouTube-style preview bubble above the seek thumb. Place it as a child of the slider box (full width): it measures
 * itself, positions at the thumb ([fraction] of the track) and clamps to the box edges, drawing above the box.
 * Frames come from [source]; while the next one loads the last frame stays (dimmed), before the first one a shimmer
 * placeholder shows, and when no preview is possible at all (no source, or it keeps returning null) only the
 * timestamp pill remains.
 */
@Composable
internal fun ScrubPreviewBubble(source: ScrubPreviewSource?, targetMs: Long, fraction: Float, modifier: Modifier = Modifier) {
    var frame by remember(source) { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember(source) { mutableStateOf(source != null) }
    var unavailable by remember(source) { mutableStateOf(false) }
    val latestTarget by rememberUpdatedState(targetMs)

    if (source != null) {
        LaunchedEffect(source) {
            snapshotFlow { latestTarget }.collectLatest { ms ->
                loading = true
                val f = source.frameAt(ms)
                loading = false
                if (f != null) { frame = f; unavailable = false } else if (frame == null) unavailable = true
            }
        }
        // Whatever ends the scrub (release, controls hidden, screen left) lets the source free its decoder.
        DisposableEffect(source) { onDispose { source.onScrubEnd() } }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val insetPx = with(density) { ThumbInset.roundToPx() }
    val slackPx = with(density) { EdgeSlack.roundToPx() }
    val gapPx = with(density) { GapAboveThumb.roundToPx() }

    Box(
        modifier.fillMaxWidth().layout { measurable, constraints ->
            val p = measurable.measure(Constraints())
            val trackW = (constraints.maxWidth - 2 * insetPx).coerceAtLeast(1)
            val thumbX = insetPx + fraction.coerceIn(0f, 1f) * trackW
            val x = (thumbX - p.width / 2f).roundToInt().coerceIn(-slackPx, (constraints.maxWidth + slackPx - p.width).coerceAtLeast(-slackPx))
            // Zero-height anchor on the top edge of the slider box; the bubble hangs above it.
            layout(constraints.maxWidth, 0) { p.place(IntOffset(x, -p.height - gapPx)) }
        }.testTag(SCRUB_BUBBLE_TAG),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (source != null && !unavailable) {
                Box(
                    Modifier.size(FrameWidth, FrameHeight)
                        .shadow(8.dp, RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(0.85f))
                        .border(1.5.dp, Color.White.copy(0.55f), RoundedCornerShape(16.dp)),
                ) {
                    val f = frame
                    if (f != null) {
                        Image(f, null, Modifier.size(FrameWidth, FrameHeight).alpha(if (loading) 0.7f else 1f), contentScale = ContentScale.Crop)
                    } else {
                        ShimmerBox(Modifier.size(FrameWidth, FrameHeight))
                    }
                }
            }
            Text(
                formatTime(targetMs),
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 6.dp)
                    .background(Color.Black.copy(0.7f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ShimmerBox(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "scrubShimmer")
    val a by t.animateFloat(0.05f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    Box(modifier.background(Color(0xFF3A3A3A)).background(Color.White.copy(alpha = a)))
}
