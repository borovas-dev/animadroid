package com.borovas.animadroid.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
fun SubtitleOverlay(player: Player, modifier: Modifier = Modifier, bottomPadding: Dp = 0.dp) {
    val viewHolder = remember { arrayOfNulls<SubtitleView>(1) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onCues(cueGroup: CueGroup) {
                viewHolder[0]?.setCues(cueGroup.cues)
            }
        }
        player.addListener(listener)
        viewHolder[0]?.setCues(player.currentCues.cues)
        onDispose { player.removeListener(listener) }
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
