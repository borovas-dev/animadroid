package com.borovas.animadroid.feature.player

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Supplies the thumbnail shown above the seek bar while scrubbing. Implementations must be cancellation-friendly:
 * the UI cancels the previous [frameAt] call whenever the thumb moves.
 */
interface ScrubPreviewSource {
    /** Frame near [positionMs], or null when none is available (failure, disabled, not buffered yet...). */
    suspend fun frameAt(positionMs: Long): ImageBitmap?

    /** The user let go of the thumb (or the controls went away). Sources may free expensive resources soon. */
    fun onScrubEnd() {}

    /** Release everything; the source is not used afterwards. */
    fun close()
}

/** Rounds [positionMs] down to a [gridMs] grid; used for cache keys and to cut redundant work. */
internal fun quantize(positionMs: Long, gridMs: Long): Long = (positionMs.coerceAtLeast(0) / gridMs) * gridMs

/**
 * Tiny size-bounded LRU (pure Kotlin so it is unit-testable). Not thread-safe: guard externally.
 * [sizeOf] is the weight of one value; entries are evicted oldest-access-first until the total is <= [maxSize].
 */
internal class LruMap<K, V : Any>(
    private val maxSize: Long,
    private val sizeOf: (V) -> Long = { 1L },
    private val onEvict: (K, V) -> Unit = { _, _ -> },
) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    var size: Long = 0L
        private set
    val count: Int get() = map.size

    operator fun get(key: K): V? = map[key]
    operator fun contains(key: K): Boolean = map.containsKey(key)

    fun put(key: K, value: V) {
        map.put(key, value)?.let { size -= sizeOf(it); onEvict(key, it) }
        size += sizeOf(value)
        val it = map.entries.iterator()
        while (size > maxSize && it.hasNext()) {
            val e = it.next()
            if (e.key == key) continue // never evict what we just inserted
            size -= sizeOf(e.value)
            it.remove()
            onEvict(e.key, e.value)
        }
    }

    fun clear() {
        val copy = map.entries.map { it.key to it.value }
        map.clear(); size = 0
        copy.forEach { (k, v) -> onEvict(k, v) }
    }
}
