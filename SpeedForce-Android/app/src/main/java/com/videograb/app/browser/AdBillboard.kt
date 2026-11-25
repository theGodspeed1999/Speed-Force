package com.videograb.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import com.videograb.app.util.UrlUtils

/**
 * The "billboard": everything ad-like that pages tried to show you ends up here instead of
 * in front of you. Popups really open (so the site thinks they worked) but in a hidden,
 * locked-down WebView that you can look at on the Ads screen if you want.
 */
object AdBillboard {
    enum class Kind(val label: String) { POPUP("Popup"), REDIRECT("Redirect"), AD_FRAME("Hidden ad") }

    class Entry(val id: Int, url: String, val page: String, val kind: Kind, val time: Long = System.currentTimeMillis()) {
        var url by androidx.compose.runtime.mutableStateOf(url)
        var webView: WebView? = null
    }

    val entries = mutableStateListOf<Entry>()
    var hiddenOnPages by mutableIntStateOf(0)
    var trackersBlocked by mutableIntStateOf(0)
    var popupsCaught by mutableIntStateOf(0)

    private val main = Handler(Looper.getMainLooper())
    private var nextId = 1
    private const val MAX_ENTRIES = 150
    private const val MAX_LIVE = 1 // live hidden pages kept running (memory/battery)

    fun countTracker() = main.post { trackersBlocked++ }

    fun addAdFrames(urls: List<String>, hiddenCount: Int, page: String) {
        hiddenOnPages += hiddenCount
        val known = entries.filter { it.kind == Kind.AD_FRAME }.map { UrlUtils.dedupeKey(it.url) }.toHashSet()
        urls.filter { UrlUtils.isHttp(it) && known.add(UrlUtils.dedupeKey(it)) }.forEach { add(Entry(nextId++, it, page, Kind.AD_FRAME)) }
    }

    private fun add(e: Entry) {
        entries.add(0, e)
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1).webView?.destroy()
        // Keep only a few hidden pages alive
        entries.filter { it.webView != null }.drop(MAX_LIVE).forEach { it.webView?.destroy(); it.webView = null }
    }

    /**
     * A page asked for a new window: hand it a hidden WebView. [onUrl] reports where it goes.
     */
    fun newWindowTarget(context: Context, page: String, onUrl: (String) -> Unit): WebView {
        popupsCaught++
        val entry = Entry(nextId++, "about:blank", page, Kind.POPUP)
        entry.webView = sandbox(context) { u ->
            if (entry.url == "about:blank" && u != "about:blank") onUrl(u)
            entry.url = u
        }
        add(entry)
        return entry.webView!!
    }

    /** An ad tried to take over the page you were on: load it here instead. */
    fun redirect(context: Context, url: String, page: String) {
        popupsCaught++
        val entry = Entry(nextId++, url, page, Kind.REDIRECT)
        entry.webView = sandbox(context) { entry.url = it }.also { it.loadUrl(url) }
        add(entry)
    }

    /** Open a caught ad on purpose (from the Ads screen). */
    fun open(context: Context, entry: Entry): WebView {
        entry.webView?.let { return it }
        val wv = sandbox(context) { entry.url = it }
        wv.loadUrl(entry.url)
        entry.webView = wv
        entries.filter { it.webView != null && it !== entry }.drop(MAX_LIVE - 1).forEach { it.webView?.destroy(); it.webView = null }
        return wv
    }

    /** The phone is short of memory: close the hidden ad pages. */
    fun dropLive() {
        entries.filter { it.webView != null }.forEach { e ->
            e.webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
            e.webView = null
        }
    }

    fun clear() {
        entries.forEach { e -> e.webView?.let { (it.parent as? ViewGroup)?.removeView(it); it.destroy() } }
        entries.clear()
        hiddenOnPages = 0; trackersBlocked = 0; popupsCaught = 0
    }

    /** A locked-down WebView: no app bridge, no downloads, no app launching, no permissions, no further popups. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun sandbox(context: Context, onUrl: (String) -> Unit): WebView {
        val wv = WebView(context.applicationContext)
        wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) // see BrowserTab
        WebSecurity.harden(wv)
        val s = wv.settings
        s.javaScriptEnabled = true // ads need it to render, which is what keeps sites happy
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = true // no surprise sound
        s.setSupportMultipleWindows(true) // so onCreateWindow below can refuse
        s.javaScriptCanOpenWindowsAutomatically = false
        wv.setDownloadListener { _, _, _, _, _ -> /* never download anything from ads */ }
        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https" // blocks intent://, market://, tel: etc.
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val c = AdFilter.classify(request.url.toString())
                return if (c == AdClass.TRACKER) AdFilter.emptyResponse() else null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = onUrl(url)

            // The page engine died (out of memory): this hidden page is simply gone, the app must not crash
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                entries.firstOrNull { it.webView === view }?.webView = null
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                return true
            }
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message) = false
            override fun onPermissionRequest(request: PermissionRequest) = WebSecurity.deny(request)
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) =
                WebSecurity.denyLocation(origin, callback)
            override fun onJsAlert(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                result.cancel(); return true // ads can't show "You won!" dialogs
            }
            override fun onJsConfirm(view: WebView, url: String?, message: String?, result: android.webkit.JsResult): Boolean {
                result.cancel(); return true
            }
            override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: android.webkit.JsPromptResult): Boolean {
                result.cancel(); return true
            }
        }
        return wv
    }
}
