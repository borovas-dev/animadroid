package com.borovas.animadroid.feature.settings

import com.borovas.animadroid.core.model.TranslationMode
import com.borovas.animadroid.provider.api.AnimeProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class StepStatus { PENDING, OK, FAILED, SKIPPED }

/** Outcome of one live check step. [millis] is the latency, [message] the error (or a short success note). */
data class StepResult(val status: StepStatus, val millis: Long = 0, val message: String? = null)

/** Live check of one provider: search -> episodes -> streams, each step reported separately. */
data class ProviderHealth(
    val id: String,
    val name: String,
    /** Disabled by the user (still checked, shown as such). */
    val disabled: Boolean = false,
    /** Part of the effective chain for the current content language. */
    val inChain: Boolean = true,
    val search: StepResult = StepResult(StepStatus.PENDING),
    val episodes: StepResult = StepResult(StepStatus.PENDING),
    val streams: StepResult = StepResult(StepStatus.PENDING),
) {
    val steps get() = listOf(search, episodes, streams)
    val finished get() = steps.none { it.status == StepStatus.PENDING }
    val allOk get() = steps.all { it.status == StepStatus.OK }
}

/** Pure runner (no Android types) so it can be unit tested with fake providers. Each step has its own timeout. */
class HealthRunner(
    private val timeoutMs: Long = 10_000,
    private val query: String = "frieren",
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Runs the three steps for [provider]; [onUpdate] receives every intermediate state (starting with [initial]). */
    suspend fun check(provider: AnimeProvider, initial: ProviderHealth, onUpdate: (ProviderHealth) -> Unit = {}): ProviderHealth {
        var h = initial
        onUpdate(h)
        val mode = TranslationMode.SUB

        val found = step { provider.search(query, mode).also { if (it.isEmpty()) error("no results for \"$query\"") } }
        h = h.copy(search = found.result("${found.value?.size ?: 0}")); onUpdate(h)
        val anime = found.value?.first()
        if (anime == null) return skipRest(h, skipEpisodes = true, onUpdate)

        val eps = step { provider.episodes(anime, mode).also { if (it.isEmpty()) error("no episodes") } }
        h = h.copy(episodes = eps.result("${eps.value?.size ?: 0}")); onUpdate(h)
        val first = eps.value?.first()
        if (first == null) return skipRest(h, skipEpisodes = false, onUpdate)

        val st = step {
            provider.streams(anime, first, mode).also { if (it.variants.isEmpty() && it.masterUrl == null) error("no playable streams") }
        }
        h = h.copy(streams = st.result(st.value?.let { "${it.variants.size}" })); onUpdate(h)
        return h
    }

    private fun skipRest(h: ProviderHealth, skipEpisodes: Boolean, onUpdate: (ProviderHealth) -> Unit): ProviderHealth {
        val skipped = StepResult(StepStatus.SKIPPED)
        val r = if (skipEpisodes) h.copy(episodes = skipped, streams = skipped) else h.copy(streams = skipped)
        onUpdate(r)
        return r
    }

    private class Timed<T>(val value: T?, val millis: Long, val error: String?) {
        fun result(note: String?) =
            if (error == null) StepResult(StepStatus.OK, millis, note) else StepResult(StepStatus.FAILED, millis, error)
    }

    private suspend fun <T> step(block: suspend () -> T): Timed<T> {
        val t0 = now()
        return try {
            Timed(withTimeout(timeoutMs) { block() }, now() - t0, null)
        } catch (e: TimeoutCancellationException) {
            Timed(null, now() - t0, "timed out after ${timeoutMs / 1000}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timed(null, now() - t0, e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName)
        }
    }
}
