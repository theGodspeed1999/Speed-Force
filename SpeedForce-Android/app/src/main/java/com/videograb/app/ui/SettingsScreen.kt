package com.videograb.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videograb.app.MainActivity
import com.videograb.app.Prefs
import com.videograb.app.util.UrlUtils
import com.videograb.app.browser.WebViewHealth
import com.videograb.app.util.VersionUtils
import com.videograb.app.download.DownloadManager
import com.videograb.app.engine.Engine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(activity: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var folder by remember { mutableStateOf(Prefs.folderName) }
    var home by remember { mutableStateOf(Prefs.homePage) }
    var maxRes by remember { mutableIntStateOf(Prefs.maxResolution) }
    var parallel by remember { mutableIntStateOf(Prefs.maxParallel) }
    var httpsOnly by remember { mutableStateOf(Prefs.httpsOnly) }
    var secureDns by remember { mutableStateOf(Prefs.secureDns) }
    var thirdParty by remember { mutableStateOf(Prefs.thirdPartyCookies) }
    var privateMode by remember { mutableStateOf(Prefs.privateMode) }
    var restoreTabs by remember { mutableStateOf(Prefs.restoreTabs) }
    var engine by remember { mutableStateOf(Prefs.searchEngine) }
    var version by remember { mutableStateOf("…") }
    var updating by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        version = withContext(Dispatchers.IO) {
            if (Engine.awaitReady()) Engine.version(ctx) else "not started: ${Engine.initError}"
        }
    }

    var darkPages by remember { mutableStateOf(Prefs.darkPages) }
    var theme by remember { mutableStateOf(Prefs.themeMode) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Appearance") {
            Text("Theme", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark").forEach { (mode, label) ->
                    FilterChip(theme == mode, { theme = mode; Prefs.themeMode = mode; activity.themeMode = mode }, label = { Text(label) })
                }
            }
            SwitchRow("Dark mode for web pages (makes light sites dark)", darkPages) {
                darkPages = it; Prefs.darkPages = it; activity.applySettingsToTabs(); activity.tabs.current.webView.reload()
            }
        }
        Section("Download engine (yt-dlp)") {
            Text("Version: $version", fontSize = 13.sp)
            Text("The app updates the engine by itself every 3 days. If a site stops working, tap Update engine to get the newest version right now.", fontSize = 12.sp, color = Color.Gray)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        updating = true; updateResult = ""
                        scope.launch {
                            updateResult = Engine.update(ctx)
                            if (!updateResult.startsWith("Update failed")) Prefs.lastEngineUpdate = System.currentTimeMillis()
                            version = withContext(Dispatchers.IO) { Engine.version(ctx) }
                            updating = false
                        }
                    },
                    enabled = !updating,
                ) { Text("Update engine") }
                if (updating) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(24.dp))
            }
            if (updateResult.isNotEmpty()) Text(updateResult, fontSize = 13.sp)
        }

        Section("Saving") {
            OutlinedTextField(folder, { folder = it }, label = { Text("Folder name (inside Movies)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton({ Prefs.folderName = folder; folder = Prefs.folderName; Toast.makeText(ctx, "Saved: Movies/${Prefs.folderName}", Toast.LENGTH_SHORT).show() }) { Text("Save folder name") }
            Text("Default quality (highlighted in the list; used by \"Best\")", fontSize = 13.sp)
            val choices = listOf(0 to "Best available", 2160 to "4K (2160p)", 1440 to "1440p", 1080 to "1080p", 720 to "720p", 480 to "480p", 360 to "360p")
            choices.forEach { (v, label) ->
                Row(Modifier.fillMaxWidth().clickable { maxRes = v; Prefs.maxResolution = v }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(maxRes == v, { maxRes = v; Prefs.maxResolution = v })
                    Text(label)
                }
            }
            Text("Downloads at the same time", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..3).forEach { n ->
                    FilterChip(parallel == n, { parallel = n; Prefs.maxParallel = n; DownloadManager.pump() }, label = { Text("$n") })
                }
            }
        }

        Section("Web engine (security updates)") {
            var health by remember { mutableStateOf<WebViewHealth.Status?>(null) }
            var checking by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { health = WebViewHealth.check(ctx) }
            val h = health
            if (h == null) {
                Text("Checking…", fontSize = 13.sp)
            } else {
                Text("${h.appName}: version ${h.installed}", fontSize = 13.sp)
                Text("Version Google is sending to phones now: ${h.latest ?: "unknown (offline)"}", fontSize = 13.sp)
                val started = WebViewHealth.newestStarted
                if (started.isNotEmpty() && VersionUtils.major(started) > VersionUtils.major(h.latest)) {
                    Text(
                        "Version ${VersionUtils.major(started)} has started reaching the first phones; the Play Store offers it " +
                            "to yours when its turn comes (Google sends new versions out over a few weeks).",
                        fontSize = 12.sp, color = Color.Gray,
                    )
                }
                val (msg, color) = when {
                    h.latest == null -> "Couldn't check right now." to Color.Gray
                    h.outdated -> "⚠ Out of date by ${h.behind} versions — you're missing security fixes. Update it now." to Color(0xFFFFB300)
                    else -> "✓ Up to date" to Color(0xFF66BB6A)
                }
                Text(msg, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold)
                Text(
                    "Every web page in Speed Force runs on this engine, and Google ships its security fixes through the Play Store. " +
                        "Keep it updated (and allow Play Store auto-updates).",
                    fontSize = 12.sp, color = Color.Gray,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button({ WebViewHealth.openUpdatePage(ctx, h.packageName) }) { Text("Update in Play Store") }
                    OutlinedButton(
                        { checking = true; scope.launch { health = WebViewHealth.check(ctx, force = true); checking = false } },
                        enabled = !checking,
                    ) { Text("Check again") }
                }
            }
        }

        Section("Ads and popups") {
            val modes = listOf(
                "HIDE" to "Hide (recommended): ads load invisibly so sites can't tell; popups go to the Ads screen",
                "BLOCK" to "Block: ads never load (saves data, but some sites detect it and complain)",
                "OFF" to "Off: show everything like a normal browser",
            )
            modes.forEach { (value, label) ->
                Row(
                    Modifier.fillMaxWidth().clickable { activity.changeAdMode(value) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(activity.adMode == value, { activity.changeAdMode(value) })
                    Text(label, fontSize = 13.sp)
                }
            }
            Text("Trackers, crypto-miners and push-spam networks are always blocked (unless Off). A change applies fully after you reload the page.", fontSize = 12.sp, color = Color.Gray)
        }

        Section("Security and privacy") {
            SwitchRow("Built-in secure DNS (Cloudflare): opens sites your internet provider blocks", secureDns) {
                secureDns = it; Prefs.secureDns = it; com.videograb.app.net.SecureDns.apply(web = true)
            }
            SwitchRow("HTTPS-only: always use secure connections (asks before opening an insecure site)", httpsOnly) {
                httpsOnly = it; Prefs.httpsOnly = it
            }
            SwitchRow("Allow third-party cookies (turn on only if a video player refuses to load)", thirdParty) {
                thirdParty = it; Prefs.thirdPartyCookies = it; activity.applySettingsToTabs()
            }
            SwitchRow("Private mode (no history; cookies & cache wiped when you exit; screenshots blocked)", privateMode) {
                privateMode = it; Prefs.privateMode = it; activity.applyPrivacyFlags()
                if (it) Prefs.clearSavedTabs()
            }
            Text(
                "Always on: Google Safe Browsing (phishing & malware warnings) · bad certificates refused · " +
                    "insecure content blocked on secure pages · tracking codes removed from links · " +
                    "sites can't use camera, microphone or location · sites can't open other apps · " +
                    "only video/audio files can be saved (no APKs or programs).",
                fontSize = 12.sp, color = Color.Gray,
            )
        }

        Section("Browser") {
            Text("Home page", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(home == "speedforce://start", { home = "speedforce://start"; Prefs.homePage = home }, label = { Text("Speed Force start page") })
                FilterChip(home != "speedforce://start", { if (home == "speedforce://start") home = "https://www.google.com/" }, label = { Text("Web address") })
            }
            if (home != "speedforce://start") {
                OutlinedTextField(home, { home = it }, label = { Text("Home page address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton({ Prefs.homePage = home.trim().ifEmpty { "https://www.google.com/" }; Toast.makeText(ctx, "Home page saved", Toast.LENGTH_SHORT).show() }) { Text("Save home page") }
            }
            Text("Search engine", fontSize = 13.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UrlUtils.engines.keys.forEach { name ->
                    FilterChip(engine == name, { engine = name; Prefs.searchEngine = name }, label = { Text(name) })
                }
            }
            SwitchRow("Reopen my tabs when the app starts (not in private mode)", restoreTabs) {
                restoreTabs = it; Prefs.restoreTabs = it
                if (!it) Prefs.clearSavedTabs()
            }
        }

        Section("About") {
            val appVersion = remember {
                try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
            }
            Text("Speed Force version $appVersion", fontSize = 13.sp)
            Text("To update: install a newer Speed Force APK over this one and tap Update. Your downloads and settings are kept.", fontSize = 12.sp, color = Color.Gray)
        }

        Section("Clear data") {
            Text("Removes browsing history, cookies (you will be logged out of sites), cache, the Ads billboard and the download list. Saved videos stay in your gallery.", fontSize = 12.sp, color = Color.Gray)
            OutlinedButton({ confirmClear = true }) { Text("Clear data") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all data?") },
            text = { Text("History, cookies, cache and the download list will be removed.") },
            confirmButton = {
                TextButton({
                    activity.clearBrowsingData(keepHistory = false)
                    DownloadManager.clearAll()
                    com.videograb.app.browser.AdBillboard.clear()
                    ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    confirmClear = false
                    Toast.makeText(ctx, "Data cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear") }
            },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(value, onChange)
    }
}
