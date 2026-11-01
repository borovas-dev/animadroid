package com.borovas.animadroid.feature.subtitles

/** Thrown when a language pack must be downloaded but the connection is metered and the user has not agreed. */
class MeteredDownloadRequired(val languages: List<String>) : Exception("Language pack download needs confirmation on mobile data")

class UnsupportedLanguageException(val language: String) : Exception("Translation to or from '$language' is not supported")

class TranslationFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Backend that translates text on-device. [MlKitTranslationEngine] is the production implementation. */
interface TranslationEngine {
    fun isSupported(language: String): Boolean

    /** Languages (of source and target) whose packs are not on the device yet. */
    suspend fun missingModels(source: String, target: String): List<String>

    /** Downloads the packs for [source] and [target] if needed. Throws [TranslationFailedException] on failure. */
    suspend fun prepare(source: String, target: String)

    /** Translates each text; the result has the same size and order. */
    suspend fun translate(source: String, target: String, texts: List<String>): List<String>

    fun close()
}

fun interface NetworkChecker {
    fun isMetered(): Boolean
}

/** Fetches raw subtitle file contents. */
fun interface SubtitleFetcher {
    suspend fun fetch(url: String, headers: Map<String, String>): String
}
