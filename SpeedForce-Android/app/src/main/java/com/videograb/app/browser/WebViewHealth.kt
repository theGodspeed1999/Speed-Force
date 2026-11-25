package com.videograb.app.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.webkit.WebViewCompat
import com.videograb.app.Prefs
import com.videograb.app.util.VersionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps an eye on the phone's web engine ("Android System WebView", or Chrome on older phones).
 * All page security fixes come from Google through the Play Store, so an old engine is the
 * biggest risk for any browser app. This compares it with Google's newest stable version.
 */
object WebViewHealth {
    data class Status(
        val packageName: String,
        val appName: String,
        val installed: String,
        val latest: String?,
        val behind: Int,
        val outdated: Boolean,
    )

    /**
     * What Google is sending to phones right now, with each version's share of phones.
     * A new version first goes to a fraction of a percent of phones and reaches everyone over a few
     * weeks; until then the Play Store says "up to date" on most phones. So the version that counts is
     * the one most phones are being given, not the newest one published (that wrongly warned on 156
     * while 158 had reached 0.25% of phones).
     */
    private const val FEED =
        "https://versionhistory.googleapis.com/v1/chrome/platforms/webview/channels/stable/versions/all/releases?filter=endtime=none&pageSize=50"
    private const val DAY = 24L * 3600 * 1000

    fun installed(context: Context): Pair<String, String> {
        val pkg = WebViewCompat.getCurrentWebViewPackage(context)
        return (pkg?.packageName ?: "com.google.android.webview") to (pkg?.versionName ?: "unknown")
    }

    /**
     * The version Google is giving most phones now (cached for a day), and the newest one it has
     * started to send out ("158 (first phones)"). Null when offline.
     */
    suspend fun latest(force: Boolean = false): String? = withContext(Dispatchers.IO) {
        val cached = Prefs.webViewServed
        if (!force && cached.isNotEmpty() && System.currentTimeMillis() - Prefs.webViewServedAt < DAY) {
            return@withContext cached
        }
        try {
            val conn = URL(FEED).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val v = servedVersion(body) ?: return@withContext cached.ifEmpty { null }
            Prefs.webViewServed = v
            Prefs.webViewServedAt = System.currentTimeMillis()
            Prefs.webViewNewest = newestVersion(body) ?: ""
            v
        } catch (e: Exception) {
            cached.ifEmpty { null }
        }
    }

    /** Newest version Google has started sending out (maybe only to a few phones so far). */
    val newestStarted: String get() = Prefs.webViewNewest

    private fun releases(body: String): List<Pair<String, Double>> {
        val arr = JSONObject(body).optJSONArray("releases") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { it.optString("version") to it.optDouble("fraction", 0.0) }
            .filter { VersionUtils.major(it.first) > 0 }
    }

    /** The major version with the biggest share of phones; within it, its newest release. */
    fun servedVersion(body: String): String? {
        val rel = releases(body)
        if (rel.isEmpty()) return null
        val share = rel.groupBy { VersionUtils.major(it.first) }.mapValues { e -> e.value.sumOf { it.second } }
        val major = share.maxByOrNull { it.value }?.key ?: return null
        return rel.filter { VersionUtils.major(it.first) == major }.map { it.first }.maxWithOrNull(VersionUtils.comparator)
    }

    fun newestVersion(body: String): String? = releases(body).map { it.first }.maxWithOrNull(VersionUtils.comparator)

    suspend fun check(context: Context, force: Boolean = false): Status {
        val (pkg, ver) = installed(context)
        val latest = latest(force)
        val appName = if (pkg.contains("chrome")) "Chrome (your phone's web engine)" else "Android System WebView"
        return Status(pkg, appName, ver, latest, VersionUtils.behind(ver, latest), VersionUtils.isOutdated(ver, latest))
    }

    /** Opens the Play Store page where the web engine can be updated. */
    fun openUpdatePage(context: Context, packageName: String) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
