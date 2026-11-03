package com.borovas.animadroid.feature.dubbing

/** Decoded 16-bit PCM WAV. [pcm] is little-endian interleaved samples. */
class WavData(val sampleRate: Int, val channelCount: Int, val pcm: ByteArray) {
    val frames: Int get() = pcm.size / (2 * channelCount)
    val durationMs: Long get() = frames * 1000L / sampleRate
}

object WavReader {
    private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
    private fun le32(b: ByteArray, o: Int) = le16(b, o).toLong() or (le16(b, o + 2).toLong() shl 16)
    private fun tag(b: ByteArray, o: Int, s: String) = s.indices.all { b[o + it].toInt() == s[it].code }

    /** Parses a RIFF/WAVE with PCM16 data; null if malformed/unsupported. Tolerates streaming headers with bogus sizes. */
    fun parse(b: ByteArray): WavData? {
        if (b.size < 12 || !tag(b, 0, "RIFF") || !tag(b, 8, "WAVE")) return null
        var pos = 12
        var rate = 0
        var channels = 0
        var ok = false
        while (pos + 8 <= b.size) {
            val size = le32(b, pos + 4)
            val body = pos + 8
            if (tag(b, pos, "fmt ")) {
                if (body + 16 > b.size) return null
                val format = le16(b, body)
                channels = le16(b, body + 2)
                rate = le32(b, body + 4).toInt()
                val bits = le16(b, body + 14)
                ok = (format == 1 || format == 0xFFFE) && bits == 16 && channels in 1..2 && rate > 0
            } else if (tag(b, pos, "data")) {
                if (!ok) return null
                val end = minOf(b.size.toLong(), body + size).toInt()
                val frameBytes = 2 * channels
                val len = (end - body) / frameBytes * frameBytes
                if (len <= 0) return null
                return WavData(rate, channels, b.copyOfRange(body, body + len))
            }
            val next = body + size + (size and 1L)
            if (next > b.size) return null
            pos = next.toInt()
        }
        return null
    }
}
