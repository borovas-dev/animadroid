package com.borovas.animadroid.provider.common

import com.borovas.animadroid.core.model.StreamVariant
import java.net.URI

/** Shared HLS helpers for providers that expose an m3u8 master playlist. */
object M3u8 {
    /** Variants sorted best to worst plus the default external audio rendition (if any); URLs are absolute. */
    class MasterPlaylist(val variants: List<StreamVariant>, val externalAudioUrl: String?)

    /** Parses [text] (a master playlist fetched from [masterUrl]); relative URIs are resolved against [masterUrl]. */
    fun parseMaster(text: String, masterUrl: String): MasterPlaylist {
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<StreamVariant>()
        var audio: String? = null
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val height = Regex("RESOLUTION=\\d+x(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) variants += StreamVariant(height, resolve(masterUrl, lines[j]))
                i = j
            } else if (audio == null && line.startsWith("#EXT-X-MEDIA:TYPE=AUDIO") && line.contains("DEFAULT=YES")) {
                audio = Regex("URI=\"([^\"]*)\"").find(line)?.groupValues?.get(1)?.let { resolve(masterUrl, it) }
            }
            i++
        }
        return MasterPlaylist(variants.sortedByDescending { it.quality ?: -1 }, audio)
    }

    /** Resolves [ref] against [base]; absolute http(s) refs are returned unchanged. */
    fun resolve(base: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URI(base).resolve(ref).toString()
}
