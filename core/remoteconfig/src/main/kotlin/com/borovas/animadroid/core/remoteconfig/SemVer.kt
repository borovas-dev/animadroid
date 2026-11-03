package com.borovas.animadroid.core.remoteconfig

/** Minimal semantic version: `v1.2.3-beta.1+build` -> numeric core + optional pre-release. Lenient on missing parts. */
data class SemVer(val parts: List<Int>, val preRelease: String? = null) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = (parts.getOrElse(i) { 0 }).compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            preRelease == other.preRelease -> 0
            preRelease == null -> 1 // release > pre-release
            other.preRelease == null -> -1
            else -> comparePre(preRelease, other.preRelease)
        }
    }

    private fun comparePre(a: String, b: String): Int {
        val x = a.split('.'); val y = b.split('.')
        for (i in 0 until minOf(x.size, y.size)) {
            val xi = x[i].toIntOrNull(); val yi = y[i].toIntOrNull()
            val c = when {
                xi != null && yi != null -> xi.compareTo(yi)
                xi != null -> -1
                yi != null -> 1
                else -> x[i].compareTo(y[i])
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    companion object {
        fun parse(raw: String): SemVer? {
            val s = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val core = s.substringBefore('-')
            val pre = if ('-' in s) s.substringAfter('-').ifEmpty { null } else null
            val nums = core.split('.').map { it.toIntOrNull() ?: return null }
            if (nums.isEmpty() || nums.size > 4) return null
            return SemVer(nums, pre)
        }
    }
}

fun isNewerVersion(candidate: String, current: String): Boolean {
    val c = SemVer.parse(candidate) ?: return false
    val cur = SemVer.parse(current) ?: return false
    return c > cur
}
