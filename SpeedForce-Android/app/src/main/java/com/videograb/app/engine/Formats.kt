package com.videograb.app.engine

import org.json.JSONObject

/** One format (quality) that yt-dlp reports for a video. */
data class FormatInfo(
    val id: String,
    val ext: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val vcodec: String? = null,
    val acodec: String? = null,
    val fileSize: Long = 0,
    val tbr: Double = 0.0,
    val note: String = "",
    val protocol: String = "",
    val url: String = "",
) {
    val hasVideo: Boolean get() = vcodec != "none" && (height > 0 || (vcodec != null))
    /**
     * Sound-only track. HLS sites (e.g. Twitter/X) often keep sound in a separate track whose codec
     * isn't stated, so "no picture" alone (or an id like "hls-audio-128000") is enough.
     */
    val audioOnly: Boolean
        get() = (vcodec == "none" && acodec != "none") ||
            (vcodec == null && height == 0 && acodec != "none" && id.contains("audio", ignoreCase = true))
    /** Explicitly says "no audio" (only then do we add a separate audio track). */
    val videoOnly: Boolean get() = acodec == "none" && vcodec != "none"

    /** The "p" number people know: the shorter side of the picture (portrait videos too). */
    val pLabel: Int
        get() = if (width > 0 && height > 0) minOf(width, height) else height
}

/** One video found by yt-dlp on a page. */
data class MediaInfo(
    val title: String,
    val webpageUrl: String,
    val extractor: String,
    val formats: List<FormatInfo>,
    val durationSec: Int = 0,
)

/** One row in the resolution picker. */
data class DownloadOption(
    val label: String,
    val detail: String,
    /** yt-dlp "-f" value. */
    val format: String,
    val audioOnly: Boolean = false,
    val height: Int = 0,
)

object FormatParser {

    /**
     * Parse yt-dlp "-J" JSON. A page with several videos comes back as a
     * playlist; every entry that has formats becomes one [MediaInfo].
     */
    fun parse(json: String): List<MediaInfo> {
        val root = JSONObject(json.substring(json.indexOf('{')))
        val out = ArrayList<MediaInfo>()
        collect(root, out)
        return out
    }

    private fun collect(o: JSONObject, out: MutableList<MediaInfo>) {
        val entries = o.optJSONArray("entries")
        if (entries != null && !o.has("formats")) {
            for (i in 0 until entries.length()) entries.optJSONObject(i)?.let { collect(it, out) }
            return
        }
        val formats = ArrayList<FormatInfo>()
        val arr = o.optJSONArray("formats")
        if (arr != null) {
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { formats.add(format(it)) }
        } else if (o.has("format_id") || o.has("url")) {
            formats.add(format(o))
        }
        out.add(
            MediaInfo(
                title = o.optString("title").ifBlank { o.optString("id", "video") },
                webpageUrl = o.optString("webpage_url", o.optString("original_url", "")),
                extractor = o.optString("extractor_key", o.optString("extractor", "")),
                formats = formats,
                durationSec = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
            )
        )
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).ifEmpty { null }

    private fun JSONObject.int(key: String): Int =
        if (isNull(key)) 0 else optDouble(key, 0.0).let { if (it.isNaN()) 0 else it.toInt() }

    private fun format(f: JSONObject): FormatInfo {
        val size = if (!f.isNull("filesize")) f.optLong("filesize", 0) else 0L
        val approx = if (!f.isNull("filesize_approx")) f.optLong("filesize_approx", 0) else 0L
        return FormatInfo(
            id = f.optString("format_id", "best"),
            ext = f.optString("ext", ""),
            width = f.int("width"),
            height = f.int("height"),
            fps = f.int("fps"),
            vcodec = f.str("vcodec"),
            acodec = f.str("acodec"),
            fileSize = if (size > 0) size else approx,
            tbr = if (f.isNull("tbr")) 0.0 else f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
            note = f.optString("format_note", ""),
            protocol = f.optString("protocol", ""),
            url = f.optString("url", ""),
        )
    }
}

object FormatOptionsBuilder {

    /** Higher = plays on more phones. */
    private fun compat(f: FormatInfo): Int {
        val v = f.vcodec?.lowercase() ?: ""
        return when {
            v.startsWith("avc") || v.startsWith("h264") -> 4
            v.isEmpty() && (f.ext == "mp4" || f.protocol.startsWith("m3u8")) -> 4
            v.startsWith("hev") || v.startsWith("hvc") || v.startsWith("h265") -> 3
            v.startsWith("vp9") || v.startsWith("vp09") -> 2
            v.startsWith("av01") -> 1
            else -> 2
        }
    }

    /** "1:02:05" or "4:07". */
    fun duration(sec: Int): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    fun qualityName(p: Int): String = when {
        p >= 4320 -> "${p}p (8K)"
        p >= 2160 -> "${p}p (4K)"
        p >= 1440 -> "${p}p (2K)"
        else -> "${p}p"
    }

