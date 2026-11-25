package com.videograb.app.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.videograb.app.MainActivity
import com.videograb.app.browser.AdBillboard
import com.videograb.app.browser.AdFilter
import com.videograb.app.browser.AdMode
import com.videograb.app.util.UrlUtils
import java.text.DateFormat
import java.util.Date

/** The billboard: ads and popups that were kept away from the page you were reading. */
@Composable
fun AdsScreen(activity: MainActivity) {
    val ctx = LocalContext.current
    var viewing by remember { mutableStateOf<AdBillboard.Entry?>(null) }

    val v = viewing
    if (v != null) {
        BackHandler { viewing = null }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(v.url, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                TextButton({ viewing = null }) { Text("Close") }
            }
            Text(
                "Look only. Downloads, app links and permission requests are blocked here.",
                fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 8.dp),
            )
            key(v.id) {
                AndroidView(
                    factory = { c ->
                        AdBillboard.open(c, v).also { (it.parent as? ViewGroup)?.removeView(it) }
                    },
                    onRelease = { (it.parent as? ViewGroup)?.removeView(it) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("Ad billboard", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("HIDE" to "Hide", "BLOCK" to "Block", "OFF" to "Off").forEach { (mode, label) ->
                FilterChip(activity.adMode == mode, { activity.changeAdMode(mode) }, label = { Text(label) })
            }
        }
        val modeText = when (AdFilter.mode) {
            AdMode.HIDE -> "Hide mode: ads still load (so sites can't tell you're filtering) but they are invisible " +
                "and can't be tapped. Popups and ad redirects open here instead of in front of you."
            AdMode.BLOCK -> "Block mode: ads are not loaded at all. Some sites may notice and complain."
            AdMode.OFF -> "Ad handling is off (Settings). Nothing is filtered."
        }
        Text(modeText, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("${AdBillboard.hiddenOnPages}", "ads hidden")
                Stat("${AdBillboard.popupsCaught}", "popups caught")
                Stat("${AdBillboard.trackersBlocked}", "trackers blocked")
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Caught (newest first)", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (AdBillboard.entries.isNotEmpty()) TextButton({ AdBillboard.clear() }) { Text("Clear all") }
        }
        if (AdBillboard.entries.isEmpty()) {
            Text("Nothing caught yet. Browse normally; ads and popups will collect here.", color = Color.Gray, fontSize = 13.sp)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AdBillboard.entries, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                e.kind.label + " · " + (UrlUtils.host(e.url) ?: e.url),
                                fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "from " + (UrlUtils.host(e.page) ?: "?") + " · " +
                                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(e.time)),
                                fontSize = 11.sp, color = Color.Gray, maxLines = 1,
                            )
                        }
                        if (UrlUtils.isHttp(e.url)) TextButton({ viewing = e }) { Text("View") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
        Text(label, fontSize = 11.sp, color = Color.Gray)
    }
}
