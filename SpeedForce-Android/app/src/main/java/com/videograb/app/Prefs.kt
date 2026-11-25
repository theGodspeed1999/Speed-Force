package com.videograb.app

import android.content.Context
import android.content.SharedPreferences
import com.videograb.app.util.UrlUtils
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All user settings, stored in simple SharedPreferences. */
object Prefs {
    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        sp = context.getSharedPreferences("videograb", Context.MODE_PRIVATE)
    }

    /** One stored setting: `var x by pref("key", default)`. */
    private fun <T> pref(key: String, default: T) = object : ReadWriteProperty<Any?, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = when (default) {
            is Boolean -> sp.getBoolean(key, default)
            is Int -> sp.getInt(key, default)
            is Long -> sp.getLong(key, default)
            else -> sp.getString(key, default as String)
        } as T

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            val e = sp.edit()
            when (value) {
                is Boolean -> e.putBoolean(key, value)
                is Int -> e.putInt(key, value)
                is Long -> e.putLong(key, value)
                else -> e.putString(key, value as String)
            }
            e.apply()
        }
    }

    var folderName: String
        get() = sp.getString("folder", "VideoGrab")!!.ifBlank { "VideoGrab" }
        set(v) = sp.edit().putString("folder", v.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")).apply()

    /** 0 = no limit ("Best"), otherwise max height in pixels, e.g. 1080. */
    var maxResolution by pref("maxRes", 1080)

    var maxParallel: Int
        get() = sp.getInt("parallel", 2).coerceIn(1, 3)
        set(v) = sp.edit().putInt("parallel", v.coerceIn(1, 3)).apply()

    var privateMode by pref("private", false)

    /** HIDE = ads load invisibly + popups go to the Ads billboard; BLOCK = ads never load; OFF = nothing filtered. */
    var adMode: String
        get() = sp.getString("adMode", null) ?: if (sp.getBoolean("adblock", true)) "HIDE" else "OFF"
        set(v) = sp.edit().putString("adMode", v).apply()

    /** Open sites over https:// even when the link says http:// (ask before falling back). */
    var httpsOnly by pref("httpsOnly", true)
    /** Look up sites through Cloudflare (encrypted) instead of the internet provider, which blocks some. */
    var secureDns by pref("secureDns", true)

    /** Third-party cookies let ad networks follow you across sites. Off unless a player needs them. */
    var thirdPartyCookies by pref("3pCookies", false)

    /** "speedforce://start" = the Speed Force start page (stats + top sites), or a web address. */
    var homePage by pref("home", "speedforce://start")

    /** SYSTEM, LIGHT or DARK. */
    var themeMode by pref("theme", "SYSTEM")
    /** Ask web pages to use their dark look too (Chrome's "force dark"). */
    var darkPages by pref("darkPages", false)

    // ---- shields: sites where the user switched shields down ----
    fun shieldsDown(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        val h = host.removePrefix("www.")
        return sp.getStringSet("shieldsOff", emptySet())!!.contains(h)
    }

    fun setShieldsDown(host: String, down: Boolean) {
        val h = host.removePrefix("www.")
        val set = HashSet(sp.getStringSet("shieldsOff", emptySet())!!)
        if (down) set.add(h) else set.remove(h)
        sp.edit().putStringSet("shieldsOff", set).apply()
    }

    fun shieldsDownHosts(): Set<String> = sp.getStringSet("shieldsOff", emptySet())!!.toSet()

    // ---- bookmarks ----
    fun bookmarks(): List<Pair<String, String>> {
        val arr = JSONArray(sp.getString("bookmarks", "[]"))
        return (0 until arr.length()).map { arr.getJSONObject(it).let { o -> o.optString("t") to o.optString("u") } }
    }

    fun isBookmarked(url: String) = bookmarks().any { it.second == url }

    fun toggleBookmark(title: String, url: String): Boolean {
        val list = bookmarks().toMutableList()
        val had = list.removeAll { it.second == url }
        if (!had) list.add(0, title to url)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("t", it.first).put("u", it.second)) }
        sp.edit().putString("bookmarks", arr.toString()).apply()
        return !had
    }

    // ---- lifetime shield statistics (shown on the start page) ----
    var statBlocked by pref("statBlocked", 0L)
    var statBytes by pref("statBytes", 0L)
    var lastEngineUpdate by pref("engineUpdated", 0L)
    var filtersUpdated by pref("filtersUpdated", 0L)
    var latestWebView by pref("latestWebView", "")
    var latestWebViewCheckedAt by pref("latestWebViewAt", 0L)
    /** Web engine version most phones are being given now, when that was checked, and the newest started. */
    var webViewServed by pref("webViewServed", "")
    var webViewServedAt by pref("webViewServedAt", 0L)
    var webViewNewest by pref("webViewNewest", "")
    /** The web engine's own Accept-Language header, and what it was measured for (engine + phone language). */
    var acceptLanguage by pref("acceptLanguage", "")
    var acceptLanguageKey by pref("acceptLanguageKey", "")

    /** Last time we showed the "web engine out of date" warning (once a day at most). */
    var webViewWarnedAt by pref("webViewWarnedAt", 0L)

    var searchEngine: String
        get() = sp.getString("engine", null)?.takeIf { it in UrlUtils.engines } ?: "Google"
        set(v) = sp.edit().putString("engine", v).apply()

    // ---- open tabs, reopened when the app starts again (never in private mode) ----
    var restoreTabs by pref("restoreTabs", true)

    /** Saved tabs: their addresses and which one was in front. */
    fun savedTabs(): Pair<List<String>, Int> {
        if (privateMode || !restoreTabs) return emptyList<String>() to 0
        val o = try { JSONObject(sp.getString("tabs", "{}")!!) } catch (e: Exception) { JSONObject() }
        val arr = o.optJSONArray("u") ?: JSONArray()
        val urls = (0 until arr.length()).map { arr.optString(it) }.filter { it.startsWith("http") }
        return urls to o.optInt("i", 0)
    }

    fun saveTabs(urls: List<String>, current: Int) {
        if (privateMode || !restoreTabs) { clearSavedTabs(); return }
        val arr = JSONArray()
        urls.filter { it.startsWith("http") }.take(50).forEach { arr.put(it) }
        sp.edit().putString("tabs", JSONObject().put("u", arr).put("i", current).toString()).apply()
    }

    fun clearSavedTabs() = sp.edit().remove("tabs").apply()

    // ---- browsing history (skipped in private mode) ----
    fun history(): List<Pair<String, String>> {
        val arr = JSONArray(sp.getString("history", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            o.optString("t") to o.optString("u")
        }
    }

    fun addHistory(title: String, url: String) {
        if (privateMode || url.isBlank() || url.startsWith("about:")) return
        val list = history().filter { it.second != url }.toMutableList()
        list.add(0, title to url)
        val arr = JSONArray()
        list.take(300).forEach { arr.put(JSONObject().put("t", it.first).put("u", it.second)) }
        sp.edit().putString("history", arr.toString()).apply()
    }

    fun clearHistory() = sp.edit().remove("history").apply()
}
