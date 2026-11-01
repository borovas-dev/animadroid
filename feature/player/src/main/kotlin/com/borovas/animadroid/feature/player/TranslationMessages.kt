package com.borovas.animadroid.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.borovas.animadroid.feature.subtitles.R
import kotlinx.coroutines.delay

/** Non-blocking translation progress / failure pills plus the metered-data confirmation for a language pack. */
@Composable
internal fun TranslationMessages(
    state: PlayerUiState,
    onConfirmDownload: () -> Unit,
    onDeclineDownload: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = state.notice ?: state.translationStatus
    if (text != null) {
        Text(
            text, color = Color.White, style = MaterialTheme.typography.labelLarge,
            modifier = modifier.background(Color.Black.copy(0.6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    LaunchedEffect(state.notice) { if (state.notice != null) { delay(6_000); onDismissNotice() } }
    state.confirmPackDownload?.let { name ->
        AlertDialog(
            onDismissRequest = onDeclineDownload,
            title = { Text(stringResource(R.string.subtitles_metered_title)) },
            text = { Text(stringResource(R.string.subtitles_metered_body, name)) },
            confirmButton = { TextButton(onConfirmDownload) { Text(stringResource(R.string.subtitles_download)) } },
            dismissButton = { TextButton(onDeclineDownload) { Text(stringResource(R.string.subtitles_cancel)) } },
        )
    }
}
