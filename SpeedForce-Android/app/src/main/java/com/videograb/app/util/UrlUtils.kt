package com.videograb.app.util

import java.net.URI
import java.net.URLEncoder

enum class StreamKind { HLS, DASH, MP4, WEBM }

/** Pure helper functions for URLs. Kept free of Android classes so they can be unit tested. */
object UrlUtils {

    private val urlInText = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val looksLikeDomain = Regex("""^[\w-]+(\.[\w-]+)+(:\d+)?(/.*)?$""")

    /** Search engines: name to the address the search words are added to. */
    val engines = linkedMapOf(
        "DuckDuckGo" to "https://duckduckgo.com/?q=",
        "Google" to "https://www.google.com/search?q=",
        "Bing" to "https://www.bing.com/search?q=",
        "Brave" to "https://search.brave.com/search?q=",
    )

    /** The search words when [url] is a results page of one of [engines], else null. */
    fun searchQuery(url: String): String? {
        if (engines.values.none { url.startsWith(it.substringBefore('?')) }) return null
        val q = Regex("[?&]q=([^&#]+)").find(url)?.groupValues?.get(1) ?: return null
        return try { java.net.URLDecoder.decode(q, "UTF-8") } catch (e: Exception) { null }
    }

    /** Turn what the user typed in the address bar into a URL (or a search). */
    fun toUrlOrSearch(input: String, searchBase: String = "https://duckduckgo.com/?q="): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) return text
        if (text.startsWith("about:") || text.startsWith("file:")) return text
        if (!text.contains(' ') && looksLikeDomain.matches(text)) return "https://$text"
        return searchBase + URLEncoder.encode(text, "UTF-8")
    }

    /** Find the first web link inside shared text like "Look at this! https://site/x". */
    fun extractUrl(text: String?): String? {
        if (text == null) return null
        val m = urlInText.find(text) ?: return null
        return m.value.trimEnd('.', ',', ')', ']', '!', '?', ';')
    }

    fun host(url: String): String? {
        val h = try {
            URI(url.trim()).host
        } catch (e: Exception) {
            null
        } ?: Regex("""^[a-z]+://([^/:?#]+)""", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)
        return h?.lowercase()?.removePrefix("www.")
    }

    fun origin(url: String): String? = try {
        val u = URI(url)
        if (u.scheme == null || u.host == null) null
        else u.scheme + "://" + u.host + (if (u.port != -1) ":" + u.port else "")
    } catch (e: Exception) {
        null
    }

    /** True if the host or any parent domain of it is in [blocked]. */
    fun isBlockedHost(host: String?, blocked: Set<String>): Boolean {
        if (host.isNullOrEmpty()) return false
        var h = host.lowercase().removePrefix("www.")
        while (true) {
            if (h in blocked) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
            if (!h.contains('.')) return false // stop at the top-level domain
        }
    }

    /**
     * Decide whether a network request looks like a video stream worth offering.
     * Small pieces of streams (.ts, .m4s segments) are ignored.
     */
    fun classifyStream(url: String): StreamKind? {
        val lower = url.lowercase()
        if (!lower.startsWith("http")) return null
        val path = lower.substringBefore('#').substringBefore('?').trimEnd('/')
        return when {
            path.endsWith(".m3u8") -> StreamKind.HLS
            path.endsWith(".mpd") -> StreamKind.DASH
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") -> StreamKind.MP4
            path.endsWith(".webm") -> StreamKind.WEBM
            else -> null
        }
    }

    /** ".../film.mp4?bytestart=0&byteend=999&sig=x" -> the whole file. */
    fun wholeFile(url: String): String =
        url.replace(Regex("[?&](bytestart|byteend)=\\d+")) { if (it.value[0] == '?') "?" else "" }.replace("?&", "?").removeSuffix("?")

    /** File names players use for the playlist that lists every quality. */
    fun looksLikeMasterPlaylist(url: String): Boolean {
        val file = url.substringBefore('?').substringAfterLast('/').lowercase()
        return Regex("""^(master|index|playlist|manifest|main)[^/]*\.(m3u8|mpd)$""").matches(file)
    }

    private val rotatingAdPath = Regex("""^/\d{1,4}/[0-9a-f]{32}/?$""")

    /**
     * Ad networks that keep changing their domain name (so no list can keep up) still use the
     * same address shape: https://random-words.org/22/0c68eb516d80d3a1ce9486707ffc5f64
     */
    fun isRotatingAdLoader(url: String): Boolean {
        val path = url.substringAfter("://", "").substringAfter('/', "").substringBefore('?').substringBefore('#')
        if (rotatingAdPath.matches("/$path")) return true
        // Same idea, other networks (fake chat / "in-page push" popups):
        // https://29548.random-words.com/4/js/253804 and their click servers https://29892691-29548-ex.random.com/...
        val host = host(url) ?: return false
        if (numberedAdHost.containsMatchIn(host) && numberedAdPath.matches("/$path")) return true
        return clickServerHost.containsMatchIn(host)
    }

    private val numberedAdHost = Regex("""^\d{3,7}\.[a-z]{6,}\.[a-z]{2,6}$""")
    private val numberedAdPath = Regex("""^/\d{1,3}/js/\d{3,9}/?$""")
    private val clickServerHost = Regex("""^\d{5,10}-\d{3,7}-ex\.""")

    private val vastShape = Regex("""vast|vmap|/gampad/ads|output=xml|[?&]format=vast|\.xml(\?|$)""", RegexOption.IGNORE_CASE)

    /** A video player asking an ad server which ad to play first (VAST/VMAP). */
    fun isVastRequest(url: String): Boolean = vastShape.containsMatchIn(url.substringAfter("://", url))

    /** The site part of a host: "cdn.example.co.uk" -> "example.co.uk", "m.site.com" -> "site.com". */
    fun siteOf(host: String): String {
        val p = host.lowercase().removePrefix("www.").split('.')
        if (p.size <= 2) return p.joinToString(".")
        val twoPart = p[p.size - 1].length == 2 && p[p.size - 2].length <= 3 // co.uk, com.au, co.in
        return p.takeLast(if (twoPart) 3 else 2).joinToString(".")
    }

    fun sameSite(a: String?, b: String?): Boolean {
        val ha = a?.let { host(it) } ?: return false
        val hb = b?.let { host(it) } ?: return false
        return siteOf(ha) == siteOf(hb)
    }

    /** Key used to avoid listing the same stream twice (same file, different query tokens). */
    fun dedupeKey(url: String): String = url.substringBefore('#').substringBefore('?').lowercase()

    private val scamText = Regex(
        """virus|infected|malware|trojan|hacked|been compromised|security (alert|warning)|battery (is )?(damaged|infected)|""" +
            """you( have|'ve)? won|winner|congratulations|claim your|free iphone|prize|gift card|""" +
            """call (support|microsoft|apple)|tech support|your (phone|device) (is|has|will)|clean(er)? (your|now)""",
        RegexOption.IGNORE_CASE,
    )

    /** Text of fake "your phone has a virus" / "you won" message boxes. */
    fun isScamMessage(message: String?): Boolean = message != null && scamText.containsMatchIn(message)

    /** Last part of the address without "?…", e.g. "93818767_720p.mp4". */
    fun fileName(url: String): String = url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')

    private val clipName = Regex("""preview|thumb|teaser|trailer|sprite|promo|(^|[/_-])site\.webm""", RegexOption.IGNORE_CASE)

    /** Hover previews, thumbnails and site-wide background clips: not the film the user wants. */
    fun looksLikePreviewClip(url: String): Boolean = clipName.containsMatchIn(fileName(url))

    /** Short readable name for a stream URL, e.g. "cdn.site.com/…/master.m3u8". */
    fun shortName(url: String): String {
        val h = host(url) ?: ""
        val file = url.substringBefore('?').substringAfterLast('/')
        return if (file.isEmpty()) h else "$h/…/$file"
    }

    /**
     * Build a Netscape cookies.txt (the format yt-dlp reads) from a browser
     * "a=1; b=2" Cookie string for the given host.
     */
    fun netscapeCookies(host: String, cookieHeader: String?): String {
        if (cookieHeader.isNullOrBlank()) return ""
        val sb = StringBuilder()
        cookieHeader.split(';').forEach { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) return@forEach
            val name = part.substring(0, eq).trim()
            val value = part.substring(eq + 1).trim()
            if (name.isEmpty()) return@forEach
            sb.append('.').append(host).append("\tTRUE\t/\tFALSE\t2147483647\t")
                .append(name).append('\t').append(value).append('\n')
        }
        return sb.toString()
    }

    // ---- privacy / security helpers ----

    private val trackingParams = setOf(
        "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "yclid", "twclid", "ttclid",
        "igshid", "mc_eid", "mc_cid", "_hsenc", "_hsmi", "mkt_tok", "oly_anon_id", "oly_enc_id",
        "vero_id", "s_cid", "rb_clickid", "_openstat", "wickedid", "srsltid",
    )

    /** Removes click-tracking codes (utm_*, fbclid, gclid …) from a link, like Brave/Firefox do. */
    fun stripTracking(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val hash = url.indexOf('#', q)
        val query = if (hash < 0) url.substring(q + 1) else url.substring(q + 1, hash)
        val fragment = if (hash < 0) "" else url.substring(hash)
        val kept = query.split('&').filter { part ->
            val name = part.substringBefore('=').lowercase()
            part.isNotEmpty() && !name.startsWith("utm_") && name !in trackingParams
        }
        val base = url.substring(0, q)
        return if (kept.isEmpty()) base + fragment else base + "?" + kept.joinToString("&") + fragment
    }

    /** Home-network / local addresses are left on http (routers, NAS boxes …). */
    fun isLocalHost(host: String?): Boolean {
        if (host.isNullOrEmpty()) return true
        val h = host.lowercase()
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home")) return true
        val ip = Regex("""^(\d+)\.(\d+)\.(\d+)\.(\d+)$""").find(h)?.groupValues?.drop(1)?.map { it.toInt() } ?: return false
        return ip[0] == 10 || ip[0] == 127 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] in 16..31) || (ip[0] == 169 && ip[1] == 254)
    }

    /** "http://site/x" → "https://site/x" (unchanged if already secure, local, or not a web link). */
    fun upgradeToHttps(url: String): String {
        if (!url.startsWith("http://", ignoreCase = true)) return url
        if (isLocalHost(host(url))) return url
        return "https://" + url.substring(7)
    }

    private val mediaExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi", "flv",
        "m4a", "aac", "mp3", "opus", "ogg", "oga", "wav", "flac",
    )

    /** Only real video/audio files may be saved; blocks APKs and other files a site could push. */
    fun isMediaExtension(ext: String?): Boolean = ext != null && ext.lowercase() in mediaExtensions

    fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://", true) || url.startsWith("https://", true))
}
