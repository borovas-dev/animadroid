package com.borovas.animadroid.feature.subtitles

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Language codes ML Kit can translate between (any pair, pivoting through English). */
object MlKitLanguages {
    fun all(): List<String> = TranslateLanguage.getAllLanguages()
}

internal suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** ML Kit on-device translation. Translators are created lazily per language pair and closed with [close]. */
class MlKitTranslationEngine : TranslationEngine {
    private val translators = HashMap<String, Translator>()
    private val modelManager by lazy { RemoteModelManager.getInstance() }

    override fun isSupported(language: String): Boolean = TranslateLanguage.fromLanguageTag(language) != null

    private fun tag(language: String): String =
        TranslateLanguage.fromLanguageTag(language) ?: throw UnsupportedLanguageException(language)

    override suspend fun missingModels(source: String, target: String): List<String> =
        listOf(source, target).distinct().filter { lang ->
            !modelManager.isModelDownloaded(TranslateRemoteModel.Builder(tag(lang)).build()).await()
        }

    override suspend fun prepare(source: String, target: String) {
        try {
            translator(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        } catch (e: UnsupportedLanguageException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationFailedException("Language pack download failed", e)
        }
    }

    override suspend fun translate(source: String, target: String, texts: List<String>): List<String> {
        val t = translator(source, target)
        return try {
            texts.map { t.translate(it).await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationFailedException("Translation failed", e)
        }
    }

    @Synchronized
    private fun translator(source: String, target: String): Translator =
        translators.getOrPut("$source>$target") {
            Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(tag(source)).setTargetLanguage(tag(target)).build(),
            )
        }

    @Synchronized
    override fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
