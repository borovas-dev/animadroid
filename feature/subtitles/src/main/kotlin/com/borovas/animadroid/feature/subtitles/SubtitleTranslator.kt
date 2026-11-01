package com.borovas.animadroid.feature.subtitles

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class TranslationRequest(
    /** Stable id of the episode (provider + anime + episode), used for caching. */
    val episodeKey: String,
    val trackUrl: String,
    val headers: Map<String, String>,
    val sourceLang: String,
    val targetLang: String,
    /** The user agreed to download language packs even on a metered connection. */
    val allowMetered: Boolean = false,
)

sealed interface TranslationProgress {
    data object Preparing : TranslationProgress
    data object DownloadingModel : TranslationProgress
    /** [cues] holds every cue translated so far, sorted by start. */
    data class Translating(val done: Int, val total: Int, val cues: List<SubtitleCue>) : TranslationProgress
    data class Done(val cues: List<SubtitleCue>) : TranslationProgress
}

/**
 * Fetches, parses and translates one subtitle track in batches ahead of playback. Cue timing is copied as-is; only
 * text changes. Collect the returned flow in a coroutine scope; cancelling stops the work.
 */
class SubtitleTranslator(
    private val engine: TranslationEngine,
    private val fetcher: SubtitleFetcher,
    private val cache: TranslationCache,
    private val network: NetworkChecker,
    private val batchSize: Int = 20,
) {
    fun translate(request: TranslationRequest, positionMs: () -> Long): Flow<TranslationProgress> = flow {
        emit(TranslationProgress.Preparing)
        val (source, target) = request.sourceLang to request.targetLang
        for (lang in listOf(source, target)) if (!engine.isSupported(lang)) throw UnsupportedLanguageException(lang)
        val key = "${request.episodeKey}|${request.trackUrl}|$source|$target"
        cache.get(key)?.let { emit(TranslationProgress.Done(it)); return@flow }

        val cues = SubtitleParser.parse(fetcher.fetch(request.trackUrl, request.headers))
        if (cues.isEmpty()) throw TranslationFailedException("Subtitle track is empty or unreadable")

        val missing = engine.missingModels(source, target)
        if (missing.isNotEmpty()) {
            if (network.isMetered() && !request.allowMetered) throw MeteredDownloadRequired(missing)
            emit(TranslationProgress.DownloadingModel)
        }
        engine.prepare(source, target)

        val plans = cues.map { CuePlan.of(it.text) }
        val translated = arrayOfNulls<SubtitleCue>(cues.size)
        val chunks = CueBatcher.chunks(cues.size, batchSize)
        val doneChunks = HashSet<Int>()
        // Identical lines recur a lot (interjections, names); translate each distinct body once.
        val memo = HashMap<String, String>()
        emit(TranslationProgress.Translating(0, cues.size, emptyList()))
        while (true) {
            currentCoroutineContext().ensureActive()
            val next = CueBatcher.nextChunk(cues, chunks, doneChunks, positionMs()) ?: break
            val range = chunks[next]
            val bodies = range.flatMap { i -> plans[i].units.map { it.body } }
                .filter { needsTranslation(it) && it !in memo }.distinct()
            if (bodies.isNotEmpty()) {
                val out = engine.translate(source, target, bodies)
                if (out.size != bodies.size) throw TranslationFailedException("Translator returned ${out.size} results for ${bodies.size} lines")
                bodies.forEachIndexed { k, b -> memo[b] = out[k] }
            }
            for (i in range) {
                val plan = plans[i]
                val text = plan.render(plan.units.map { u -> if (needsTranslation(u.body)) memo.getValue(u.body) else u.body })
                translated[i] = cues[i].copy(text = text)
            }
            doneChunks += next
            val soFar = translated.filterNotNull().sortedBy { it.startMs }
            emit(TranslationProgress.Translating(soFar.size, cues.size, soFar))
        }
        val result = translated.filterNotNull().sortedBy { it.startMs }
        cache.put(key, result)
        emit(TranslationProgress.Done(result))
    }
}
