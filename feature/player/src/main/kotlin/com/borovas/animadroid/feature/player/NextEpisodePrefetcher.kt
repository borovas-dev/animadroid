package com.borovas.animadroid.feature.player

/**
 * Pure bookkeeping for next-episode prefetch: when to start, and a tiny in-memory cache whose entries expire
 * after [ttlMs] (provider stream links go stale). Not thread-safe; use from the main thread.
 */
class NextEpisodePrefetcher<T : Any>(
    private val clock: () -> Long,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val triggerFraction: Double = DEFAULT_TRIGGER,
) {
    private class Entry<T>(val value: T, val atMs: Long)

    private val cache = HashMap<String, Entry<T>>()
    private val attempted = HashSet<String>()

    /** True once per [key]: enabled, network allowed, a next episode exists and playback passed the trigger point. */
    fun shouldStart(
        key: String?, enabled: Boolean, metered: Boolean, allowMetered: Boolean, positionMs: Long, durationMs: Long,
    ): Boolean {
        if (!enabled || key == null || durationMs <= 0) return false
        if (metered && !allowMetered) return false
        if (positionMs < durationMs * triggerFraction) return false
        return key !in attempted && peek(key) == null
    }

    /** Marks [key] as tried so a failed resolve is not retried on every tick. */
    fun markAttempted(key: String) { attempted += key }

    fun put(key: String, value: T) { cache[key] = Entry(value, clock()) }

    /** Returns and removes a fresh entry; expired entries are dropped and yield null. */
    fun take(key: String): T? {
        val e = cache.remove(key) ?: return null
        return e.value.takeIf { clock() - e.atMs < ttlMs }
    }

    private fun peek(key: String): T? {
        val e = cache[key] ?: return null
        if (clock() - e.atMs >= ttlMs) { cache.remove(key); return null }
        return e.value
    }

    fun clear() { cache.clear(); attempted.clear() }

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60 * 1000L
        const val DEFAULT_TRIGGER = 0.8
    }
}
