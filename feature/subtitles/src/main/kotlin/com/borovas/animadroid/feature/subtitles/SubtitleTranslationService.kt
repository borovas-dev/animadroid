package com.borovas.animadroid.feature.subtitles

import android.content.Context
import android.net.ConnectivityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Production wiring for [SubtitleTranslator]. Everything heavy (the ML Kit client, disk cache) is created on first
 * use, so nothing runs at application start.
 */
@Singleton
class SubtitleTranslationService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val engine: TranslationEngine by lazy { MlKitTranslationEngine() }
    private val translator by lazy {
        SubtitleTranslator(
            engine = engine,
            fetcher = OkHttpSubtitleFetcher(http),
            cache = LayeredTranslationCache(File(context.cacheDir, "translated-subtitles")),
            network = object : NetworkChecker {
                override fun isMetered(): Boolean =
                    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
            },
        )
    }

    /** Whether [language] (two-letter code) can be translated to/from. Loads ML Kit lazily. */
    fun isSupported(language: String): Boolean = runCatching { engine.isSupported(language) }.getOrDefault(false)

    fun translate(request: TranslationRequest, positionMs: () -> Long): Flow<TranslationProgress> =
        translator.translate(request, positionMs)
}

internal class OkHttpSubtitleFetcher(private val http: OkHttpClient) : SubtitleFetcher {
    override suspend fun fetch(url: String, headers: Map<String, String>): String = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw TranslationFailedException("Subtitle download failed (HTTP ${resp.code})")
                resp.body.string()
            }
        } catch (e: TranslationFailedException) {
            throw e
        } catch (e: java.io.IOException) {
            throw TranslationFailedException("Subtitle download failed", e)
        } catch (e: IllegalArgumentException) {
            throw TranslationFailedException("Unsupported subtitle URL", e)
        }
    }
}
