package com.videograb.app.util

/** Pure helpers for comparing web-engine versions like "157.0.8103.30". */
object VersionUtils {

    fun major(version: String?): Int =
        version?.trim()?.substringBefore('.')?.toIntOrNull() ?: 0

    /** Orders "156.0.8078.50" < "157.0.8103.38" part by part. */
    val comparator = Comparator<String> { a, b ->
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return@Comparator c
        }
        0
    }

    /** How many major versions [installed] is behind [latest] (0 when up to date or unknown). */
    fun behind(installed: String?, latest: String?): Int {
        val i = major(installed)
        val l = major(latest)
        return if (i <= 0 || l <= 0) 0 else (l - i).coerceAtLeast(0)
    }

    /**
     * Chrome releases a new major version about every 4 weeks; being 2 or more behind means
     * months of missing security fixes.
     */
    fun isOutdated(installed: String?, latest: String?): Boolean = behind(installed, latest) >= 2
}
