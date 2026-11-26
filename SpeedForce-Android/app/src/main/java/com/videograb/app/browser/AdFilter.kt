package com.videograb.app.browser

import android.content.Context
import android.webkit.WebResourceResponse
import com.videograb.app.Prefs
import com.videograb.app.util.UrlUtils
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

enum class AdMode { HIDE, BLOCK, OFF }
enum class AdClass { NONE, AD, TRACKER }

/**
 * Decides what to do with each request:
 *  - trackers / miners / push-spam (assets/trackers.txt): always blocked (sites can't notice)
 *  - ad networks (assets/adhosts.txt): HIDE = allowed to load but made invisible,
 *    BLOCK = blocked, OFF = left alone.
 *  - everything the community filter lists name (assets/filters, kept up to date): blocked, unless OFF.
 */
object AdFilter {
    @Volatile private var adHosts: Set<String> = emptySet()
    @Volatile private var trackerHosts: Set<String> = emptySet()

    /** Filled on a background thread at start; requests made before that use the two small lists. */
    @Volatile private var engine: FilterEngine? = null

    /** The same lists uBlock Origin and Brave use. Only rules (data), never code. */
    private val LISTS = linkedMapOf(
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "ublock" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
        "ublock-privacy" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/privacy.txt",
        "ublock-badware" to "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/badware.txt",
        "adguard-mobile" to "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        "peterlowe" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
    )
    private const val UPDATE_EVERY_MS = 3L * 24 * 3600 * 1000

    fun init(context: Context) {
        adHosts = load(context, "adhosts.txt")
        trackerHosts = load(context, "trackers.txt")
        val app = context.applicationContext
        thread(name = "ad-lists", priority = Thread.MIN_PRIORITY) {
            engine = buildEngine(app)
            if (System.currentTimeMillis() - Prefs.filtersUpdated > UPDATE_EVERY_MS && updateLists(app)) engine = buildEngine(app)
        }
    }

    private fun listDir(context: Context) = File(context.filesDir, "filters")

    private fun buildEngine(context: Context): FilterEngine? = try {
        val e = FilterEngine()
        val started = System.currentTimeMillis()
        for (name in LISTS.keys + "extra") {
            val fresh = File(listDir(context), "$name.txt")
            e.add(if (fresh.exists()) fresh.readLines() else context.assets.open("filters/$name.txt").bufferedReader().readLines())
        }
        android.util.Log.i("AdFilter", "${e.ruleCount} rules ready in ${System.currentTimeMillis() - started} ms")
        e
    } catch (t: Throwable) {
        android.util.Log.e("AdFilter", "could not read the lists", t)
        null
    }

    /** New ad networks appear daily: fetch the lists every few days. True when something new arrived. */
    private fun updateLists(context: Context): Boolean {
        var any = false
        listDir(context).mkdirs()
        for ((name, address) in LISTS) {
            try {
                val c = URL(address).openConnection() as HttpURLConnection
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val text = c.inputStream.bufferedReader().use { it.readText() }
                // A captive portal or error page is not a filter list
                if (c.responseCode != 200 || text.length < 10_000 || text.trimStart().startsWith("<")) continue
                val tmp = File(listDir(context), "$name.tmp")
                tmp.writeText(text)
                if (tmp.renameTo(File(listDir(context), "$name.txt"))) any = true
            } catch (t: Throwable) {
                android.util.Log.w("AdFilter", "update of $name failed: ${t.message}")
            }
        }
        if (any) Prefs.filtersUpdated = System.currentTimeMillis()
        return any
    }

