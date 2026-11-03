package com.borovas.animadroid.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.borovas.animadroid.feature.aniskip.SkipSegment
import com.borovas.animadroid.feature.player.R as PR
import kotlinx.coroutines.delay

/** Brief "Skipped intro - Undo" prompt shown after an automatic skip. */
@Composable
internal fun AutoSkipUndo(segment: SkipSegment, onUndo: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(segment) { delay(5_000); onDismiss() }
    Row(
        modifier.background(Color.Black.copy(0.75f), RoundedCornerShape(50)).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (segment == SkipSegment.OPENING) PR.string.player_skipped_intro else PR.string.player_skipped_outro),
            color = Color.White, style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onUndo) { Text(stringResource(PR.string.player_undo_skip)) }
    }
}
