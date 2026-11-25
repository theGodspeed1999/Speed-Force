package com.videograb.app.browser

import com.videograb.app.Prefs
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Counts what the shields stopped, for the start page ("trackers & ads blocked, data and time saved"). */
object ShieldStats {
    private val pending = AtomicInteger()
    private val pendingBytes = AtomicLong()

    /** Rough size of what a blocked request would have downloaded. */
    private fun guessBytes(type: Int): Long = when (type) {
        FilterEngine.SCRIPT -> 30_000L
        FilterEngine.IMAGE -> 60_000L
        FilterEngine.FRAME -> 90_000L
        FilterEngine.MEDIA -> 400_000L
        else -> 6_000L
    }

    fun record(type: Int) {
        pendingBytes.addAndGet(guessBytes(type))
        if (pending.incrementAndGet() >= 25) flush()
    }

    @Synchronized
    fun flush() {
        val n = pending.getAndSet(0)
        val b = pendingBytes.getAndSet(0)
        if (n == 0) return
        Prefs.statBlocked += n
        Prefs.statBytes += b
    }

    fun blockedTotal(): Long = Prefs.statBlocked + pending.get()
    fun bytesTotal(): Long = Prefs.statBytes + pendingBytes.get()

    /** Time not spent waiting for it: download time at about 1.5 MB/s plus a little per request. */
    fun secondsSaved(): Long = bytesTotal() * 10 / 15_000_000L + blockedTotal() / 30

    fun bytesText(b: Long): String = when {
        b >= 1_000_000_000L -> "%.1f GB".format(b / 1e9)
        b >= 1_000_000L -> "%.1f MB".format(b / 1e6)
        else -> "${b / 1000} KB"
    }

    fun timeText(s: Long): String = when {
        s >= 3600 -> "%.1f h".format(s / 3600.0)
        s >= 60 -> "${s / 60} min"
        else -> "$s s"
    }
}