    private fun load(context: Context, name: String): Set<String> = try {
        context.assets.open(name).bufferedReader().useLines { lines ->
            lines.map { it.substringBefore('#').trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
        }
    } catch (e: Exception) {
        emptySet()
    }

    val mode: AdMode get() = runCatching { AdMode.valueOf(Prefs.adMode) }.getOrDefault(AdMode.HIDE)

    /** [type] is what the request is for (see [FilterEngine.typeOf]); a page itself is never checked against the big lists. */
    fun classify(url: String, page: String? = null, type: Int = FilterEngine.ALL_SUB or FilterEngine.POPUP): AdClass {
        if (mode == AdMode.OFF) return AdClass.NONE
        val host = UrlUtils.host(url) ?: return AdClass.NONE
        return when {
            UrlUtils.isBlockedHost(host, trackerHosts) -> AdClass.TRACKER
            // Domain-hopping ad loaders (casino overlays, "smart link" redirects): always blocked
            UrlUtils.isRotatingAdLoader(url) -> AdClass.TRACKER
            UrlUtils.isBlockedHost(host, adHosts) -> AdClass.AD
            type != FilterEngine.DOCUMENT && engine?.blocks(url, page, type) == true -> AdClass.TRACKER
            else -> AdClass.NONE
        }
    }

    fun isAd(url: String?): Boolean = url != null && classify(url) != AdClass.NONE

    /** Style rules hiding the ads named by the lists on this page. */
    fun hidingCss(host: String, page: String, classes: Collection<String>, ids: Collection<String>, first: Boolean): String {
        if (mode == AdMode.OFF) return ""
        val selectors = engine?.selectorsFor(host, page, classes, ids, first) ?: return ""
        // One rule per selector: a selector this web engine doesn't know must not cancel the others
        return selectors.joinToString("\n") { "$it{display:none!important}" }
    }

    /** Should this network request be stopped? */
    fun shouldBlock(c: AdClass): Boolean = c == AdClass.TRACKER || (c == AdClass.AD && mode == AdMode.BLOCK)

    /** Settings handed to the in-page script (detect.js). */
    fun jsConfig(): String {
        val arr = JSONArray()
        if (mode != AdMode.OFF) adHosts.forEach { arr.put(it) }
        val off = JSONArray()
        com.videograb.app.Prefs.shieldsDownHosts().forEach { off.put(it) }
        return "window.__vgCfg={mode:'${mode.name}',ads:$arr,wake:'$wakeKey',off:$off,reject:${com.videograb.app.Prefs.rejectCookies}};\n"
    }

    /** Secret for the app's "start the player" message, so a page can't send it to its frames. */
    private val wakeKey = java.util.UUID.randomUUID().toString().replace("-", "")

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Video-player ad engines (Google IMA): blocking them breaks the whole player, so they always load. */
    private val playerEngines = setOf("imasdk.googleapis.com")

    /**
     * What to answer instead of a blocked ad request; null = let it load.
     * A player asking for its pre-roll ad gets a valid "no ad today" (empty VAST): it then starts
     * the real video at once, where an error would leave it stuck or waiting.
     */
    fun blockedResponse(url: String, origin: String?, type: Int = FilterEngine.OTHER): WebResourceResponse? {
        val host = UrlUtils.host(url) ?: return emptyResponse()
        if (playerEngines.any { host == it || host.endsWith(".$it") }) return null
        val headers = HashMap<String, String>()
        if (!origin.isNullOrEmpty()) {
            headers["Access-Control-Allow-Origin"] = origin
            headers["Access-Control-Allow-Credentials"] = "true"
        }
        // A normal, empty answer of the right kind: the page sees no failed request to complain about
        val (mime, body) = when {
            UrlUtils.isVastRequest(url) -> "text/xml" to EMPTY_VAST.toByteArray()
            type == FilterEngine.IMAGE -> "image/gif" to ONE_PIXEL_GIF
            type == FilterEngine.FRAME -> "text/html" to ByteArray(0)
            type == FilterEngine.SCRIPT -> "text/javascript" to ByteArray(0)
            type == FilterEngine.CSS -> "text/css" to ByteArray(0)
            else -> "text/plain" to ByteArray(0)
        }
        return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(body))
    }

    private val ONE_PIXEL_GIF = android.util.Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", android.util.Base64.DEFAULT)

    private const val EMPTY_VAST = """<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"></VAST>"""
}