    /**
     * Build the picker rows: "Best", one row per resolution (highest first), "Audio only".
     * @param maxRes default resolution limit from settings (0 = no limit), used by "Best".
     */
    fun build(info: MediaInfo, maxRes: Int): List<DownloadOption> {
        val formats = info.formats.map { f ->
            // No size given: estimate it from bitrate (kbit/s) x duration
            if (f.fileSize <= 0 && f.tbr > 0 && info.durationSec > 0) f.copy(fileSize = (f.tbr * 1000 / 8 * info.durationSec).toLong())
            else f
        }
        val hasSeparateAudio = formats.any { it.audioOnly }
        val bestAudioSize = formats.filter { it.audioOnly }.maxOfOrNull { it.fileSize } ?: 0L

        val video = formats.filter { it.hasVideo && it.pLabel > 0 }
        val byRes = video.groupBy { it.pLabel }
        val rows = ArrayList<DownloadOption>()

        val bestFormat = if (maxRes > 0)
            "bv*[height<=$maxRes]+ba/b[height<=$maxRes]/bv*+ba/b"
        else "bv*+ba/b"
        val bestLabel = if (maxRes > 0) "Best (up to ${maxRes}p)" else "Best quality"
        rows.add(DownloadOption(bestLabel, "Automatic: picks the best available", bestFormat, height = maxRes))

        for (p in byRes.keys.sortedDescending()) {
            val candidates = byRes.getValue(p)
            val pick = candidates.sortedWith(
                compareByDescending<FormatInfo> { compat(it) }
                    .thenByDescending { it.fps }
                    .thenByDescending { it.tbr }
                    .thenByDescending { it.fileSize }
            ).first()
            val needsAudio = pick.videoOnly && hasSeparateAudio
            val fmt = if (needsAudio) "${pick.id}+ba[ext=m4a]/${pick.id}+ba/${pick.id}" else pick.id
            val size = pick.fileSize + if (needsAudio) bestAudioSize else 0L
            val fpsText = if (pick.fps > 30) " ${pick.fps}fps" else ""
            val detail = listOfNotNull(
                savedAs(pick),
                if (size > 0) "~" + ProgressParser.humanSize(size) else "size unknown",
                if (pick.videoOnly && !hasSeparateAudio) "no sound" else null,
            ).joinToString(" · ")
            rows.add(DownloadOption(qualityName(p) + fpsText, detail, fmt, height = p))
        }

        if (byRes.isEmpty() && formats.size > 1) {
            // Formats without size info (e.g. some direct streams): list them as they are
            formats.filter { !it.audioOnly }.forEachIndexed { i, f ->
                val label = "Version ${i + 1}" + if (f.note.isNotBlank()) " (${f.note})" else ""
                val detail = listOfNotNull(savedAs(f),
                    if (f.fileSize > 0) "~" + ProgressParser.humanSize(f.fileSize) else "size unknown").joinToString(" · ")
                rows.add(DownloadOption(label, detail, f.id))
            }
        }

        // "Best" shows what it will really fetch: the first row within the limit
        val bestRow = rows.drop(1).firstOrNull { maxRes <= 0 || it.height in 1..maxRes } ?: rows.getOrNull(1)
        rows[0] = rows[0].copy(detail = if (bestRow != null) bestRow.label + " · " + bestRow.detail else (formats.firstOrNull()?.let { savedAs(it) } ?: "MP4") + " · " + (formats.maxOfOrNull { it.fileSize }?.takeIf { it > 0 }?.let { "~" + ProgressParser.humanSize(it) } ?: "size unknown"))

        val audioDetail = if (bestAudioSize > 0) "M4A · ~" + ProgressParser.humanSize(bestAudioSize) else "M4A · size unknown"
        rows.add(DownloadOption("Audio only", audioDetail, "ba/b", audioOnly = true))
        return rows
    }

    /** Every video is saved as MP4; says so, and from what when it has to be converted. */
    private fun savedAs(f: FormatInfo): String = when {
        f.protocol.startsWith("m3u8") || f.ext == "m3u8" -> "MP4 (from HLS)"
        f.protocol.contains("dash") || f.ext == "mpd" -> "MP4 (from DASH)"
        f.ext.isEmpty() || f.ext.equals("mp4", true) -> "MP4"
        else -> "MP4 (from ${f.ext.uppercase()})"
    }

    /** Index of the row to highlight by default (closest resolution at or below the limit). */
    fun defaultIndex(options: List<DownloadOption>, maxRes: Int): Int {
        if (maxRes <= 0) return 0
        val idx = options.indexOfFirst { !it.audioOnly && it.height in 1..maxRes && !it.label.startsWith("Best") }
        return if (idx >= 0) idx else 0
    }
}
