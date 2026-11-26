package com.videograb.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.videograb.app.Prefs
import com.videograb.app.util.StreamKind
import com.videograb.app.util.UrlUtils
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A video/stream address seen while the page loaded, with the headers needed to fetch it again. */
data class SniffedStream(
    val url: String,
    val kind: StreamKind,
    val referer: String?,
    val userAgent: String?,
    val pageUrl: String,
    val foundAt: Long = System.currentTimeMillis(),
)

interface TabListener {
    fun onVideoTapped(tab: BrowserTab, frameUrl: String, src: String, title: String, durationSec: Int, p: Int)
    /** A popup was caught. [url] may be null when it isn't known; [toBillboard] = it opened on the Ads screen. */
    fun onPopup(tab: BrowserTab, url: String?, toBillboard: Boolean)
    /** A tab for a popup the user's tap opened; it is not on screen until [showPopupTab]. */
    fun newPopupTab(): BrowserTab
    fun showPopupTab(tab: BrowserTab)
    fun openInNewTab(url: String)
    fun onFileDownload(tab: BrowserTab, url: String, mimeType: String?)
    fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback)
    fun onHideFullscreen()
    /** The https:// version of a site failed; ask before using insecure http://. */
    fun onInsecureFallback(tab: BrowserTab, httpUrl: String)
    fun onCertificateError(tab: BrowserTab, url: String)
    fun onTabCrashed(tab: BrowserTab)
}

