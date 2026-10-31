package dev.borovas.animadroid.core.network

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

/**
 * Loads [url] in a WebView (same UA as OkHttp) and calls [onSolved] once the `cf_clearance`
 * cookie for the host appears in the shared [CookieManager]. Size it via [modifier]
 * (small to keep it hidden, or full size so the user can tap a captcha).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareSolver(
    url: String,
    onSolved: () -> Unit,
    modifier: Modifier = Modifier,
    pollMillis: Long = 500,
    timeoutMillis: Long = 120_000,
    onTimeout: () -> Unit = {},
) {
    val solved by rememberUpdatedState(onSolved)
    val timedOut by rememberUpdatedState(onTimeout)
    LaunchedEffect(url) {
        var waited = 0L
        while (waited < timeoutMillis) {
            if (hasClearance(url)) {
                solved()
                return@LaunchedEffect
            }
            delay(pollMillis)
            waited += pollMillis
        }
        timedOut()
    }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = ANI_CLI_USER_AGENT
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = WebViewClient()
                    loadUrl(url)
                }
            },
            onRelease = { it.destroy() },
        )
    }
}

fun hasClearance(url: String): Boolean =
    CookieManager.getInstance().getCookie(url)?.split(';')?.any { it.trim().startsWith("cf_clearance=") } == true
