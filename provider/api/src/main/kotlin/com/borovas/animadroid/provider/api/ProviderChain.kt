package com.borovas.animadroid.provider.api

import com.borovas.animadroid.core.model.Anime
import com.borovas.animadroid.core.model.AppSettings
import com.borovas.animadroid.core.model.Episode
import com.borovas.animadroid.core.model.ProviderRegistry
import com.borovas.animadroid.core.model.SettingsRepository
import com.borovas.animadroid.core.model.SourceStrategy
import com.borovas.animadroid.core.model.StreamSet
import com.borovas.animadroid.core.model.TranslationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Tells [ProviderChain] whether a provider is currently allowed (implemented in `:app` from remote config). */
fun interface ProviderAvailability {
    fun isEnabled(id: String): Boolean

    companion object { val ALL = ProviderAvailability { true } }
}

/**
 * Streams resolved by [ProviderChain.streams].
 *
 * @property set what the player needs.
 * @property providerId provider that actually served [set].
 * @property isFallback true when [providerId] is not the provider of the requested anime.
 * @property anime the anime as known to [providerId] (differs from the requested one on fallback).
 * @property episode the episode as known to [providerId] (its `id` is provider-specific; use it for downloads).
 */
data class ResolvedStreams(
    val set: StreamSet,
    val providerId: String,
    val isFallback: Boolean,
    val anime: Anime,
    val episode: Episode,
)

/**
 * Language-aware, failover-capable facade over all [AnimeProvider]s. Provided as a singleton by `:app`
 * (`ProviderModule`); inject it instead of `Map<String, AnimeProvider>`.
 *
 * Chain rules ([chainFor]): the content language is `AppSettings.contentLanguage` ("auto" = subtitle language, else "en");
 * providers serving it come first (ordered by `providerOrder[lang]`, then [AnimeProvider.priority], then id), followed by
 * English providers as fallback for non-English languages (their subtitles can be machine-translated). For "zh",
 * [AnimeProvider.gfwBlocked] providers are always excluded, even when listed in a manual order. `disabledProviders` and providers disabled by remote config ([ProviderAvailability]) are excluded.
 */
