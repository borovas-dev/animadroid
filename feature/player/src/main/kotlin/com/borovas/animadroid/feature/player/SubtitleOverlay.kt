package com.borovas.animadroid.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.media3.common.text.Cue
import com.borovas.animadroid.feature.subtitles.StyledText
import com.borovas.animadroid.feature.subtitles.SubtitleCue
import com.borovas.animadroid.feature.subtitles.activeAt
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView

/**
 * Draws the player's current subtitle cues. media3-ui-compose renders only the video surface, so without
 * this overlay selected text tracks (sidecar or embedded) are decoded but never shown.
 */
@OptIn(UnstableApi::class)
@Composable
fun SubtitleOverlay(
    player: Player,
    modifier: Modifier = Modifier,
    bottomPadding: Dp = 0.dp,
    /** Machine-translated cues (sorted by start); drawn instead of the player's own cues while [translatedActive]. */
    translatedCues: List<SubtitleCue> = emptyList(),
    translatedActive: Boolean = false,
) {
    val viewHolder = remember { arrayOfNulls<SubtitleView>(1) }
    val active by rememberUpdatedState(translatedActive)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                if (!active) viewHolder[0]?.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        viewHolder[0]?.setCues(player.currentCues.cues)
        onDispose { player.removeListener(listener) }
    }
    // Translated cues are looked up by position, never translated on the render path: a cheap binary search every
    // 100 ms, and the view is only touched when the active cue changes.
    LaunchedEffect(translatedActive, translatedCues, player) {
        if (!translatedActive) {
            viewHolder[0]?.setCues(player.currentCues.cues)
            return@LaunchedEffect
        }
        var shown: SubtitleCue? = null
        var first = true
        while (true) {
            val cue = translatedCues.activeAt(player.currentPosition)
            if (first || cue != shown) {
                first = false
                shown = cue
                viewHolder[0]?.setCues(cue?.let { listOf(it.toCue()) } ?: emptyList())
            }
            delay(100)
        }
    }
    Box(modifier.fillMaxSize().padding(bottom = bottomPadding)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                SubtitleView(ctx).apply {
                    setUserDefaultStyle()
                    setUserDefaultTextSize()
                    setApplyEmbeddedStyles(true)
                    setCues(player.currentCues.cues)
                    viewHolder[0] = this
                }
            },
            onRelease = { viewHolder[0] = null },
        )
    }
}

@OptIn(UnstableApi::class)
private fun SubtitleCue.toCue(): Cue {
    val styled = StyledText.parse(text)
    val spannable = android.text.SpannableString(styled.text)
    styled.spans.forEach { sp ->
        val what: Any = when (sp.kind) {
            StyledText.Kind.ITALIC -> android.text.style.StyleSpan(android.graphics.Typeface.ITALIC)
            StyledText.Kind.BOLD -> android.text.style.StyleSpan(android.graphics.Typeface.BOLD)
            StyledText.Kind.UNDERLINE -> android.text.style.UnderlineSpan()
        }
        spannable.setSpan(what, sp.start, sp.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return Cue.Builder().setText(spannable).build()
}
