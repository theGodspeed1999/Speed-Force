package com.videograb.app.engine

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Reads a video address directly (a second at most) instead of starting yt-dlp (many seconds on a phone):
 * the HLS playlist lists its qualities, and a plain file says how big it is.
 */
object StreamProbe {
    class Quality(val height: Int, val bandwidth: Long)
    class Result(val qualities: List<Quality>, val durationSec: Int, val sizeBytes: Long)

    /** Null when the address doesn't answer like a video. */
    fun probe(url: String, ctx: RequestContext, durationHint: Int): Result? = try {
        val path = url.substringBefore('#').substringBefore('?').trimEnd('/').lowercase()
        if (path.endsWith(".m3u8")) probeHls(url, ctx) else if (path.endsWith(".mpd")) null else probeFile(url, ctx, durationHint)
    } catch (e: Exception) {
        null
    }

    private fun open(url: String, ctx: RequestContext, method: String = "GET"): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 4000
        c.readTimeout = 4000
        ctx.userAgent?.let { c.setRequestProperty("User-Agent", it) }
        ctx.referer?.let { c.setRequestProperty("Referer", it) }
        ctx.origin?.let { c.setRequestProperty("Origin", it) }
        ctx.acceptLanguage?.let { c.setRequestProperty("Accept-Language", it) }
        val host = URI(url).host?.lowercase()?.removePrefix("www.")
        ctx.cookies[host]?.let { c.setRequestProperty("Cookie", it) }
        return c
    }

    private fun text(url: String, ctx: RequestContext): String? {
        val c = open(url, ctx)
        try {
            if (c.responseCode !in 200..299) return null
            val bytes = c.inputStream.use { it.readNBytes(2_000_000) }
            return String(bytes)
        } finally {
            c.disconnect()
        }
    }

    private fun probeHls(url: String, ctx: RequestContext): Result? {
        val body = text(url, ctx) ?: return null
        val playlist = parsePlaylist(body) ?: return null
        if (playlist.variants.isEmpty()) {
            // Only one quality: its size is about (first piece) x (number of pieces)
            val first = playlist.firstSegment?.let { resolve(url, it) }
            val piece = first?.let { contentLength(it, ctx) } ?: 0L
            return Result(emptyList(), playlist.durationSec, piece * playlist.segments)
        }
        val lowest = playlist.variants.minByOrNull { it.bandwidth }!!
        val duration = text(resolve(url, lowest.uri), ctx)?.let { parsePlaylist(it)?.durationSec } ?: 0
        return Result(playlist.variants.map { Quality(it.height, it.bandwidth) }, duration, 0)
    }

    private fun probeFile(url: String, ctx: RequestContext, durationHint: Int): Result? {
        val c = open(url, ctx)
        c.setRequestProperty("Range", "bytes=0-0")
        try {
            if (c.responseCode !in 200..299) return null
            val type = c.contentType?.lowercase() ?: ""
            if (type.startsWith("text/") || type.contains("json") || type.contains("xml")) return null
            val range = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            val size = range ?: c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            return Result(emptyList(), durationHint, size)
        } finally {
            c.disconnect()
        }
    }

    private fun contentLength(url: String, ctx: RequestContext): Long {
        val c = open(url, ctx, "HEAD")
        try {
            return if (c.responseCode in 200..299) c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L else 0L
        } finally {
            c.disconnect()
        }
    }

    private fun resolve(base: String, relative: String): String = URL(URL(base), relative).toString()

    class Variant(val uri: String, val height: Int, val bandwidth: Long)
    class Playlist(val variants: List<Variant>, val durationSec: Int, val segments: Int, val firstSegment: String?)

    private val bandwidth = Regex("""(?:^|[:,])BANDWIDTH=(\d+)""")
    private val resolution = Regex("""RESOLUTION=(\d+)x(\d+)""")

    /** Reads an HLS playlist. Null when the text isn't one. */
    fun parsePlaylist(text: String): Playlist? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        val lines = text.lines().map { it.trim() }
        val variants = ArrayList<Variant>()
        var seconds = 0.0
        var segments = 0
        var first: String? = null
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                val r = resolution.find(l)
                // The "p" number people know: the shorter side (portrait videos too)
                val h = if (r != null) minOf(r.groupValues[1].toInt(), r.groupValues[2].toInt()) else 0
                variants.add(Variant(uri, h, bandwidth.find(l)?.groupValues?.get(1)?.toLong() ?: 0L))
            } else if (l.startsWith("#EXTINF:")) {
                seconds += l.substring(8).substringBefore(',').toDoubleOrNull() ?: 0.0
                segments++
                if (first == null) first = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            }
        }
        return Playlist(variants, seconds.toInt(), segments, first)
    }

    /** Picker rows for what [probe] found. [heightHint]: picture size when the stream itself doesn't list any. */
    fun options(r: Result, maxRes: Int, isHls: Boolean, heightHint: Int = 0): List<DownloadOption> {
        val source = if (isHls) "MP4 (from HLS)" else "MP4"
        val rows = ArrayList<DownloadOption>()
        fun size(bytes: Long) = if (bytes > 0) "~" + ProgressParser.humanSize(bytes) else "size unknown"
        val byHeight = r.qualities.filter { it.height > 0 }.groupBy { it.height }.map { (h, list) -> h to list.maxOf { it.bandwidth } }.sortedByDescending { it.first }
        for ((h, band) in byHeight) {
            val bytes = if (r.durationSec > 0) band / 8 * r.durationSec else 0L
            rows.add(DownloadOption(FormatOptionsBuilder.qualityName(h), "$source · ${size(bytes)}",
                "b[height=$h]/bv*[height=$h]+ba/bv*[height<=$h]+ba/b", height = h))
        }
        val within = rows.firstOrNull { maxRes <= 0 || it.height in 1..maxRes } ?: rows.firstOrNull()
        val bestFormat = if (maxRes > 0 && rows.isNotEmpty()) "bv*[height<=$maxRes]+ba/b[height<=$maxRes]/bv*+ba/b" else "bv*+ba/b"
        val bestDetail = if (within != null) within.label + " · " + within.detail else "$source · ${size(r.sizeBytes)}"
        val single = if (heightHint > 0) FormatOptionsBuilder.qualityName(heightHint) else "Video"
        rows.add(0, DownloadOption(if (rows.isEmpty()) single else if (maxRes > 0) "Best (up to ${maxRes}p)" else "Best quality", bestDetail, bestFormat, height = maxRes))
        rows.add(DownloadOption("Audio only", "M4A · size unknown", "ba/b", audioOnly = true))
        return rows
    }
}