class ProviderChain(
    private val providers: Map<String, AnimeProvider>,
    private val settings: SettingsRepository,
    /** Per-provider limit for resolving streams / episode lists / search. */
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    /** Remote kill switch; providers it reports as disabled are skipped by [chainFor]. */
    private val availability: ProviderAvailability = ProviderAvailability.ALL,
    /** Device UI language code; injectable for tests. */
    private val deviceLanguage: () -> String = { java.util.Locale.getDefault().language },
) : ProviderRegistry {
    override val ids: List<String> get() = providers.keys.toList()

    /** Any registered provider by id (ignores disabled/language filters), e.g. to open a library entry. */
    fun provider(id: String): AnimeProvider? = providers[id]

    /**
     * Concrete language for [s]: explicit `contentLanguage`, else `subtitleLanguage`, else the device language when some
     * provider serves it, else "en".
     */
    fun effectiveLanguage(s: AppSettings): String {
        val explicit = s.contentLanguage.takeUnless { it.isBlank() || it == "auto" }
        return normalizeLang(explicit ?: s.subtitleLanguage.takeIf { it.isNotBlank() } ?: deviceContentLanguage())
    }

    private fun deviceContentLanguage(): String {
        val dev = normalizeLang(deviceLanguage())
        return if (providers.values.any { dev in it.contentLanguages }) dev else "en"
    }

    /** Ordered providers to use for [s]; see the class doc for the rules. */
    fun chainFor(s: AppSettings): List<AnimeProvider> {
        val lang = effectiveLanguage(s)
        val eligible = providers.values.filter { it.id !in s.disabledProviders && availability.isEnabled(it.id) && !(lang == "zh" && it.gfwBlocked) }
        val manual = s.providerOrder[lang].orEmpty()
        val byOrder = compareBy<AnimeProvider>({ manual.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.priority }, { it.id })
        val native = eligible.filter { lang in it.contentLanguages }.sortedWith(byOrder)
        val english = if (lang == "en") emptyList()
        else eligible.filter { "en" in it.contentLanguages && it !in native }.sortedWith(byOrder)
        return native + english
    }

    suspend fun chain(): List<AnimeProvider> = chainFor(settings.settings.first())

    /**
     * Searches every provider in the chain in parallel and merges the results (by MAL id, else normalised title; at most
     * one entry per provider per merged show). Results of earlier chain providers come first; each merged [Anime] keeps the
     * first provider's ids and lists the others in [Anime.alternates]. Individual failures are tolerated; if every
     * provider fails the first failure (a [CloudflareChallengeException] if any) is rethrown.
     */
    suspend fun search(query: String, mode: TranslationMode): List<Anime> {
        val chain = chain()
        if (chain.isEmpty()) return emptyList()
        val outcomes = coroutineScope {
            chain.map { p -> async { attempt { p.search(query, mode) } } }.map { it.await() }
        }
        if (outcomes.all { it.isFailure }) throw pickFailure(outcomes.map { it.exceptionOrNull()!! })
        return merge(chain.map { it.id }.zip(outcomes.map { it.getOrNull().orEmpty() }))
    }

    /**
     * Resolves streams for [episode] of [anime]. Candidates are the anime's own provider, then providers in
     * [Anime.alternates] (in chain order); alternates are matched by episode number via `provider.episodes`.
     * A candidate fails on exception, timeout, or no playable variant/master. With [SourceStrategy.BEST_QUALITY] the first
     * two candidates are resolved concurrently and the better one (subtitles if wanted, then max height) wins; ties favour
     * the earlier one. Throws if all candidates fail (a [CloudflareChallengeException] if any raised one, so UI can solve it).
     */
    suspend fun streams(anime: Anime, episode: Episode, mode: TranslationMode): ResolvedStreams {
        val s = settings.settings.first()
        val candidates = candidates(anime, s)
        if (candidates.isEmpty()) throw ProviderException("No provider available for ${anime.providerId}")
        val failures = ArrayList<Throwable>()
        var next = 0
        if (s.sourceStrategy == SourceStrategy.BEST_QUALITY && candidates.size >= 2) {
            val pair = candidates.take(2)
            next = 2
            val results = coroutineScope { pair.map { c -> async { attempt { resolve(c, anime, episode, mode) } } }.map { it.await() } }
            results.forEach { r -> r.exceptionOrNull()?.let(failures::add) }
            val ok = results.mapNotNull { it.getOrNull() }
            if (ok.isNotEmpty()) return ok.reduce { best, r -> if (score(r, s, mode) > score(best, s, mode)) r else best }
        }
        while (next < candidates.size) {
            val r = attempt { resolve(candidates[next], anime, episode, mode) }
            next++
            r.getOrNull()?.let { return it }
            failures += r.exceptionOrNull()!!
        }
        throw pickFailure(failures)
    }

    private class Candidate(val provider: AnimeProvider, val anime: Anime, val isFallback: Boolean)

    private fun candidates(anime: Anime, s: AppSettings): List<Candidate> {
        val out = ArrayList<Candidate>()
        providers[anime.providerId]?.let { out += Candidate(it, anime, false) }
        for (p in chainFor(s)) {
            if (p.id == anime.providerId) continue
            val altId = anime.alternates[p.id] ?: continue
            out += Candidate(p, anime.copy(id = altId, providerId = p.id, alternates = emptyMap()), true)
        }
        return out
    }

    private suspend fun resolve(c: Candidate, original: Anime, episode: Episode, mode: TranslationMode): ResolvedStreams {
        val ep = if (!c.isFallback) episode else {
            c.provider.episodes(c.anime, mode).firstOrNull { sameEpisode(it.number, episode.number) }
                ?: throw ProviderException("${c.provider.displayName}: episode ${episode.number} not found")
        }
        val set = withTimeoutOrNull(timeoutMs) { c.provider.streams(c.anime, ep, mode) }
            ?: throw ProviderException("${c.provider.displayName}: timed out after ${timeoutMs / 1000}s")
        if (set.variants.isEmpty() && set.masterUrl == null) throw ProviderException("${c.provider.displayName}: no playable streams")
        return ResolvedStreams(set, c.provider.id, c.isFallback, c.anime, ep)
    }

    /** Higher is better: subtitles satisfied (when wanted) dominate, then max height. */
    private fun score(r: ResolvedStreams, s: AppSettings, mode: TranslationMode): Long {
        val lang = normalizeLang(s.subtitleLanguage)
        val wantsSubs = mode == TranslationMode.SUB && s.subtitleLanguage.isNotBlank()
        val subsOk = if (!wantsSubs) 0 else if (r.set.hardsubLanguage?.let(::normalizeLang) == lang || r.set.subtitles.isNotEmpty()) 1 else 0
        return subsOk * 100_000L + (r.set.variants.maxOfOrNull { it.quality ?: 0 } ?: 0)
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(
            withTimeoutOrNull(timeoutMs + 5_000) { block() } ?: throw ProviderException("Timed out"),
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun pickFailure(all: List<Throwable>): Throwable =
        all.firstOrNull { it is CloudflareChallengeException } ?: all.firstOrNull() ?: ProviderException("No provider available")

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L

        private fun normalizeLang(l: String) = l.trim().lowercase().substringBefore('-').substringBefore('_')

        private fun sameEpisode(a: String, b: String): Boolean {
            if (a == b) return true
            val x = a.toDoubleOrNull() ?: return false
            return x == b.toDoubleOrNull()
        }

        /** Lowercase letters/digits only, so "Re:Zero  - Season 2" and "re zero season 2" match. */
        internal fun normalizeTitle(t: String): String = t.lowercase().filter { it.isLetterOrDigit() }

        /** Merge step of [search]; [perProvider] is in chain order. */
        internal fun merge(perProvider: List<Pair<String, List<Anime>>>): List<Anime> {
            class Group(var head: Anime, val alts: LinkedHashMap<String, String> = LinkedHashMap())
            val groups = ArrayList<Group>()
            val byMal = HashMap<Int, Group>()
            val byTitle = HashMap<String, Group>()
            for ((providerId, results) in perProvider) {
                val joined = HashSet<Group>()
                for (a in results) {
                    val key = normalizeTitle(a.title)
                    val g = a.malId?.let(byMal::get)?.takeIf { it !in joined && it.head.providerId != providerId && providerId !in it.alts }
                        ?: key.takeIf { it.isNotEmpty() }?.let(byTitle::get)
                            ?.takeIf { it !in joined && it.head.providerId != providerId && providerId !in it.alts }
                    if (g != null) {
                        g.alts[providerId] = a.id
                        joined += g
                        val newMal = a.malId
                        if (g.head.malId == null && newMal != null) { g.head = g.head.copy(malId = newMal); byMal[newMal] = g }
                        if (g.head.coverUrl == null) g.head = g.head.copy(coverUrl = a.coverUrl)
                    } else {
                        val ng = Group(a)
                        groups += ng
                        joined += ng
                        a.malId?.let { byMal.putIfAbsent(it, ng) }
                        if (key.isNotEmpty()) byTitle.putIfAbsent(key, ng)
                    }
                }
            }
            return groups.map { it.head.copy(alternates = it.head.alternates + it.alts) }
        }
    }
}
