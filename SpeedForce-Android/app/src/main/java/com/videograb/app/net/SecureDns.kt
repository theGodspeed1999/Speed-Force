package com.videograb.app.net

import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.videograb.app.Prefs
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * "Built-in secure DNS": sends the web pages, the download engine and the app's own requests
 * through [LocalProxy], so blocked sites open without changing the phone's settings.
 */
object SecureDns {
    private const val TAG = "SecureDns"
    private val original: ProxySelector? = ProxySelector.getDefault()

    val active: Boolean get() = Prefs.secureDns && LocalProxy.port != 0

    /** Address yt-dlp should use as its proxy, or null when the feature is off. */
    fun proxyUrl(): String? = if (active) "http://127.0.0.1:${LocalProxy.port}" else null

    /** Call at app start and whenever the switch changes. [web] also routes the web pages (starts the web engine). */
    fun apply(web: Boolean = false) {
        val webOk = web && WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
        if (!Prefs.secureDns) {
            if (webOk) runCatching { ProxyController.getInstance().clearProxyOverride({ it.run() }, {}) }
            ProxySelector.setDefault(original)
            return
        }
        val port = try { LocalProxy.start() } catch (e: Exception) {
            Log.e(TAG, "relay failed to start", e); return
        }
        if (webOk) {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build(), { it.run() }, {}
            )
        } else if (web) {
            Log.w(TAG, "this web engine can't use a relay; pages use the phone's normal lookup")
        }
        val relay = listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                val h = uri?.host?.lowercase() ?: return listOf(Proxy.NO_PROXY)
                if (h == "localhost" || h.startsWith("127.") || h == "::1" || h == "[::1]") return listOf(Proxy.NO_PROXY)
                return relay
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        })
    }
}
