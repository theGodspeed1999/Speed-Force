package com.videograb.app.browser

import com.videograb.app.util.UrlUtils

/**
 * Reads community ad-filter lists (EasyList, uBlock filters, AdGuard, ...: the "Adblock Plus" rule format)
 * and answers two questions: should this request be stopped, and which page elements are ads?
 * Kept free of Android classes so it can be unit tested. Built once on a background thread, then read-only.
 */
class FilterEngine {
    private class Rule(
        val pattern: Array<String>, // the address pattern, split at each *
        val hostAnchor: Boolean,    // || : starts at a domain name
        val leftAnchor: Boolean,    // |  : starts at the beginning of the address
        val rightAnchor: Boolean,   // |  : ends at the end of the address
        val regex: String?,
        val types: Int,
        val party: Int,             // 0 any, 1 only other sites, 2 only the same site
        val domains: Array<String>?,
        val notDomains: Array<String>?,
        val matchCase: Boolean,
    ) {
        val compiled: Regex? by lazy {
            regex?.let { runCatching { Regex(it, if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)) }.getOrNull() }
        }
    }

    private class Index {
        val hosts = HashSet<String>()                    // plain ||host^ rules, the fast path
        val byToken = HashMap<Int, ArrayList<Rule>>()
        val noToken = ArrayList<Rule>()
        fun isEmpty() = hosts.isEmpty() && byToken.isEmpty() && noToken.isEmpty()
    }

    private val block = Index()
    private val allow = Index()
    private val important = Index()

    private val genericAlways = LinkedHashSet<String>()                // selectors with no leading class or id
    private val genericByToken = HashMap<String, ArrayList<String>>()  // ".name" / "#name" -> selectors using it first
    private val specific = HashMap<String, ArrayList<String>>()        // site -> selectors
    private val notHere = HashMap<String, HashSet<String>>()           // site -> selectors that must not apply there
    private val neverGeneric = HashSet<String>()                       // selectors switched off everywhere

    var ruleCount = 0
        private set

    /** Add one whole list. Lines that use features we don't support are skipped safely. */
    fun add(lines: Iterable<String>) {
        val cancelled = HashSet<String>()
        for (l in lines) {
            val i = l.indexOf("badfilter")
            if (i > 0 && !l.startsWith("!")) cancelled.add(l.trim().replace(Regex(",?badfilter"), "").removeSuffix("$"))
        }
        for (raw in lines) {
            val l = raw.trim()
            if (l.isEmpty() || l[0] == '!' || l[0] == '[' || l in cancelled || l.contains("badfilter")) continue
            if (!addCosmetic(l)) addNetwork(l)
        }
    }

    // ---- network rules ----

    private fun addNetwork(line: String) {
        var l = line
        val exception = l.startsWith("@@")
        if (exception) l = l.substring(2)
        var types = 0
        var party = 0
        var domains: Array<String>? = null
        var notDomains: Array<String>? = null
        var matchCase = false
        var isImportant = false

        val isRegex = l.length > 1 && l[0] == '/' && (l.endsWith("/") || l.contains("/$"))
        val dollar = if (isRegex && l.endsWith("/")) -1 else l.lastIndexOf('$').takeIf { it >= 0 && (it == 0 || !l.startsWith("/") || l[it - 1] == '/') } ?: -1
        if (dollar >= 0) {
            val opts = l.substring(dollar + 1)
            if (!opts.contains('=') && opts.none { it == ',' || it.isLetter() || it == '~' || it == '_' }) return
            l = l.substring(0, dollar)
            var notTypes = 0
            for (o in opts.split(',')) {
                when (val name = o.substringBefore('=').lowercase()) {
                    "script" -> types = types or SCRIPT
                    "~script" -> notTypes = notTypes or SCRIPT
                    "image" -> types = types or IMAGE
                    "~image" -> notTypes = notTypes or IMAGE
                    "stylesheet", "css" -> types = types or CSS
                    "~stylesheet", "~css" -> notTypes = notTypes or CSS
                    "xmlhttprequest", "xhr" -> types = types or XHR
                    "~xmlhttprequest", "~xhr" -> notTypes = notTypes or XHR
                    "subdocument", "frame" -> types = types or FRAME
                    "~subdocument", "~frame" -> notTypes = notTypes or FRAME
                    "media" -> types = types or MEDIA
                    "~media" -> notTypes = notTypes or MEDIA
                    "font" -> types = types or FONT
                    "~font" -> notTypes = notTypes or FONT
                    "object", "ping", "beacon", "other", "websocket", "webrtc" -> types = types or OTHER
                    "~object", "~ping", "~other", "~websocket" -> notTypes = notTypes or OTHER
                    "document", "doc" -> types = types or DOCUMENT
                    "popup", "popunder" -> types = types or POPUP
                    "all" -> types = types or ALL
                    "elemhide", "ehide" -> types = types or ELEMHIDE
                    "generichide", "ghide" -> types = types or GENERICHIDE
                    "third-party", "3p", "~first-party", "~1p" -> party = 1
                    "first-party", "1p", "~third-party", "~3p" -> party = 2
                    "match-case" -> matchCase = true
                    "important" -> isImportant = true
                    "domain", "from" -> {
                        val (yes, no) = o.substringAfter('=').lowercase().split('|').partition { !it.startsWith("~") }
                        if (yes.isNotEmpty()) domains = yes.toTypedArray()
                        if (no.isNotEmpty()) notDomains = no.map { it.substring(1) }.toTypedArray()
                    }
                    // A replacement answer still means "stop the real one"
                    "redirect", "rewrite", "empty", "mp4" -> {}
                    "genericblock", "shide", "specifichide", "stealth", "cname", "strict1p", "strict3p" -> {}
                    else -> {
                        // redirect-rule, csp, removeparam, replace, to=, method=, header=, denyallow= ... change
                        // the meaning of the rule in ways we can't follow: applying it blindly could break sites
                        if (name.isNotEmpty()) return
                    }
                }
            }
            if (types == 0 && notTypes != 0) types = ALL_SUB and notTypes.inv()
        }
        if (types == 0) types = ALL_SUB
        if (l.isEmpty() || l == "*" || l == "|" || l == "||") {
            if (domains == null && !exception) return // would block everything
            l = "*"
        }

        val index = if (exception) allow else if (isImportant) important else block
        if (isRegex && l.endsWith("/") && l.length > 2) {
            val rule = Rule(emptyArray(), false, false, false, l.substring(1, l.length - 1), types, party, domains, notDomains, matchCase)
            index.noToken.add(rule)
            ruleCount++
            return
        }

        val hostAnchor = l.startsWith("||")
        val leftAnchor = !hostAnchor && l.startsWith("|")
        var body = if (hostAnchor) l.substring(2) else if (leftAnchor) l.substring(1) else l
        val rightAnchor = body.endsWith("|")
        if (rightAnchor) body = body.dropLast(1)
        if (!matchCase) body = body.lowercase()

        // ||example.com^ with no conditions: a plain set lookup
        if (hostAnchor && types == ALL_SUB && party == 0 && domains == null && notDomains == null && !rightAnchor &&
            body.endsWith("^") && plainHost.matches(body.dropLast(1))) {
            index.hosts.add(body.dropLast(1))
            ruleCount++
            return
        }
        val rule = Rule(body.split('*').filter { it.isNotEmpty() }.toTypedArray(), hostAnchor, leftAnchor, rightAnchor, null, types, party, domains, notDomains, matchCase)
        val token = bestToken(body, hostAnchor || leftAnchor, rightAnchor, index)
        if (token == 0) index.noToken.add(rule) else index.byToken.getOrPut(token) { ArrayList(2) }.add(rule)
        ruleCount++
    }

    /** The rarest whole word of the pattern, so a request only has to check a few rules. */
    private fun bestToken(body: String, anchoredStart: Boolean, anchoredEnd: Boolean, index: Index): Int {
        var best = 0
        var bestSize = Int.MAX_VALUE
        var i = 0
        val b = body.lowercase()
        while (i < b.length) {
            if (!isTokenChar(b[i])) { i++; continue }
            val start = i
            while (i < b.length && isTokenChar(b[i])) i++
            val whole = (start > 0 && b[start - 1] != '*' || start == 0 && anchoredStart) &&
                (i < b.length && b[i] != '*' || i == b.length && anchoredEnd)
            if (!whole || i - start < 3) continue
            val word = b.substring(start, i)
            if (word in commonWords) continue
            val hash = word.hashCode()
            val size = index.byToken[hash]?.size ?: 0
            if (size < bestSize) { best = hash; bestSize = size }
        }
        return best
    }

    /** Does a rule stop this request? [page] is the address of the page or frame asking for it. */
    fun blocks(url: String, page: String?, type: Int): Boolean {
        val lower = url.lowercase()
        val host = UrlUtils.host(url) ?: return false
        val pageHost = page?.let { UrlUtils.host(it) }
        val third = if (pageHost == null) null else UrlUtils.siteOf(host) != UrlUtils.siteOf(pageHost)
        val tokens = tokensOf(lower)
        if (matches(important, url, lower, host, pageHost, third, type, tokens)) return true
        // Pages the lists fully switch off (their rules would break the site)
        if (page != null && !allow.isEmpty() && pageExempt(page, pageHost)) return false
        if (!matches(block, url, lower, host, pageHost, third, type, tokens)) return false
        return !matches(allow, url, lower, host, pageHost, third, type, tokens)
    }

    private fun pageExempt(page: String, pageHost: String?): Boolean {
        val lower = page.lowercase()
        return matchesTyped(allow, page, lower, pageHost ?: return false, null, null, DOCUMENT, tokensOf(lower))
    }

    private fun matches(idx: Index, url: String, lower: String, host: String, pageHost: String?, third: Boolean?, type: Int, tokens: IntArray): Boolean {
        if (type and ALL_SUB != 0 && hostInSet(idx.hosts, host)) return true
        return matchesTyped(idx, url, lower, host, pageHost, third, type, tokens)
    }

    private fun hostInSet(set: Set<String>, host: String): Boolean {
        if (set.isEmpty()) return false
        var h = host
        while (true) {
            if (h in set) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    private fun matchesTyped(idx: Index, url: String, lower: String, host: String, pageHost: String?, third: Boolean?, type: Int, tokens: IntArray): Boolean {
        for (t in tokens) {
            val list = idx.byToken[t] ?: continue
            for (r in list) if (ruleHits(r, url, lower, pageHost, third, type)) return true
        }
        for (r in idx.noToken) if (ruleHits(r, url, lower, pageHost, third, type)) return true
        return false
    }

    private fun ruleHits(r: Rule, url: String, lower: String, pageHost: String?, third: Boolean?, type: Int): Boolean {
        if (r.types and type == 0) return false
        if (r.party == 1 && third == false) return false
        if (r.party == 2 && third == true) return false
        r.domains?.let { d -> if (pageHost == null || d.none { siteMatches(pageHost, it) }) return false }
        r.notDomains?.let { d -> if (pageHost != null && d.any { siteMatches(pageHost, it) }) return false }
        r.regex?.let { return r.compiled?.containsMatchIn(url) == true }
        return patternHits(r, if (r.matchCase) url else lower)
    }

    private fun patternHits(r: Rule, u: String): Boolean {
        val p = r.pattern
        if (p.isEmpty()) return true
        val scheme = u.indexOf("://")
        if (r.hostAnchor) {
            val hostStart = if (scheme < 0) 0 else scheme + 3
            val hostEnd = u.indexOfAny(hostEnders, hostStart).let { if (it < 0) u.length else it }
            var s = hostStart
            while (s in 0 until hostEnd) {
                if (restHits(p, r.rightAnchor, u, s, true)) return true
                s = u.indexOf('.', s).let { if (it < 0 || it >= hostEnd) -1 else it + 1 }
            }
            return false
        }
        return restHits(p, r.rightAnchor, u, 0, r.leftAnchor)
    }

    private fun restHits(p: Array<String>, rightAnchor: Boolean, u: String, from: Int, fixedStart: Boolean): Boolean {
        var pos = from
        for ((n, piece) in p.withIndex()) {
            val mustBeHere = n == 0 && fixedStart
            val last = n == p.size - 1
            var at = pos
            var found = -1
            while (at <= u.length) {
                val end = pieceEnd(piece, u, at)
                if (end >= 0 && (!(last && rightAnchor) || end == u.length)) { found = end; break }
                if (mustBeHere) return false
                at++
            }
            if (found < 0) return false
            pos = found
        }
        return true
    }

    /** Where [piece] ends if it matches at [at], else -1. ^ stands for a separator or the end of the address. */
    private fun pieceEnd(piece: String, u: String, at: Int): Int {
        var i = at
        for (c in piece) {
            if (c == '^') {
                if (i == u.length) continue
                if (isUrlWordChar(u[i])) return -1
                i++
            } else {
                if (i >= u.length || u[i] != c) return -1
                i++
            }
        }
        return i
    }

    private fun tokensOf(u: String): IntArray {
        val out = ArrayList<Int>(24)
        var i = 0
        while (i < u.length) {
            if (!isTokenChar(u[i])) { i++; continue }
            val s = i
            while (i < u.length && isTokenChar(u[i])) i++
            if (i - s >= 3) out.add(u.substring(s, i).hashCode())
        }
        return out.distinct().toIntArray()
    }

    // ---- element hiding ("cosmetic") rules ----

    private fun addCosmetic(l: String): Boolean {
        val i = l.indexOf('#').takeIf { it >= 0 } ?: return false
        val isHide = l.startsWith("##", i)
        val isExcept = l.startsWith("#@#", i)
        if (!isHide && !isExcept) {
            // #?# #$# #%# (scripts and restyling) are not plain hiding: recognised, not used
            return l.startsWith("#?#", i) || l.startsWith("#$#", i) || l.startsWith("#%#", i) || l.startsWith("#@?#", i) || l.startsWith("#@$#", i)
        }
        val sel = l.substring(i + if (isHide) 2 else 3).trim()
        if (sel.isEmpty() || sel.startsWith("+js") || sel.startsWith("^") || procedural.containsMatchIn(sel)) return true
        val sites = if (i == 0) emptyList() else l.substring(0, i).lowercase().split(',')
        val yes = sites.filter { !it.startsWith("~") }
        val no = sites.filter { it.startsWith("~") }.map { it.substring(1) }
        if (isExcept) {
            if (yes.isEmpty()) neverGeneric.add(sel) else yes.forEach { notHere.getOrPut(it) { HashSet(2) }.add(sel) }
            return true
        }
        no.forEach { notHere.getOrPut(it) { HashSet(2) }.add(sel) }
        if (yes.isEmpty()) {
            val key = leadingToken.find(sel)?.value
            if (key != null) genericByToken.getOrPut(key) { ArrayList(1) }.add(sel) else genericAlways.add(sel)
        } else {
            yes.forEach { specific.getOrPut(it) { ArrayList(2) }.add(sel) }
        }
        ruleCount++
        return true
    }

    /** Selectors to hide on [host], given the class names and ids the page uses. */
    fun selectorsFor(host: String, page: String, classes: Collection<String>, ids: Collection<String>, includeBase: Boolean): List<String> {
        val h = host.lowercase().removePrefix("www.")
        val lower = page.lowercase()
        val tokens = tokensOf(lower)
        if (matchesTyped(allow, page, lower, h, null, null, ELEMHIDE, tokens)) return emptyList()
        val noGeneric = matchesTyped(allow, page, lower, h, null, null, GENERICHIDE, tokens)
        val skip = HashSet<String>()
        val out = LinkedHashSet<String>()
        forEachSiteKey(h) { key ->
            notHere[key]?.let { skip.addAll(it) }
            if (includeBase) specific[key]?.let { out.addAll(it) }
        }
        if (!noGeneric) {
            if (includeBase) out.addAll(genericAlways)
            classes.forEach { c -> genericByToken[".$c"]?.let { out.addAll(it) } }
            ids.forEach { c -> genericByToken["#$c"]?.let { out.addAll(it) } }
        }
        out.removeAll(skip)
        out.removeAll(neverGeneric)
        return out.toList()
    }

    /** a.b.example.com -> a.b.example.com, b.example.com, example.com, and the "example.*" form lists use. */
    private inline fun forEachSiteKey(host: String, action: (String) -> Unit) {
        var h = host
        while (true) {
            action(h)
            val first = h.substringBefore('.')
            if (h.contains('.')) action("$first.*")
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
    }

    private fun siteMatches(host: String, entry: String): Boolean {
        if (entry.endsWith(".*")) {
            val name = entry.dropLast(2)
            var h = host
            while (true) {
                if (h.startsWith("$name.")) return true
                val dot = h.indexOf('.')
                if (dot < 0) return false
                h = h.substring(dot + 1)
            }
        }
        return host == entry || host.endsWith(".$entry")
    }

    companion object {
        const val SCRIPT = 1
        const val IMAGE = 2
        const val CSS = 4
        const val XHR = 8
        const val FRAME = 16
        const val MEDIA = 32
        const val FONT = 64
        const val OTHER = 128
        const val ALL_SUB = 255
        const val DOCUMENT = 256
        const val POPUP = 512
        const val ALL = ALL_SUB or DOCUMENT or POPUP
        private const val ELEMHIDE = 1024
        private const val GENERICHIDE = 2048

        private val plainHost = Regex("""^[a-z0-9.-]+$""")
        private val hostEnders = charArrayOf('/', '?', '#', ':')
        private val commonWords = setOf("http", "https", "www", "com", "net", "org", "html", "php", "png", "jpg", "gif")
        private val leadingToken = Regex("""^[.#][\w-]+""")
        private val procedural = Regex(""":(has-text|contains|upward|xpath|matches-|min-text|nth-ancestor|others|remove|style|watch-attr|-abp-|if)|\+js\(""")

        private fun isTokenChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '%' || c in 'A'..'Z'
        private fun isUrlWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-' || c == '.' || c == '%'

        /** What kind of file a request is for, from what the browser says it accepts and the file name. */
        fun typeOf(url: String, accept: String?, isMainFrame: Boolean): Int {
            if (isMainFrame) return DOCUMENT
            val a = accept?.lowercase() ?: ""
            if (a.contains("text/html")) return FRAME
            if (a.startsWith("image/") || a.contains("image/webp")) return IMAGE
            if (a.contains("text/css")) return CSS
            val path = url.substringBefore('#').substringBefore('?').lowercase()
            return when {
                path.endsWith(".js") || path.endsWith(".mjs") -> SCRIPT
                imageFile.containsMatchIn(path) -> IMAGE
                mediaFile.containsMatchIn(path) -> MEDIA
                fontFile.containsMatchIn(path) -> FONT
                path.endsWith(".css") -> CSS
                else -> SCRIPT or XHR or MEDIA or OTHER // can't tell
            }
        }

        private val imageFile = Regex("""\.(png|jpe?g|gif|webp|svg|avif|ico)$""")
        private val mediaFile = Regex("""\.(mp4|webm|m3u8|mpd|ts|m4s|m4v|mp3|m4a)$""")
        private val fontFile = Regex("""\.(woff2?|ttf|otf|eot)$""")
    }
}
