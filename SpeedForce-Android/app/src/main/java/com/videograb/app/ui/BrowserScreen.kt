package com.videograb.app.ui

import android.content.Intent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.videograb.app.MainActivity
import com.videograb.app.Prefs
import com.videograb.app.browser.BrowserTab
import com.videograb.app.util.UrlUtils
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(activity: MainActivity) {
    val tabs = activity.tabs
    val tab = tabs.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val addressFocus = remember { FocusRequester() }
    var field by remember(tab.id) { mutableStateOf(TextFieldValue("")) }
    var editing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showShields by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showFind by remember { mutableStateOf(false) }
    var privateMode by remember { mutableStateOf(Prefs.privateMode) }
    var shieldsVersion by remember { mutableIntStateOf(0) }
    var bookmarkVersion by remember { mutableIntStateOf(0) }

    BackHandler(enabled = tab.canGoBack) { tab.goBack() }

    val host = if (tab.isStart) "" else UrlUtils.host(tab.url)?.removePrefix("www.") ?: ""
    val shieldsDown = remember(tab.url, shieldsVersion) { Prefs.shieldsDown(host) }
    val bookmarked = remember(tab.url, bookmarkVersion) { Prefs.isBookmarked(tab.url) }

    Column(Modifier.fillMaxSize()) {
        // ---- address bar: shield, address, reload ----
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).height(46.dp),
            shape = RoundedCornerShape(23.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(46.dp).clickable { showShields = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(ShieldIcon, "Shields", tint = if (shieldsDown) Color.Gray else Accent, modifier = Modifier.size(22.dp))
                    if (!shieldsDown && tab.blocked > 0 && !tab.isStart) {
                        Surface(
                            Modifier.align(Alignment.TopEnd).padding(top = 5.dp, end = 3.dp),
                            shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary,
                        ) {
                            Text(
                                if (tab.blocked > 99) "99+" else "${tab.blocked}", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                if (privateMode) Icon(Icons.Filled.Lock, "Private mode", tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                if (tab.insecure && !editing) Icon(Icons.Filled.Warning, "Not secure (http)", tint = Color(0xFFFFB300), modifier = Modifier.size(16.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = if (editing) field else TextFieldValue(if (tab.isStart) "" else host.ifEmpty { tab.url }),
                    onValueChange = { field = it },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp).focusRequester(addressFocus).onFocusChanged {
                        if (it.isFocused && !editing) {
                            val u = if (tab.isStart) "" else tab.url
                            field = TextFieldValue(u, TextRange(0, u.length))
                        }
                        editing = it.isFocused
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        val target = UrlUtils.toUrlOrSearch(field.text, UrlUtils.engines.getValue(Prefs.searchEngine))
                        if (target.isNotEmpty()) tab.load(target)
                        keyboard?.hide()
                        focus.clearFocus()
                    }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            val shown = if (editing) field.text else if (tab.isStart) "" else host.ifEmpty { tab.url }
                            if (shown.isEmpty()) Text("Search or type web address", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            inner()
                        }
                    },
                )
                if (editing && field.text.isNotEmpty()) {
                    IconButton({ field = TextFieldValue("") }, Modifier.size(42.dp)) { Icon(Icons.Filled.Close, "Clear") }
                } else if (!tab.isStart) {
                    IconButton({ if (tab.progress < 100) tab.webView.stopLoading() else tab.webView.reload() }, Modifier.size(42.dp)) {
                        if (tab.progress < 100) Icon(Icons.Filled.Close, "Stop") else Icon(Icons.Filled.Refresh, "Refresh")
                    }
                } else {
                    // Empty address bar: search a photo or screenshot with Google Lens
                    Surface(
                        Modifier.padding(end = 6.dp).clickable { activity.pickPhotoForSearch() },
                        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                    ) { Text("Lens", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) }
                }
            }
        }
        if (tab.progress in 0..99 && !tab.isStart) {
            LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), color = MaterialTheme.colorScheme.primary)
        }
        // On a search results page: the same search with another engine, one tap
        UrlUtils.searchQuery(tab.url)?.let { q ->
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Search with", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                UrlUtils.engines.forEach { (name, base) ->
                    if (!tab.url.startsWith(base.substringBefore('?'))) {
                        AssistChip({ tab.load(base + java.net.URLEncoder.encode(q, "UTF-8")) }, { Text(name) })
                    }
                }
            }
        }

        // ---- page ----
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // The start page is drawn by the app alone: a web view with nothing loaded is not shown
            if (!tab.isStart) key(tab.id) {
                AndroidView(
                    factory = {
                        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                        tab.webView
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (tab.isStart) {
                StartPage(
                    onSearchTap = { addressFocus.requestFocus(); keyboard?.show() },
                    onOpen = { tab.load(it) },
                )
            }
            val popup = activity.blockedPopup
            if (popup != null) {
                LaunchedEffect(popup) { delay(6000); if (activity.blockedPopup == popup) activity.blockedPopup = null }
                Surface(
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth(0.75f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (activity.popupToBillboard) "Popup sent to Shields log" else "Popup blocked",
                            Modifier.weight(1f), fontSize = 13.sp,
                        )
                        TextButton({ activity.blockedPopup = null; tabs.newTab(popup) }) { Text("Open in tab") }
                    }
                }
            }
        }

        // ---- find in page ----
        if (showFind) FindBar(tab) { showFind = false }

        // ---- bottom toolbar (like Brave): back, forward, new tab, tabs, menu ----
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).height(52.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton({ tab.goBack() }, enabled = tab.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton({ tab.webView.goForward() }, enabled = tab.canGoForward && !tab.isStart) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            IconButton({ tabs.newTab(Prefs.homePage); focus.clearFocus() }) { Icon(Icons.Filled.Add, "New tab") }
            IconButton({ showTabs = true }) { TabCountBox(tabs.tabs.size) }
            IconButton({ showMenu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
        }
    }

    // ---------------- menu ----------------
    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(Icons.Filled.Refresh, "Reload") { showMenu = false; tab.webView.reload() }
                    QuickAction(Icons.Filled.Star, if (bookmarked) "Bookmarked" else "Bookmark", highlight = bookmarked) {
                        if (!tab.isStart && tab.url.startsWith("http")) {
                            val added = Prefs.toggleBookmark(tab.title, tab.url)
                            bookmarkVersion++
                            Toast.makeText(activity, if (added) "Bookmark added" else "Bookmark removed", Toast.LENGTH_SHORT).show()
                        }
                        showMenu = false
                    }
                    QuickAction(Icons.Filled.Share, "Share") {
                        showMenu = false
                        if (tab.url.startsWith("http")) {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url)
                            activity.startActivity(Intent.createChooser(send, "Share page"))
                        }
                    }
                    QuickAction(Icons.Filled.Search, "Find") { showMenu = false; showFind = true }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                MenuRow(Icons.Filled.Add, "New tab", { showMenu = false; tabs.newTab(Prefs.homePage) })
                MenuRow(
                    Icons.Filled.Lock, "Private mode",
                    { privateMode = !privateMode; Prefs.privateMode = privateMode; activity.applyPrivacyFlags() },
                    hint = "No history, cookies wiped on exit, no screenshots",
                    trailing = { androidx.compose.material3.Switch(privateMode, { privateMode = it; Prefs.privateMode = it; activity.applyPrivacyFlags() }) },
                )
                MenuRow(Icons.AutoMirrored.Filled.List, "Downloads", { showMenu = false; activity.openScreen(1) })
                MenuRow(Icons.Filled.Star, "Bookmarks", { showMenu = false; showBookmarks = true })
                MenuRow(Icons.Filled.Info, "History", { showMenu = false; showHistory = true })
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                MenuRow(
                    Icons.Filled.Home, "Desktop site", { tab.setDesktopSite(!tab.desktop); showMenu = false },
                    trailing = { androidx.compose.material3.Switch(tab.desktop, { tab.setDesktopSite(it); showMenu = false }) },
                )
                MenuRow(Icons.Filled.Search, "Google Lens: photo or screenshot", { showMenu = false; activity.pickPhotoForSearch() },
                    hint = "Pick one from your phone: results open in a tab")
                MenuRow(Icons.Filled.Search, "Google Lens: use the camera", { showMenu = false; activity.scanWithCamera() })
                MenuRow(Icons.Filled.Search, "Find videos on this page", { showMenu = false; activity.openPickerForCurrentPage() })
                MenuRow(Icons.Filled.Info, "Why won't this site load?", {
                    showMenu = false
                    if (tab.url.startsWith("http") && !tab.url.startsWith(com.videograb.app.browser.Diagnostic.BASE)) tab.showDiagnostic(tab.url, "", tab.lastStatus)
                    else Toast.makeText(activity, "Open the page that does not load, then use this", Toast.LENGTH_LONG).show()
                })
                MenuRow(Icons.Filled.Notifications, "Shields log", { showMenu = false; activity.openScreen(2) },
                    hint = if (activity.blockedCount > 0) "Popups caught: ${activity.blockedCount}" else null)
                MenuRow(Icons.Filled.Settings, "Settings", { showMenu = false; activity.openScreen(3) })
            }
        }
    }

    // ---------------- shields ----------------
    if (showShields) {
        ModalBottomSheet(onDismissRequest = { showShields = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(ShieldIcon, null, tint = if (shieldsDown) Color.Gray else Accent, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (host.isEmpty()) "Speed Force Shields" else host, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (host.isEmpty()) "Open a site to see what was stopped" else if (shieldsDown) "Shields are DOWN for this site" else "Shields are UP for this site",
                            fontSize = 13.sp, color = if (shieldsDown) Color(0xFFFFB300) else ShieldGreen,
                        )
                    }
                    if (host.isNotEmpty()) androidx.compose.material3.Switch(!shieldsDown, {
                        showShields = false
                        tab.setShieldsDown(!it)
                        shieldsVersion++
                    })
                }
                if (host.isNotEmpty() && !shieldsDown) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ShieldCount(Modifier.weight(1f), tab.blocked, "Trackers & ads blocked")
                        ShieldCount(Modifier.weight(1f), tab.hiddenAds, "Ads hidden")
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                Text("Trackers & ads blocking", Modifier.padding(start = 20.dp, top = 14.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf(
                    "HIDE" to ("Standard" to "Ads load invisibly, sites can't tell. Popups go to the Shields log."),
                    "BLOCK" to ("Aggressive" to "Ads never load (saves data, a few sites complain)."),
                    "OFF" to ("Disabled" to "No filtering anywhere."),
                ).forEach { (mode, texts) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { showShields = false; activity.changeAdMode(mode) }.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(activity.adMode == mode, { showShields = false; activity.changeAdMode(mode) })
                        Column {
                            Text(texts.first, fontSize = 15.sp)
                            Text(texts.second, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                var https by remember { mutableStateOf(Prefs.httpsOnly) }
                var cookies by remember { mutableStateOf(!Prefs.thirdPartyCookies) }
                var dns by remember { mutableStateOf(Prefs.secureDns) }
                SwitchLine("Upgrade connections to HTTPS", "Opens https:// even when a link says http://", https) { https = it; Prefs.httpsOnly = it }
                SwitchLine("Block cross-site cookies", "Stops ad networks following you from site to site", cookies) {
                    cookies = it; Prefs.thirdPartyCookies = !it; activity.applySettingsToTabs()
                }
                var reject by remember { mutableStateOf(Prefs.rejectCookies) }
                var guard by remember { mutableStateOf(Prefs.cookieGuard) }
                var trust by remember(host) { mutableStateOf(host.isNotEmpty() && host in Prefs.trustedSites()) }
                SwitchLine("Reject cookie banners", "Presses Reject for you. Never presses Accept", reject) {
                    reject = it; Prefs.rejectCookies = it; activity.applySettingsToTabs()
                }
                SwitchLine("Cookie guard", "Deletes a site's cookies when you close it. You are signed out there", guard) {
                    guard = it; Prefs.cookieGuard = it
                    if (it) tab.visited.forEach { s -> com.videograb.app.browser.CookieGuard.note(s) }
                }
                if (guard && host.isNotEmpty()) {
                    SwitchLine("Trust this site", "Keep me signed in here", trust) { trust = it; Prefs.setTrusted(host, it) }
                }
                SwitchLine("Encrypted DNS (Cloudflare)", "Opens sites your provider blocks", dns) {
                    dns = it; Prefs.secureDns = it; com.videograb.app.net.SecureDns.apply(web = true)
                }
                if (host.isNotEmpty() && !shieldsDown) {
                    MenuRow(null, "Show hidden ads on this page", { showShields = false; tab.revealAds() }, hint = "Until you reload the page")
                }
            }
        }
    }

    // ---------------- tabs ----------------
    if (showTabs) {
        androidx.compose.runtime.LaunchedEffect(Unit) { tab.capture() }
        Dialog({ showTabs = false }, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${tabs.tabs.size} tab${if (tabs.tabs.size == 1) "" else "s"}", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        if (privateMode) Text("Private mode on", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(end = 8.dp))
                        TextButton({ showTabs = false }) { Text("Done") }
                    }
                    LazyVerticalGrid(
                        GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(tabs.tabs, key = { _, t -> t.id }) { i, t ->
                            val here = i == tabs.currentIndex
                            val h = if (t.isStart) "" else UrlUtils.host(t.url)?.removePrefix("www.") ?: ""
                            Surface(
                                Modifier.fillMaxWidth().height(230.dp).clickable { tabs.select(i); showTabs = false },
                                shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer,
                                border = BorderStroke(if (here) 2.dp else 1.dp, if (here) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                            ) {
                                Column {
                                    Row(Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        SiteAvatar(h.ifEmpty { "Speed Force" }, 22.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            if (t.isStart) "New tab" else t.title, Modifier.weight(1f), maxLines = 1,
                                            overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                        )
                                        IconButton({ tabs.close(i, Prefs.homePage) }, Modifier.size(34.dp)) { Icon(Icons.Filled.Close, "Close tab", Modifier.size(18.dp)) }
                                    }
                                    val pic = t.thumb
                                    Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                                        if (pic != null && !t.isStart) {
                                            androidx.compose.foundation.Image(
                                                pic, t.title, Modifier.fillMaxSize(),
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, alignment = Alignment.TopCenter,
                                            )
                                        } else {
                                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                                if (t.isStart) androidx.compose.foundation.Image(
                                                    androidx.compose.ui.res.painterResource(com.videograb.app.R.drawable.speedforce_logo), null,
                                                    Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape),
                                                ) else SiteAvatar(h.ifEmpty { "?" }, 56.dp)
                                                Spacer(Modifier.height(6.dp))
                                                Text(h.ifEmpty { "Start page" }, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.Center) {
                        ExtendedFloatingActionButton(
                            onClick = { tabs.newTab(Prefs.homePage); showTabs = false },
                            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); Text("New tab") }
                    }
                }
            }
        }
    }

    // ---------------- bookmarks ----------------
    if (showBookmarks) {
        var items by remember { mutableStateOf(Prefs.bookmarks()) }
        AlertDialog(
            onDismissRequest = { showBookmarks = false },
            title = { Text("Bookmarks") },
            text = {
                if (items.isEmpty()) Text("No bookmarks yet. Open a page and use ⋮ → Bookmark.")
                LazyColumn {
                    itemsIndexed(items) { _, (title, url) ->
                        Row(Modifier.fillMaxWidth().clickable { tab.load(url); showBookmarks = false }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton({ Prefs.toggleBookmark(title, url); items = Prefs.bookmarks(); bookmarkVersion++ }) { Icon(Icons.Filled.Delete, "Remove") }
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton({ showBookmarks = false }) { Text("Close") } },
        )
    }

    // ---------------- history ----------------
    if (showHistory) {
        var items by remember { mutableStateOf(Prefs.history()) }
        AlertDialog(
            onDismissRequest = { showHistory = false },
            title = { Text("History") },
            text = {
                if (items.isEmpty()) Text(if (Prefs.privateMode) "Private mode is on: history is not saved." else "No history yet.")
                LazyColumn {
                    itemsIndexed(items) { _, (title, url) ->
                        Column(Modifier.fillMaxWidth().clickable { tab.load(url); showHistory = false }.padding(vertical = 6.dp)) {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(url, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton({ showHistory = false }) { Text("Close") } },
            dismissButton = { TextButton({ Prefs.clearHistory(); items = emptyList() }) { Text("Clear history") } },
        )
    }
}

@Composable
private fun QuickAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, highlight: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShieldCount(modifier: Modifier, n: Int, label: String) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(14.dp)) {
            Text("$n", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Find in page: type a word, step through the matches. */
@Composable
private fun FindBar(tab: BrowserTab, onClose: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var total by remember { mutableIntStateOf(0) }
    var index by remember { mutableIntStateOf(0) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    androidx.compose.runtime.DisposableEffect(tab.id) {
        tab.webView.setFindListener { active, count, _ -> index = active; total = count }
        onDispose { tab.webView.clearMatches(); tab.webView.setFindListener(null) }
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            q, { q = it; if (it.isEmpty()) tab.webView.clearMatches() else tab.webView.findAllAsync(it) },
            Modifier.weight(1f).focusRequester(fr), singleLine = true, placeholder = { Text("Find in page") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { tab.webView.findNext(true) }),
        )
        Text(if (q.isEmpty()) "" else if (total == 0) "0" else "${index + 1}/$total", Modifier.padding(horizontal = 8.dp), fontSize = 13.sp)
        IconButton({ tab.webView.findNext(false) }) { Icon(Icons.Filled.KeyboardArrowUp, "Previous") }
        IconButton({ tab.webView.findNext(true) }) { Icon(Icons.Filled.KeyboardArrowDown, "Next") }
        IconButton(onClose) { Icon(Icons.Filled.Close, "Close") }
    }
}
