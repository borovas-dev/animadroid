@file:OptIn(ExperimentalMaterial3Api::class)

package com.borovas.animadroid.navigation

import com.borovas.animadroid.core.designsystem.R as DR
import com.borovas.animadroid.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.borovas.animadroid.core.network.CloudflareSolver
import com.borovas.animadroid.provider.api.CloudflareSolved

/**
 * Full-screen WebView that lets the user pass a Cloudflare check for [url]. When the `cf_clearance`
 * cookie appears it dismisses itself and signals [CloudflareSolved] so the failed request is retried.
 */
@Composable
fun CloudflareHost(url: String?, onDismiss: () -> Unit) {
    if (url == null) return
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.cloudflare_verify_title)) },
                    actions = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(DR.string.common_close)) } },
                )
                CloudflareSolver(
                    url = url,
                    onSolved = { onDismiss(); CloudflareSolved.notifySolved() },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}
