package com.videograb.app.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import com.videograb.app.Prefs
import com.videograb.app.util.UrlUtils

/**
 * Cookie guard: a site works normally while it is open; when its last tab is closed, its cookies are thrown
 * away. Sites on the trusted list keep theirs, so you stay signed in there.
 */
object CookieGuard {
    fun trusted(host: String): Boolean = Prefs.trustedSites().any { UrlUtils.sameSite("https://$it/", "https://$host/") }

    /** A page was shown: remembered until cleaned, so it is also cleaned if the app is killed. */
    fun note(host: String) {
        if (Prefs.cookieGuard && !trusted(host)) Prefs.setGuardSeen(Prefs.guardSeen() + host)
    }

    fun clean(host: String) {
        if (!Prefs.cookieGuard || host.isEmpty() || trusted(host)) return
        val cookies = CookieManager.getInstance()
        val url = "https://$host/"
        val names = cookies.getCookie(url).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        // a cookie is only removed when the same domain is named again, so each parent domain is tried
        val parts = host.split('.')
        val domains = listOf("") + (0..parts.size - 2).map { "; Domain=" + parts.drop(it).joinToString(".") }
        for (n in names) for (d in domains) cookies.setCookie(url, "$n=; Max-Age=0; Path=/; Secure$d")
        cookies.flush()
        WebStorage.getInstance().deleteOrigin("https://$host")
        Prefs.setGuardSeen(Prefs.guardSeen() - host)
    }

    private fun openIn(host: String, urls: List<String>) = urls.any { UrlUtils.sameSite(it, "https://$host/") }

    /** A tab is closing: clean every site it showed that no other tab has open. */
    fun tabClosed(visited: Set<String>, stillOpen: List<String>) {
        for (h in visited) if (!openIn(h, stillOpen)) clean(h)
    }

    /** At start: clean what was left over, except sites that are open again. */
    fun atStart(openUrls: List<String>) {
        for (h in Prefs.guardSeen()) if (!openIn(h, openUrls)) clean(h)
    }
}
