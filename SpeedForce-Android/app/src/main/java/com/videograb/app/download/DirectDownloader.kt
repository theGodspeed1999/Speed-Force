package com.videograb.app.download

import android.net.Uri
import android.webkit.MimeTypeMap
import com.videograb.app.engine.ProgressParser
import com.videograb.app.engine.RequestContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import kotlin.coroutines.cancellation.CancellationException

/**
 * Plain file download (documents, apps, archives, pictures... anything that is not a video page).
 * Uses the app's own connection, so it also goes through the encrypted-DNS relay, carries the browser's
 * cookies, and continues where it stopped after Pause.
 */
object DirectDownloader {
    class Result(val file: File, val name: String, val mime: String)

    fun run(
        url: String, ctx: RequestContext, dir: File, titleHint: String,
        shouldStop: () -> Boolean, progress: (percent: Float, speed: String, eta: String, total: String) -> Unit,
    ): Result {
        dir.mkdirs()
        val part = File(dir, "download.part")
        val metaFile = File(dir, "download.meta")
        var offset = if (part.exists()) part.length() else 0L
        var u = url
        var conn: HttpURLConnection? = null
        try {
            // Follow redirects ourselves so the headers go with every hop
            for (hop in 0 until 8) {
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000
                c.readTimeout = 30_000
                ctx.userAgent?.let { c.setRequestProperty("User-Agent", it) }
                ctx.referer?.let { c.setRequestProperty("Referer", it) }
                ctx.acceptLanguage?.let { c.setRequestProperty("Accept-Language", it) }
                c.setRequestProperty("Accept", "*/*")
                val host = Uri.parse(u).host?.lowercase()?.removePrefix("www.")
                ctx.cookies.entries.firstOrNull { it.key.removePrefix("www.") == host }?.let { c.setRequestProperty("Cookie", it.value) }
                if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
                val code = c.responseCode
                if (code in 301..303 || code == 307 || code == 308) {
                    val next = c.getHeaderField("Location") ?: throw IllegalStateException("Redirect without address")
                    u = URL(URL(u), next).toString()
                    c.disconnect()
                    continue
                }
                if (code == 416 && part.exists()) { // already complete
                    conn = c
                    break
                }
                if (code !in 200..299) { c.disconnect(); throw IllegalStateException("Server answered HTTP $code") }
                conn = c
                break
            }
            val c = conn ?: throw IllegalStateException("Too many redirects")
            val resumed = c.responseCode == 206
            if (!resumed) offset = 0L
            val name = fileName(c, u, titleHint)
            val mime = (c.contentType?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "application/octet-stream" })
                ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            metaFile.writeText(name + "\n" + mime)
            val remaining = c.contentLengthLong
            val total = if (remaining > 0) remaining + offset else -1L
            if (c.responseCode != 416) {
                RandomAccessFile(part, "rw").use { out ->
                    if (!resumed) out.setLength(0)
                    out.seek(offset)
                    val buf = ByteArray(64 * 1024)
                    var done = offset
                    var lastTick = System.currentTimeMillis()
                    var lastBytes = done
                    c.inputStream.use { inp ->
                        while (true) {
                            if (shouldStop()) throw CancellationException("stopped")
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 500) {
                                val rate = (done - lastBytes) * 1000.0 / (now - lastTick)
                                val eta = if (total > 0 && rate > 1) formatEta(((total - done) / rate).toLong()) else ""
                                progress(
                                    if (total > 0) done * 100f / total else 0f,
                                    ProgressParser.humanSize(rate.toLong()) + "/s", eta,
                                    if (total > 0) ProgressParser.humanSize(total) else ProgressParser.humanSize(done),
                                )
                                lastTick = now
                                lastBytes = done
                            }
                        }
                    }
                    if (total > 0 && done < total) throw IllegalStateException("Connection closed early (${ProgressParser.humanSize(done)} of ${ProgressParser.humanSize(total)}). Press Retry to continue.")
                }
            }
            val final = File(dir, name)
            if (final.exists()) final.delete()
            part.renameTo(final)
            return Result(final, name, mime)
        } finally {
            conn?.disconnect()
        }
    }

    /** Name from Content-Disposition, else the address, else the page title. */
    private fun fileName(c: HttpURLConnection, url: String, hint: String): String {
        val cd = c.getHeaderField("Content-Disposition") ?: ""
        var name: String? = Regex("""filename\*\s*=\s*[^']*''([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (name == null) name = Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
        if (name == null) name = Uri.parse(url).lastPathSegment?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        if (name.isNullOrBlank() || !name.contains('.')) {
            val ext = c.contentType?.substringBefore(';')?.trim()?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            val base = (name?.takeIf { it.isNotBlank() } ?: hint.ifBlank { "download" })
            name = if (ext != null && !base.endsWith(".$ext")) "$base.$ext" else base
        }
        return name!!.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().take(120).ifEmpty { "download" }
    }

    private fun formatEta(sec: Long): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)
}
