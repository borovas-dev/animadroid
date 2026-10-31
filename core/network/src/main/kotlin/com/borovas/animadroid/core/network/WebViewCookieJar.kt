package dev.borovas.animadroid.core.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** CookieJar backed by the WebView CookieManager so cookies from [CloudflareSolver] are reused. */
class WebViewCookieJar(private val manager: () -> CookieManager = { CookieManager.getInstance() }) : CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = manager()
        val u = url.toString()
        cookies.forEach { m.setCookie(u, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager().getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }
}
