package com.borovas.animadroid.feature.subtitles

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Finished translations per episode + language. */
interface TranslationCache {
    fun get(key: String): List<SubtitleCue>?
    fun put(key: String, cues: List<SubtitleCue>)
}

/** Small in-memory LRU in front of an optional disk directory (cacheDir, so the OS can reclaim it). */
class LayeredTranslationCache(private val dir: File?, private val maxEntries: Int = 8) : TranslationCache {
    private val memory = object : LinkedHashMap<String, List<SubtitleCue>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SubtitleCue>>?) = size > maxEntries
    }
    private val serializer = ListSerializer(SubtitleCue.serializer())

    @Synchronized
    override fun get(key: String): List<SubtitleCue>? {
        memory[key]?.let { return it }
        val file = fileFor(key)?.takeIf { it.isFile } ?: return null
        val cues = runCatching { Json.decodeFromString(serializer, file.readText()) }.getOrNull()
        if (cues == null) file.delete() else memory[key] = cues
        return cues
    }

    @Synchronized
    override fun put(key: String, cues: List<SubtitleCue>) {
        memory[key] = cues
        val file = fileFor(key) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(Json.encodeToString(serializer, cues))
            // Keep the directory bounded: drop the oldest files beyond 40.
            dir?.listFiles()?.sortedByDescending { it.lastModified() }?.drop(40)?.forEach { it.delete() }
        }
    }

    private fun fileFor(key: String): File? = dir?.let { File(it, hash(key) + ".json") }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
}