@SuppressLint("SetJavaScriptEnabled")
class BrowserTab(val id: Int, private val context: Context, private val listener: TabListener) {
    var url by mutableStateOf("")
    var title by mutableStateOf("New tab")
    var progress by mutableIntStateOf(100)
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var videoCount by mutableIntStateOf(0)
    /** True while the page is on plain http:// (shown as a warning in the address bar). */
    var insecure by mutableStateOf(false)
    /** Trackers and ads stopped on the page you are on, and ads hidden by the page script. */
    var blocked by mutableIntStateOf(0)
    var hiddenAds by mutableIntStateOf(0)
    /** Small picture of the page, shown on the tab card. */
    var thumb by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)

    /** Takes a small picture of what the page shows now (for the tab grid). */
    fun capture() {
        try {
            if (isStart || webView.width < 50 || webView.height < 50 || webView.parent == null) return
            val scale = 360f / webView.width
            val bmp = android.graphics.Bitmap.createBitmap(360, (webView.height * scale).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.RGB_565)
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale, scale)
            webView.draw(canvas)
            thumb = bmp.asImageBitmap()
        } catch (e: Throwable) {
            Log.w("BrowserTab", "capture failed: $e")
        }
    }

    /** Asked the site for its desktop version. */
    var desktop by mutableStateOf(false)
    private val blockedCounter = java.util.concurrent.atomic.AtomicInteger()
    private val flushCount = Runnable { blocked = blockedCounter.get() }
    val streams = mutableStateListOf<SniffedStream>()
    /** When the page last opened a popup (to catch "tab-under" ads, see shouldOverrideUrlLoading). */
    @Volatile private var lastPopupAt = 0L
    /** When you last pressed Back (to catch "back-button trap" ads). */
    @Volatile private var lastBackAt = 0L
    /** When the current page finished loading (0 = still loading). */
    @Volatile private var pageFinishedAt = 0L
    /** Message boxes (alert/confirm) shown by the current page, to stop endless "virus!" loops. */
    private val dialogTimes = ArrayDeque<Long>()
    private var dialogsMuted = false

    private val main = Handler(Looper.getMainLooper())
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val frameVideos = ConcurrentHashMap<String, Int>()
    @Volatile private var currentUrl = ""
    private var scriptHandler: ScriptHandler? = null
    private var lastVideoTap = 0L
    @Volatile private var lastTouchAt = 0L
    /** Hosts we switched from http to https, so we can offer a fallback if https fails. */
    private val upgradedHosts = ConcurrentHashMap.newKeySet<String>()

    val webView: WebView = WebView(context)

    init {
        // Fill the screen: without this the WebView is in "grow with content" mode, where the CSS
        // screen-height units (100vh, 100dvh) are 0 and age checks / dialogs sized with them collapse.
        webView.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        WebSecurity.harden(webView)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.setSupportMultipleWindows(true)          // new windows come to onCreateWindow, where we decide
        // Hide mode: even tap-less popups "open" (into the billboard) so sites can't detect blocking.
        // Other modes: window.open() without a tap is refused outright.
        s.javaScriptCanOpenWindowsAutomatically = AdFilter.mode == AdMode.HIDE

        // Page → app messages. The modern channel tells us WHICH site sent each message,
        // so ad frames can't trigger anything; old WebViews fall back to the classic bridge.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "VideoGrabBridge", setOf("*")) { _, message, origin, isMainFrame, reply ->
                handleMessage(message.data ?: return@addWebMessageListener, origin.toString(), isMainFrame, reply)
            }
        } else {
            webView.addJavascriptInterface(LegacyBridge(), "VideoGrabNative")
        }
        installScript()

        webView.webViewClient = Client()
        webView.webChromeClient = Chrome()
        // A file only downloads after you tapped something: pages that start downloads by themselves (ads, scams) are refused
        webView.setOnTouchListener { _, ev -> if (ev.action == android.view.MotionEvent.ACTION_DOWN) lastTouchAt = System.currentTimeMillis(); false }
        webView.setDownloadListener { dlUrl, _, _, mime, _ ->
            if (System.currentTimeMillis() - lastTouchAt < 8000) listener.onFileDownload(this, dlUrl, mime)
            else main.post { android.widget.Toast.makeText(context, "Refused a download the page started by itself", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    /** (Re)install detect.js with the current ad settings; call after settings change. */
    fun installScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        scriptHandler?.remove()
        // Runs in the main page AND every iframe, before the page's own scripts
        scriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, fullScript(context), setOf("*"))
    }

    fun applySettings() {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, Prefs.thirdPartyCookies)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, Prefs.darkPages)
        }
        webView.settings.javaScriptCanOpenWindowsAutomatically = AdFilter.mode == AdMode.HIDE
        installScript()
    }

    /** Clean up a link before opening: drop tracking codes, prefer https. */
    private fun prepare(target: String): String {
        var u = UrlUtils.stripTracking(target)
        if (Prefs.httpsOnly) {
            val host = UrlUtils.host(u)
            if (host != null && host !in httpAllowed) {
                val up = UrlUtils.upgradeToHttps(u)
                if (up != u) upgradedHosts.add(host)
                u = up
            }
        }
        return u
    }

    /** The Speed Force start page is drawn by the app, not by the web engine. */
    val isStart: Boolean get() = url == START

    fun load(target: String) {
        pendingUrl = null
        if (target == START) {
            url = START
            title = "New tab"
            progress = 100
            insecure = false
            updateNav()
            return
        }
        lastTouchAt = System.currentTimeMillis() // you asked for this address
        if (target.isNotBlank()) webView.loadUrl(prepare(target))
    }

    /** Show a page made by the app itself (the picture upload for Lens); [baseUrl] is the site it posts to. */
    fun loadHtml(baseUrl: String, html: String) {
        pendingUrl = null
        lastTouchAt = System.currentTimeMillis()
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "utf-8", null)
    }

    /** Address of a reopened tab that loads only when you open it. */
    var pendingUrl: String? = null
        private set

    fun loadLater(target: String) {
        pendingUrl = target
        url = target
        title = UrlUtils.host(target) ?: target
    }

    fun loadPending() {
        pendingUrl?.let { load(it) }
    }

    /**
     * Back, skipping fake history steps: ad scripts add the same page to the history again and again
     * so that Back seems to do nothing (or opens their ad). Steps that are the same page are skipped.
     */
    fun goBack() {
        lastBackAt = System.currentTimeMillis()
        if (isStart) {
            // Leave the start page: back to the page the web engine still shows
            val shown = webView.url
            if (!shown.isNullOrBlank() && shown != "about:blank") { url = shown; title = webView.title ?: shown; updateNav() }
            return
        }
        val list = webView.copyBackForwardList()
        val cur = list.currentIndex
        val here = list.currentItem?.url?.substringBefore('#')
        var steps = 1
        while (cur - steps > 0 && list.getItemAtIndex(cur - steps)?.url?.substringBefore('#') == here) steps++
        if (webView.canGoBackOrForward(-steps)) webView.goBackOrForward(-steps) else webView.goBack()
    }

    private fun resetPageState(newUrl: String) {
        currentUrl = newUrl
        blockedCounter.set(0)
        blocked = 0
        hiddenAds = 0
        seen.clear()
        frameVideos.clear()
        streams.clear()
        videoCount = 0
    }

    private fun addStream(pieceUrl: String, kind: StreamKind, referer: String?, ua: String?) {
        val url = UrlUtils.wholeFile(pieceUrl)
        if (!seen.add(UrlUtils.dedupeKey(url))) return
        val page = currentUrl
        main.post {
            streams.add(0, SniffedStream(url, kind, referer ?: page, ua, page))
            while (streams.size > 40) streams.removeAt(streams.size - 1)
        }
    }

    private fun updateNav() {
        canGoBack = if (isStart) !webView.url.isNullOrBlank() && webView.url != "about:blank" else webView.canGoBack()
        canGoForward = webView.canGoForward()
    }

    /** Messages from detect.js. Runs on the main thread. */
    private fun handleMessage(data: String, origin: String?, isMainFrame: Boolean, reply: androidx.webkit.JavaScriptReplyProxy? = null) {
        if (data.length > 200_000) return
        val o = try { JSONObject(data) } catch (e: Exception) { return }
        // Ignore anything sent from an ad network's frame
        if (origin != null && AdFilter.isAd(origin)) return
        when (o.optString("t")) {
            "video" -> {
                val now = System.currentTimeMillis()
                if (now - lastVideoTap < 1500) return // a page can't spam the picker
                lastVideoTap = now
                listener.onVideoTapped(this, o.optString("frame"), o.optString("src"), o.optString("title"), o.optDouble("dur", 0.0).let { if (it.isNaN() || it.isInfinite()) 0 else it.toInt() }, o.optInt("p"))
            }
            "videos" -> {
                frameVideos[o.optString("frame")] = o.optInt("n").coerceIn(0, 100)
                videoCount = frameVideos.values.sum()
            }
            "stream" -> {
                val u = o.optString("url")
                val kind = UrlUtils.classifyStream(u) ?: return
                if (AdFilter.isAd(u)) return
                addStream(u, kind, o.optString("frame").ifEmpty { null }, null)
            }
            "cosmetic" -> {
                val r = reply ?: return
                val strings = { key: String -> o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList() }
                val host = o.optString("host")
                val page = o.optString("page")
                val classes = strings("c")
                val ids = strings("i")
                val first = o.optBoolean("first")
                listWork.execute {
                    val css = AdFilter.hidingCss(host, page, classes, ids, first)
                    if (css.isNotEmpty()) main.post { runCatching { r.postMessage(css) } }
                }
            }
            "hidden" -> {
                val arr = o.optJSONArray("frames")
                val frames = (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }.take(50)
                AdBillboard.addAdFrames(frames, o.optInt("n").coerceIn(0, 500), o.optString("page"))
                if (isMainFrame) hiddenAds = (hiddenAds + o.optInt("n").coerceIn(0, 500)).coerceAtMost(9999)
            }
        }
    }

    /** Old-WebView fallback (no sender information available). */
    inner class LegacyBridge {
        @JavascriptInterface
        fun postMessage(data: String?) {
            if (data != null) main.post { handleMessage(data, null, false) }
        }
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url.toString()
            if (request.isForMainFrame && popupRefused(u)) return true
            val scheme = request.url.scheme?.lowercase() ?: ""
            // Block app-store / intent:// jumps and other non-web links that ads use
            if (scheme != "http" && scheme != "https" && scheme != "about" && scheme != "data" && scheme != "blob") return true
            if (!request.isForMainFrame) return false
            if (shieldsDownHere()) return false
            // A link you tapped may go anywhere that isn't a known ad network; a jump you didn't tap is checked fully
            when (AdFilter.classify(u, currentUrl, if (request.hasGesture()) FilterEngine.DOCUMENT else FilterEngine.ALL_SUB or FilterEngine.POPUP)) {
                AdClass.TRACKER -> return true
                AdClass.AD -> {
                    // An ad tried to take over the page: send it to the billboard and stay here
                    if (AdFilter.mode == AdMode.HIDE) AdBillboard.redirect(context, u, currentUrl)
                    return true
                }
                AdClass.NONE -> {}
            }
            // "Tab-under" ad: the page opens itself again in a new tab and switches THIS tab to an ad
            // (or an ad page on a fresh domain that no list knows yet). A jump to another site right
            // after a popup is treated as that ad; the page you were on stays.
            if (AdFilter.mode != AdMode.OFF && System.currentTimeMillis() - lastPopupAt < 2000 &&
                currentUrl.startsWith("http") && !UrlUtils.sameSite(u, currentUrl)) {
                Log.i("BrowserTab", "tab-under ad stopped: $u")
                return stopJump(u, offer = null)
            }
            // A jump to another site that you did NOT tap: right after Back ("back-button trap") or while
            // you are reading the page ("You won an iPhone!" scam pages on fresh domains no list knows).
            // Server redirects and taps are normal and pass. "Open in tab" on the notice still lets you go.
            if (AdFilter.mode != AdMode.OFF && !request.hasGesture() && !request.isRedirect &&
                currentUrl.startsWith("http") && !UrlUtils.sameSite(u, currentUrl)) {
                val now = System.currentTimeMillis()
                val afterBack = now - lastBackAt < 3000
                val whileReading = pageFinishedAt > 0 && now - pageFinishedAt > 3000
                if (afterBack || whileReading) {
                    Log.i("BrowserTab", "silent redirect stopped (afterBack=$afterBack): $u")
                    return stopJump(u, offer = u)
                }
            }
            val clean = prepare(u)
            if (clean != u && request.method.equals("GET", true)) {
                view.loadUrl(clean)
                return true
            }
            return false
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url.toString()
            val h = request.requestHeaders
            val type = FilterEngine.typeOf(u, h["Accept"] ?: h["accept"], request.isForMainFrame)
            val down = shieldsDownHere()
            val c = if (down) AdClass.NONE else AdFilter.classify(u, h["Referer"] ?: h["referer"] ?: currentUrl, type)
            if (AdFilter.shouldBlock(c)) {
                if (c == AdClass.TRACKER) AdBillboard.countTracker()
                val answer = AdFilter.blockedResponse(u, h["Origin"] ?: h["origin"], type)
                if (answer != null) {
                    hideInPage(u, type)
                    ShieldStats.record(type)
                    blockedCounter.incrementAndGet()
                    main.removeCallbacks(flushCount)
                    main.postDelayed(flushCount, 250)
                }
                return answer
            }
            if (c == AdClass.AD) return null // HIDE mode: let it load; the page script hides it
            val kind = UrlUtils.classifyStream(u)
            if (kind != null) {
                val h = request.requestHeaders
                val ref = h["Referer"] ?: h["referer"]
                val ua = h["User-Agent"] ?: h["user-agent"]
                addStream(u, kind, ref, ua)
            }
            return null
        }

        override fun onPageStarted(view: WebView, pageUrl: String, favicon: Bitmap?) {
            if (popupRefused(pageUrl)) { view.stopLoading(); return }
            pageFinishedAt = 0L
            dialogsMuted = false
            dialogTimes.clear()
            if (UrlUtils.dedupeKey(pageUrl) != UrlUtils.dedupeKey(currentUrl)) resetPageState(pageUrl)
            currentUrl = pageUrl
            url = pageUrl
            insecure = pageUrl.startsWith("http://", true)
            updateNav()
        }

        override fun onPageFinished(view: WebView, pageUrl: String) {
            if (pageFinishedAt == 0L) pageFinishedAt = System.currentTimeMillis()
            main.postDelayed({ capture() }, 1500)
            url = pageUrl
            title = view.title?.ifBlank { null } ?: pageUrl
            updateNav()
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                view.evaluateJavascript(fullScript(view.context), null)
            }
            Prefs.addHistory(title, pageUrl)
        }

        override fun doUpdateVisitedHistory(view: WebView, pageUrl: String, isReload: Boolean) {
            url = pageUrl
            updateNav()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) offerHttpFallback(request.url.toString())
        }

        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
            // Never accept a bad certificate (that is how "man-in-the-middle" attacks work)
            handler.cancel()
            val u = error.url ?: return
            if (UrlUtils.dedupeKey(u) == UrlUtils.dedupeKey(currentUrl) || u == view.url) {
                if (!offerHttpFallback(u)) listener.onCertificateError(this@BrowserTab, u)
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // A page crashed (or was killed for memory). Keep the app alive and replace the tab.
            Log.w("BrowserTab", "renderer gone, crash=${detail.didCrash()}")
            listener.onTabCrashed(this@BrowserTab)
            return true
        }
    }

    /** Blocked pictures and frames leave an empty box behind: tell the page to fold it away. */
    private fun hideInPage(u: String, type: Int) {
        if (type != FilterEngine.IMAGE && type != FilterEngine.FRAME) return
        if (blockedForPage.size < 200) blockedForPage.add(u)
        main.removeCallbacks(flushBlocked)
        main.postDelayed(flushBlocked, 300)
    }

    private val blockedForPage = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val flushBlocked = Runnable {
        val arr = org.json.JSONArray()
        while (true) arr.put(blockedForPage.poll() ?: break)
        if (arr.length() > 0) webView.evaluateJavascript("window.__vgHide&&__vgHide($arr)", null)
    }

    /** Keep the page: the jump goes to the Ads billboard (Hide mode) and a notice offers [offer]. */
    private fun stopJump(u: String, offer: String?): Boolean {
        val hide = AdFilter.mode == AdMode.HIDE
        if (hide) AdBillboard.redirect(context, u, currentUrl)
        listener.onPopup(this, offer, hide)
        return true
    }

    /** If we upgraded this host to https and it failed, offer http (once per host). */
    private fun offerHttpFallback(failedUrl: String): Boolean {
        val host = UrlUtils.host(failedUrl) ?: return false
        if (!failedUrl.startsWith("https://", true) || !upgradedHosts.remove(host)) return false
        listener.onInsecureFallback(this, "http://" + failedUrl.substring(8))
        return true
    }

    /** The user agreed to use http:// for this site (until the app is closed). */
    fun allowHttpAndLoad(httpUrl: String) {
        UrlUtils.host(httpUrl)?.let { httpAllowed.add(it) }
        webView.loadUrl(httpUrl)
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
        }

        override fun onReceivedTitle(view: WebView, t: String?) {
            if (!t.isNullOrBlank()) title = t
        }

        override fun onPermissionRequest(request: PermissionRequest) = WebSecurity.deny(request)

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) =
            WebSecurity.denyLocation(origin, callback)

        // --- message boxes (alert / confirm / prompt) ---
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (!dialogAllowed(url, message)) { result.confirm(); return true }
            showDialog(url, message, result, withCancel = false)
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (!dialogAllowed(url, message)) { result.cancel(); return true }
            showDialog(url, message, result, withCancel = true)
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (!dialogAllowed(url, message)) { result.cancel(); return true }
            return false // normal text question from the page: the standard box
        }

        /** "Are you sure you want to leave?" Scam pages use it to trap you: leaving is always allowed. */
        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (AdFilter.mode == AdMode.OFF) return false
            result.confirm()
            return true
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
            val transport = resultMsg.obj as WebView.WebViewTransport
            lastPopupAt = System.currentTimeMillis()
            if (isUserGesture) {
                transport.webView = popupTarget(view)
                resultMsg.sendToTarget()
                return true
            }
            // From here on: popups nobody asked for (no tap)
            if (AdFilter.mode == AdMode.HIDE) {
                // Let the popup really open — just somewhere you don't have to look at it
                transport.webView = AdBillboard.newWindowTarget(context, currentUrl) { }
                resultMsg.sendToTarget()
                listener.onPopup(this@BrowserTab, null, true)
                return true
            }
            listener.onPopup(this@BrowserTab, null, false)
            return false
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            listener.onShowFullscreen(view, callback)
        }

        override fun onHideCustomView() {
            listener.onHideFullscreen()
        }
    }

    /**
     * May the page show this message box? Not when it is a scam text ("virus", "you won"), comes from
     * another site's frame (an ad), or the page keeps showing boxes (more than 2 in 10 seconds).
     */
    private fun dialogAllowed(fromUrl: String?, message: String?): Boolean {
        if (AdFilter.mode == AdMode.OFF) return true
        val now = System.currentTimeMillis()
        val reason = when {
            dialogsMuted -> "muted"
            fromUrl != null && currentUrl.startsWith("http") && !UrlUtils.sameSite(fromUrl, currentUrl) -> "other site"
            UrlUtils.isScamMessage(message) -> "scam text"
            else -> {
                while (dialogTimes.isNotEmpty() && now - dialogTimes.first() > 10_000) dialogTimes.removeFirst()
                dialogTimes.addLast(now)
                if (dialogTimes.size > 2) { dialogsMuted = true; "too many" } else null
            }
        }
        if (reason == null) return true
        Log.i("BrowserTab", "message box stopped ($reason): ${message?.take(80)}")
        AdBillboard.addAdFrames(emptyList(), 1, currentUrl)
        return false
    }

    private fun showDialog(fromUrl: String?, message: String?, result: JsResult, withCancel: Boolean) {
        var answered = false
        fun answer(ok: Boolean) {
            if (answered) return
            answered = true
            if (ok) result.confirm() else result.cancel()
        }
        try {
            val b = android.app.AlertDialog.Builder(context)
                .setTitle(UrlUtils.host(fromUrl ?: currentUrl) ?: "This page")
                .setMessage(message?.take(1000) ?: "")
                .setPositiveButton("OK") { _, _ -> answer(true) }
                .setNeutralButton("Block messages") { _, _ -> dialogsMuted = true; answer(!withCancel) }
                .setOnCancelListener { answer(!withCancel) }
            if (withCancel) b.setNegativeButton("Cancel") { _, _ -> answer(false) }
            b.show()
        } catch (e: Exception) {
            answer(!withCancel)
        }
    }

    /** Set on a popup tab until its first web address is known: answers "may it be shown?". */
    private var popupGate: ((String) -> Boolean)? = null

    /** True when this load must be dropped (an unwanted popup). */
    private fun popupRefused(u: String): Boolean {
        val gate = popupGate ?: return false
        if (!UrlUtils.isHttp(u)) return false
        popupGate = null
        return !gate(u)
    }

    /**
     * The window for a popup opened by a tap. Many sites open the very thing you asked for this way (a chat
     * room, a player, a login) and then move the old tab on. It becomes a real tab when it stays on the same
     * site, or is the link that was tapped. Anything else is treated as a popup ad, as before.
     */
    private fun popupTarget(view: WebView): WebView {
        val opener = currentUrl
        val hit = view.hitTestResult
        val tappedLink = hit.extra?.takeIf { hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE }
        val child = listener.newPopupTab()
        child.popupGate = { u ->
            val tapped = tappedLink != null && UrlUtils.dedupeKey(u) == UrlUtils.dedupeKey(tappedLink)
            val wanted = AdFilter.mode == AdMode.OFF || (!AdFilter.isAd(u) && (UrlUtils.sameSite(u, opener) || tapped))
            main.post {
                if (wanted) listener.showPopupTab(child)
                else {
                    child.destroy()
                    val hide = AdFilter.mode == AdMode.HIDE
                    if (hide) AdBillboard.redirect(context, u, opener)
                    listener.onPopup(this, u.takeIf { !AdFilter.isAd(it) }, hide)
                }
            }
            wanted
        }
        // a popup that never goes anywhere is thrown away
        main.postDelayed({ if (child.popupGate != null) { child.popupGate = null; child.destroy() } }, 15_000)
        return child.webView
    }

    /** Stops hiding ads on this page (until it is reloaded), so the user can see an ad and close it. */
    fun revealAds() {
        webView.evaluateJavascript("window.__vgReveal&&window.__vgReveal()", null)
    }

    private fun shieldsDownHere(): Boolean = Prefs.shieldsDown(UrlUtils.host(currentUrl))

    /** Shields up/down for the site you are on (sites keep their choice). */
    fun setShieldsDown(down: Boolean) {
        val host = UrlUtils.host(url) ?: return
        Prefs.setShieldsDown(host, down)
        installScript()
        webView.reload()
    }

    /** Ask for the desktop version of the site (or the phone version again) and reload. */
    fun setDesktopSite(on: Boolean) {
        desktop = on
        val def = android.webkit.WebSettings.getDefaultUserAgent(context)
        val chrome = Regex("Chrome/[\\d.]+").find(def)?.value ?: "Chrome/120.0.0.0"
        webView.settings.userAgentString =
            if (on) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $chrome Safari/537.36" else def
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        webView.reload()
    }

    /** Starts the page's players (muted, in every frame) so their stream addresses get captured. */
    fun wakePlayers() {
        webView.evaluateJavascript("window.__vgWake&&window.__vgWake()", null)
    }

    /** A tab you aren't looking at must not keep playing big videos: they use up the phone's memory. */
    fun pause() {
        webView.evaluateJavascript("document.querySelectorAll('video').forEach(function(v){v.pause()})", null)
        webView.onPause()
    }

    fun resume() = webView.onResume()

    fun destroy() {
        try {
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        } catch (_: Exception) {}
    }

    companion object {
        const val START = "speedforce://start"
        private val listWork = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** Sites the user allowed to use insecure http:// this session. */
        val httpAllowed: MutableSet<String> = ConcurrentHashMap.newKeySet()

        @Volatile private var script: String? = null
        private fun detectScript(context: Context): String =
            script ?: context.assets.open("detect.js").bufferedReader().use { it.readText() }.also { script = it }

        fun fullScript(context: Context) = AdFilter.jsConfig() + detectScript(context)
    }
}
