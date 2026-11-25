package com.videograb.app.engine

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.videograb.app.util.UrlUtils
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Headers/cookies the browser used, so yt-dlp looks like the same browser. */
data class RequestContext(
    val referer: String? = null,
    val userAgent: String? = null,
    val origin: String? = null,
    /** "a=1; b=2" cookie strings, keyed by host. */
    val cookies: Map<String, String> = emptyMap(),
    /** The browser's Accept-Language; some hosts refuse a playlist when it differs from the player's. */
    val acceptLanguage: String? = null,
) {

    companion object {
        /** Capture the WebView's cookies for every URL involved. */
        fun capture(context: Context, urls: List<String?>, referer: String?, userAgent: String? = null, origin: String? = null): RequestContext {
            val cm = CookieManager.getInstance()
            val map = HashMap<String, String>()
            urls.filterNotNull().filter { UrlUtils.isHttp(it) }.forEach { u ->
                val h = UrlUtils.host(u) ?: return@forEach
                val c = try { cm.getCookie(u) } catch (e: Exception) { null }
                if (!c.isNullOrBlank()) map[h] = c
            }
            return RequestContext(
                referer, userAgent ?: Engine.defaultUserAgent(context), origin, map,
                com.videograb.app.browser.BrowserHeaders.acceptLanguage,
            )
        }
    }
}

/** Thin wrapper around youtubedl-android (yt-dlp + Python + ffmpeg). */
object Engine {
    private const val TAG = "Engine"
    private val ready = CompletableDeferred<Boolean>()
    @Volatile var initError: String? = null
        private set

    /** Unpacks Python/yt-dlp/ffmpeg on first run (takes a few seconds). Call from a background thread. */
    fun init(context: Context) {
        try {
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            com.yausername.aria2c.Aria2c.getInstance().init(context)
            ready.complete(true)
        } catch (e: Throwable) {
            Log.e(TAG, "init failed", e)
            initError = e.message ?: e.toString()
            ready.complete(false)
        }
    }

    suspend fun awaitReady(): Boolean = ready.await()

    @Volatile private var ua: String? = null
    fun defaultUserAgent(context: Context): String =
        ua ?: try { WebSettings.getDefaultUserAgent(context) } catch (e: Exception) { "Mozilla/5.0 (Linux; Android) Mobile" }.also { ua = it }

    /**
     * Adds referer / user-agent / cookie options to a yt-dlp request.
     * Cookies go into [cookieFile] inside the app's private storage (no other app can read it);
     * the caller deletes it when yt-dlp is done.
     */
    fun applyContext(req: YoutubeDLRequest, ctx: RequestContext, cookieFile: File) {
        // Through the app's relay, so sites the provider blocks are found (also used by aria2c)
        com.videograb.app.net.SecureDns.proxyUrl()?.let { req.addOption("--proxy", it) }
        ctx.userAgent?.let { req.addOption("--user-agent", it) }
        ctx.referer?.let { req.addOption("--referer", it) }
        ctx.origin?.let { req.addOption("--add-header", "Origin:$it") }
        // Same language header as the browser (yt-dlp's own "en-us,en;q=0.5" gets refused by some hosts)
        ctx.acceptLanguage?.let { req.addOption("--add-header", "Accept-Language:$it") }
        val cookieText = ctx.cookies.entries.joinToString("") { UrlUtils.netscapeCookies(it.key, it.value) }
        if (cookieText.isNotEmpty()) {
            cookieFile.parentFile?.mkdirs()
            cookieFile.writeText("# Netscape HTTP Cookie File\n$cookieText")
            req.addOption("--cookies", cookieFile.absolutePath)
        }
    }

    fun cookieFile(context: Context, name: String) = File(File(context.cacheDir, "cookies"), "$name.txt")

    /** Ask yt-dlp which videos/formats exist at [url]. Throws with yt-dlp's message on failure. */
    suspend fun fetchInfo(context: Context, url: String, ctx: RequestContext): List<MediaInfo> = withContext(Dispatchers.IO) {
        if (!awaitReady()) throw IllegalStateException("Download engine failed to start: $initError")
        val req = YoutubeDLRequest(url)
        req.addOption("-J")
        req.addOption("--no-warnings")
        req.addOption("--playlist-items", "1:10")
        req.addOption("--socket-timeout", "20")
        val cookies = cookieFile(context, "info-" + System.nanoTime())
        applyContext(req, ctx, cookies)
        val resp = try {
            YoutubeDL.getInstance().execute(req, null, null)
        } finally {
            cookies.delete()
        }
        val list = FormatParser.parse(resp.out)
        if (list.isEmpty()) throw IllegalStateException("No video found")
        list
    }

    /** The library only remembers the version after an update, so otherwise ask yt-dlp itself. */
    fun version(context: Context): String = try {
        YoutubeDL.getInstance().version(context)
            ?: YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("--version")).out.trim().ifEmpty { null }
            ?: "unknown"
    } catch (e: Throwable) { "unknown" }

    /** Downloads the newest yt-dlp release. Returns a plain-language result. */
    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        try {
            if (!awaitReady()) return@withContext "Engine not ready: $initError"
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel._STABLE)
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Updated to ${version(context)}"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up to date (${version(context)})"
                else -> "Finished: $status"
            }
        } catch (e: Throwable) {
            Log.e(TAG, "update failed", e)
            "Update failed: ${e.message}"
        }
    }

    /** Turns long yt-dlp errors into one readable line. */
    fun shortError(e: Throwable): String {
        val msg = e.message ?: e.toString()
        val line = msg.lines().firstOrNull { it.contains("ERROR") } ?: msg.lines().firstOrNull { it.isNotBlank() } ?: msg
        return line.replace(Regex("""^.*?ERROR:\s*"""), "").take(300)
    }
}
