package com.borovas.animadroid.feature.dubbing

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * System TextToSpeech backed synthesizer. The TextToSpeech instance is created lazily on first use and intentionally never shut down
 * (it lives for the app process; re-creating it is slow and shutting it down mid-synthesis is racy).
 */
class AndroidTtsSynthesizer(private val context: Context, private val timeoutMs: Long = 10_000) : TtsSynthesizer {
    private val initMutex = Mutex()
    private val synthMutex = Mutex()
    @Volatile private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val ids = AtomicLong()
    private var preparedTag: String? = null
    private var preparedOk = false

    fun hasEngine(): Boolean =
        context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()

    fun installIntent(): Intent? =
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).takeIf { it.resolveActivity(context.packageManager) != null }

    private suspend fun engine(): TextToSpeech? = initMutex.withLock {
        tts?.let { return it }
        if (!hasEngine()) return null
        val ready = CompletableDeferred<Boolean>()
        var instance: TextToSpeech? = null
        instance = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }
        val ok = withTimeoutOrNull(timeoutMs) { ready.await() } == true
        if (!ok) { runCatching { instance.shutdown() }; return null }
        instance.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(true) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(false) } }
            override fun onError(utteranceId: String?, errorCode: Int) { onError(utteranceId) }
        })
        tts = instance
        instance
    }

    private fun voicesFor(t: TextToSpeech, locale: Locale): List<Voice> =
        runCatching { t.voices.orEmpty().filter { it.locale.language == locale.language } }.getOrDefault(emptyList())

    private fun Voice.state() = VoiceState(installed = "notInstalled" !in features, offline = !isNetworkConnectionRequired)

    override suspend fun availability(languageTag: String): DubAvailability {
        val t = engine() ?: return if (hasEngine()) DubAvailability.Unsupported else DubAvailability.NoEngine
        val locale = Locale.forLanguageTag(languageTag)
        val code = runCatching { t.isLanguageAvailable(locale) }.getOrDefault(TextToSpeech.ERROR)
        return availabilityFromCode(code, voicesFor(t, locale).map { it.state() })
    }

    /** Sets language and prefers an installed offline voice. Returns false when the language can't be spoken. */
    private fun prepare(t: TextToSpeech, tag: String): Boolean {
        if (preparedTag == tag) return preparedOk
        val locale = Locale.forLanguageTag(tag)
        val code = runCatching { t.setLanguage(locale) }.getOrDefault(TextToSpeech.ERROR)
        var ok = availabilityFromCode(code) == DubAvailability.Ready
        if (ok) {
            val best = voicesFor(t, locale)
                .filter { it.state().installed && it.state().offline }
                .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenBy { it.quality.let { q -> -q } })
                .firstOrNull()
            if (best != null) runCatching { t.setVoice(best) }
        }
        preparedTag = tag
        preparedOk = ok
        return ok
    }

    override suspend fun synthesize(languageTag: String, text: String, out: File): Boolean {
        val t = engine() ?: return false
        return synthMutex.withLock {
            if (!prepare(t, languageTag)) return@withLock false
            out.parentFile?.mkdirs()
            val tmp = File(out.parentFile, out.name + ".tmp")
            val id = "dub-" + ids.incrementAndGet()
            val done = CompletableDeferred<Boolean>()
            pending[id] = done
            val queued = t.synthesizeToFile(text, Bundle(), tmp, id)
            if (queued != TextToSpeech.SUCCESS) { pending.remove(id); tmp.delete(); return@withLock false }
            val ok = withTimeoutOrNull(timeoutMs) { done.await() } == true
            pending.remove(id)
            if (!ok) { runCatching { t.stop() }; tmp.delete(); return@withLock false }
            val valid = tmp.exists() && runCatching { WavReader.parse(tmp.readBytes()) }.getOrNull() != null
            if (valid && tmp.renameTo(out)) true else { tmp.delete(); false }
        }
    }
}
