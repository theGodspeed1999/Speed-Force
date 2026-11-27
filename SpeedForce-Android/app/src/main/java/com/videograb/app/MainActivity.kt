package com.videograb.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebStorage
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.videograb.app.browser.AdBillboard
import com.videograb.app.browser.BrowserTab
import com.videograb.app.browser.WebViewHealth
import com.videograb.app.browser.TabListener
import com.videograb.app.browser.TabManager
import com.videograb.app.ui.AdsScreen
import com.videograb.app.ui.BrowserScreen
import com.videograb.app.ui.DownloadsScreen
import com.videograb.app.ui.PickRequest
import com.videograb.app.ui.PickerController
import com.videograb.app.ui.PickerSheet
import com.videograb.app.ui.SettingsScreen
import com.videograb.app.ui.VideoGrabTheme
import com.videograb.app.util.UrlUtils
import com.videograb.app.util.VersionUtils
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), TabListener {
    lateinit var tabs: TabManager
    lateinit var picker: PickerController
    private var screen by mutableIntStateOf(0)
    private var fullscreenView by mutableStateOf<View?>(null)
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    /** Last caught popup that the user may still want to open. */
    var blockedPopup by mutableStateOf<String?>(null)
    var popupToBillboard by mutableStateOf(false)
    var blockedCount by mutableIntStateOf(0)
    /** Light, Dark or System; the screens redraw when it changes. */
    var themeMode by mutableStateOf(Prefs.themeMode)
    /** (tab, http url) waiting for "open insecure site?" answer */
    private var insecurePrompt by mutableStateOf<Pair<BrowserTab, String>?>(null)

    /** Set when the phone's web engine is out of date (warned at most once a day). */
    private var webViewWarning by mutableStateOf<WebViewHealth.Status?>(null)
    /** Set when a newer Speed Force APK has been downloaded from GitHub. */
    private var appUpdateReady by mutableStateOf<com.videograb.app.browser.AppUpdate.Ready?>(null)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    // ---- search by picture (Google Lens) ----
    private var scanFile: java.io.File? = null
    // The phone's own gallery picker (photos and screenshots); it needs no storage permission
    private val photoPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) searchByImage(uri, null) }
    private val cameraShot = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = scanFile
        if (ok && f != null) searchByImage(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f), f)
        else f?.delete()
    }

    fun pickPhotoForSearch() =
        photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))

    fun scanWithCamera() {
        val dir = java.io.File(cacheDir, "scan").apply { mkdirs() }
        val f = java.io.File(dir, "scan.jpg").also { scanFile = it }
        cameraShot.launch(androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f))
    }

    /** Shrink the picture, then open Google Lens' results in a new tab; tapping a result opens it there. */
    private fun searchByImage(uri: android.net.Uri, temp: java.io.File?) {
        lifecycleScope.launch {
            val html = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.videograb.app.browser.ImageSearch.pageFor(contentResolver, uri).also { temp?.delete() }
            }
            if (html == null) {
                Toast.makeText(this@MainActivity, "Couldn't read that picture", Toast.LENGTH_LONG).show()
                return@launch
            }
            screen = 0
            Toast.makeText(this@MainActivity, "Sending the picture to Google Lens…", Toast.LENGTH_SHORT).show()
            tabs.newTab("https://lens.google.com/").loadHtml("https://lens.google.com/", html)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Web pages go through the app's relay too (before the first tab loads)
        com.videograb.app.net.SecureDns.apply(web = true)
        if (Prefs.privateMode) clearBrowsingData(keepHistory = true)
        applyPrivacyFlags()
        // Remote inspection of pages only in test builds, never in the build you install
        if (BuildConfigHelper.isDebuggable(this)) WebView.setWebContentsDebuggingEnabled(true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.START_SAFE_BROWSING)) {
            WebViewCompat.startSafeBrowsing(applicationContext) { ok -> Log.i("VideoGrab", "Safe Browsing ready: $ok") }
        }
        tabs = TabManager(this, this)
        picker = PickerController(applicationContext, lifecycleScope)

        val startUrl = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        // Reopen the tabs from last time (like a normal browser); a link you opened the app with goes on top
        val (saved, front) = Prefs.savedTabs()
        if (saved.isNotEmpty()) tabs.restore(saved, front)
        com.videograb.app.browser.CookieGuard.atStart(saved + listOfNotNull(startUrl))
        if (startUrl != null || saved.isEmpty()) tabs.newTab(startUrl ?: Prefs.homePage)
        handleShare(intent)
        askPermissions()
        lifecycleScope.launch {
            val s = WebViewHealth.check(applicationContext)
            val now = System.currentTimeMillis()
            if (s.outdated && now - Prefs.webViewWarnedAt > 24L * 3600 * 1000) {
                Prefs.webViewWarnedAt = now
                webViewWarning = s
            }
        }
        lifecycleScope.launch {
            appUpdateReady = com.videograb.app.browser.AppUpdate.checkAndDownload(applicationContext)
        }

        setContent {
            VideoGrabTheme(themeMode) {
                val fs = fullscreenView
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        if (screen != 0) {
                            BackHandler { screen = 0 }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconButton({ screen = 0 }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to browser") }
                                Text(
                                    when (screen) { 1 -> "Downloads"; 2 -> "Shields log"; else -> "Settings" },
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                0 -> BrowserScreen(this@MainActivity)
                                1 -> DownloadsScreen(this@MainActivity)
                                2 -> AdsScreen(this@MainActivity)
                                else -> SettingsScreen(this@MainActivity)
                            }
                        }
                    }
                    if (fs != null) {
                        key(fs) { AndroidView(factory = { fs }, modifier = Modifier.fillMaxSize().background(Color.Black)) }
                        BackHandler { onHideFullscreen() }
                    }
                    PickerSheet(picker)
                    webViewWarning?.let { s ->
                        AlertDialog(
                            onDismissRequest = { webViewWarning = null },
                            title = { Text("Update your web engine") },
                            text = {
                                Text(
                                    "${s.appName} on this phone is version ${VersionUtils.major(s.installed)}, but Google is " +
                                        "already giving most phones version ${VersionUtils.major(s.latest)} (${s.behind} versions newer).\n\n" +
                                        "Every page in Speed Force runs on it, and its security fixes only arrive through the " +
                                        "Play Store. Updating takes a minute and keeps you protected."
                                )
                            },
                            confirmButton = {
                                TextButton({ webViewWarning = null; WebViewHealth.openUpdatePage(this@MainActivity, s.packageName) }) {
                                    Text("Update now")
                                }
                            },
                            dismissButton = { TextButton({ webViewWarning = null }) { Text("Later") } },
                        )
                    }
                    appUpdateReady?.let { upd ->
                        AlertDialog(
                            onDismissRequest = { appUpdateReady = null },
                            title = { Text("Update ready") },
                            text = { Text("Speed Force ${upd.version} has been downloaded from GitHub. Install it now to update?") },
                            confirmButton = {
                                TextButton({
                                    appUpdateReady = null
                                    com.videograb.app.browser.AppUpdate.install(this@MainActivity, upd.apk)
                                }) { Text("Install") }
                            },
                            dismissButton = { TextButton({ appUpdateReady = null }) { Text("Later") } },
                        )
                    }
                    insecurePrompt?.let { (tab, httpUrl) ->
                        AlertDialog(
                            onDismissRequest = { insecurePrompt = null },
                            title = { Text("Site isn't secure") },
                            text = {
                                Text(
                                    "${UrlUtils.host(httpUrl)} has no secure (https) version. On an insecure connection, " +
                                        "anyone on the same Wi-Fi could see or change what you browse there.\n\nOpen it anyway?"
                                )
                            },
                            confirmButton = { TextButton({ insecurePrompt = null; tab.allowHttpAndLoad(httpUrl) }) { Text("Open anyway") } },
                            dismissButton = { TextButton({ insecurePrompt = null }) { Text("Go back") } },
                        )
                    }
                }
                }
            }
        }
    }

    fun openScreen(index: Int) {
        screen = index
    }

    private fun askPermissions() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= 28) wanted.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val missing = wanted.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_VIEW && intent.dataString != null) {
            screen = 0
            tabs.newTab(intent.dataString!!)
        }
        handleShare(intent)
    }

    /** A picture shared from another app goes to Google Lens; a shared link goes to the quality picker. */
    private fun handleShare(intent: Intent?) {
        if (intent == null) return
        intent.getIntExtra(EXTRA_TAB, -1).takeIf { it >= 0 }?.let { screen = it }
        if (intent.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val picture = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
            // Only pictures the other app hands over: never a file path or something from this app's own storage,
            // or another app could make us upload our private files
            if (picture != null && picture.scheme == "content" && picture.authority != "$packageName.files") searchByImage(picture, null)
            else Toast.makeText(this, "Couldn't read that picture", Toast.LENGTH_LONG).show()
            return
        }
        val url = UrlUtils.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            Toast.makeText(this, "No web link found in what was shared", Toast.LENGTH_LONG).show()
            return
        }
        screen = 1
        picker.open(PickRequest(pageUrl = url))
    }

    // ---- TabListener ----
    override fun onVideoTapped(tab: BrowserTab, frameUrl: String, src: String, title: String, durationSec: Int, p: Int) {
        tab.wakePlayers()
        picker.open(
            PickRequest(
                pageUrl = tab.url,
                frameUrl = frameUrl.takeIf { it != tab.url },
                directUrl = src.takeIf { UrlUtils.isHttp(it) },
                title = title,
                streams = tab.streams.toList(),
                durationHint = durationSec,
                heightHint = p,
                liveStreams = { tab.streams.toList() },
            )
        )
    }

    fun openPickerForCurrentPage() {
        val tab = tabs.current
        tab.wakePlayers()
        // Ask the page which video it is showing: reading that one directly is much faster than yt-dlp
        tab.webView.evaluateJavascript(MAIN_VIDEO_JS) { answer ->
            val o = runCatching { org.json.JSONObject(org.json.JSONTokener(answer).nextValue() as String) }.getOrNull()
            val dur = o?.optDouble("d", 0.0)?.takeIf { !it.isNaN() && !it.isInfinite() }?.toInt() ?: 0
            picker.open(
                PickRequest(
                    pageUrl = tab.url, directUrl = o?.optString("s")?.takeIf { UrlUtils.isHttp(it) }, title = tab.title,
                    streams = tab.streams.toList(), durationHint = dur, heightHint = o?.optInt("p") ?: 0,
                    liveStreams = { tab.streams.toList() },
                )
            )
        }
    }

    override fun onPopup(tab: BrowserTab, url: String?, toBillboard: Boolean) {
        blockedCount++
        if (url != null) {
            blockedPopup = url
            popupToBillboard = toBillboard
        }
    }

    override fun newPopupTab(): BrowserTab = tabs.popupTab()

    override fun showPopupTab(tab: BrowserTab) {
        screen = 0
        tabs.show(tab)
    }

    override fun openInNewTab(url: String) {
        tabs.newTab(url)
    }

    /** A site started a file download. Only video/audio is accepted; apps (APK) and other files are refused. */
    override fun onFileDownload(tab: BrowserTab, url: String, mimeType: String?) {
        val ext = url.substringBefore('?').substringBefore('#').substringAfterLast('/').substringAfterLast('.', "")
        val mime = mimeType?.lowercase() ?: ""
        val isMedia = mime.startsWith("video/") || mime.startsWith("audio/") || UrlUtils.isMediaExtension(ext) ||
            mime.contains("mpegurl") || mime.contains("dash+xml")
        if (!isMedia) {
            // Any other file: downloaded as it is, to the phone's Downloads folder
            if (!UrlUtils.isHttp(url)) {
                Toast.makeText(this, "This kind of link (${url.substringBefore(':')}:) can't be downloaded here", Toast.LENGTH_LONG).show()
                return
            }
            val ctx = com.videograb.app.engine.RequestContext.capture(applicationContext, listOf(url, tab.url), tab.url.takeIf { UrlUtils.isHttp(it) })
            com.videograb.app.download.DownloadManager.enqueueDirect(url, ctx)
            Toast.makeText(this, "Downloading file… see Downloads", Toast.LENGTH_SHORT).show()
            return
        }
        picker.open(PickRequest(pageUrl = url, streams = tab.streams.toList()))
    }

    override fun onInsecureFallback(tab: BrowserTab, httpUrl: String) {
        insecurePrompt = tab to httpUrl
    }

    override fun onCertificateError(tab: BrowserTab, url: String) {
        Toast.makeText(this, "Blocked: ${UrlUtils.host(url)} has an invalid security certificate (possible attack or broken site).", Toast.LENGTH_LONG).show()
    }

    override fun onTabCrashed(tab: BrowserTab) {
        val wasFront = tabs.current === tab
        val reloaded = tabs.replaceCrashed(tab, Prefs.homePage)
        if (!wasFront) return
        Toast.makeText(
            this,
            if (reloaded) "The page ran out of memory and was reloaded." else "This page keeps running out of memory, so the home page was opened instead.",
            Toast.LENGTH_LONG,
        ).show()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) AdBillboard.dropLive()
    }

    /** Private mode: hide the app from screenshots, screen recording and the recent-apps preview. */
    fun applyPrivacyFlags() {
        if (Prefs.privateMode) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Push changed settings (ad mode, cookies) into the open tabs. */
    fun applySettingsToTabs() {
        tabs.tabs.forEach { it.applySettings() }
    }

    /** Current ad mode (HIDE / BLOCK / OFF), observable by the screens. */
    var adMode by mutableStateOf(Prefs.adMode)
        private set

    /** Quick switch from the browser menu / Ads screen: applies and reloads the page you're on. */
    fun changeAdMode(mode: String) {
        Prefs.adMode = mode
        adMode = mode
        applySettingsToTabs()
        tabs.current.webView.reload()
        val text = when (mode) {
            "BLOCK" -> "Block mode: ads won't load at all"
            "OFF" -> "Ad filtering off"
            else -> "Hide mode: ads load invisibly, popups go to Ads"
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        fullscreenView = view
        fullscreenCallback = callback
    }

    override fun onHideFullscreen() {
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }

    fun clearBrowsingData(keepHistory: Boolean) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        if (::tabs.isInitialized) tabs.tabs.forEach { it.webView.clearCache(true); it.webView.clearHistory() }
        else WebView(this).apply { clearCache(true); destroy() }
        if (!keepHistory) Prefs.clearHistory()
    }

    override fun onStop() {
        super.onStop()
        // Remember the open tabs; the app may be closed or swiped away from here on
        if (::tabs.isInitialized) Prefs.saveTabs(tabs.urls(), tabs.currentIndex)
    }

    override fun onDestroy() {
        if (isFinishing && Prefs.privateMode) {
            clearBrowsingData(keepHistory = true)
            AdBillboard.clear()
        }
        tabs.destroyAll()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_TAB = "tab"

        /** The biggest video on the page, as text; "null" when there is none. */
        private const val MAIN_VIDEO_JS = """(function(){var b=null,a=0,v=document.getElementsByTagName('video');
for(var i=0;i<v.length;i++){var s=v[i].offsetWidth*v[i].offsetHeight;if(s>a&&!v[i].closest('[data-vgad]')){a=s;b=v[i]}}
return b?JSON.stringify({s:b.currentSrc||b.src||'',d:b.duration||0,p:Math.min(b.videoWidth,b.videoHeight)||0}):null})()"""
    }
}

object BuildConfigHelper {
    fun isDebuggable(a: android.content.Context) =
        (a.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
