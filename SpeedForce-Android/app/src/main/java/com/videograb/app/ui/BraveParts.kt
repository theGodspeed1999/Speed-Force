package com.videograb.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videograb.app.Prefs
import com.videograb.app.R
import com.videograb.app.browser.ShieldStats
import com.videograb.app.util.UrlUtils

/** The shield (like Brave's lion shield button). */
val ShieldIcon: ImageVector = materialIcon("SpeedForceShield") {
    materialPath {
        moveTo(12f, 1f)
        lineTo(3f, 5f)
        verticalLineToRelative(6f)
        curveToRelative(0f, 5.55f, 3.84f, 10.74f, 9f, 12f)
        curveToRelative(5.16f, -1.26f, 9f, -6.45f, 9f, -12f)
        verticalLineTo(5f)
        lineToRelative(-9f, -4f)
        close()
    }
}

/** Round "letter" icon for a site (no web pictures are downloaded). */
@Composable
fun SiteAvatar(host: String, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val letter = host.removePrefix("www.").firstOrNull()?.uppercase() ?: "•"
    val hues = listOf(0xFFFB542B, 0xFF4C54D2, 0xFF2E9D6B, 0xFFB5517C, 0xFF3D8BD9, 0xFFC68A1D)
    val color = Color(hues[(host.removePrefix("www.").hashCode() and 0x7fffffff) % hues.size])
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.42f).sp)
    }
}

/** The tabs button: a rounded square with the number of open tabs. */
@Composable
fun TabCountBox(count: Int) {
    Box(
        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp)).background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(7.dp), color = Color.Transparent,
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface), modifier = Modifier.size(24.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (count > 99) ":D" else "$count", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun MenuRow(icon: ImageVector?, text: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null, hint: String? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) else Spacer(Modifier.width(24.dp))
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 16.sp)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
    }
}

@Composable
fun SwitchLine(text: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text, fontSize = 15.sp)
            if (hint != null) Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

/** The page you get on a new tab: logo, search, what the shields saved you, and your top sites. */
@Composable
fun StartPage(onSearchTap: () -> Unit, onOpen: (String) -> Unit) {
    val blocked = remember { ShieldStats.blockedTotal() }
    val bytes = remember { ShieldStats.bytesTotal() }
    val secs = remember { ShieldStats.secondsSaved() }
    val sites = remember {
        Prefs.history().mapNotNull { (_, u) -> UrlUtils.host(u)?.removePrefix("www.")?.let { it to u } }
            .groupBy { it.first }.entries.sortedByDescending { it.value.size }.take(8).map { it.key to it.value.first().second }
    }
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(28.dp))
        Image(
            painterResource(R.drawable.speedforce_logo), "Speed Force",
            Modifier.size(84.dp).clip(CircleShape), contentScale = ContentScale.Crop,
        )
        Spacer(Modifier.height(10.dp))
        Text("Speed Force", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Fast. Private. Ad-free.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Surface(
            Modifier.fillMaxWidth().height(48.dp).clickable(onClick = onSearchTap),
            shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Text("Search or type web address", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard(Modifier.weight(1f), if (blocked >= 1000) "%.1fK".format(blocked / 1000.0) else "$blocked", "Trackers & ads blocked", Accent)
            StatCard(Modifier.weight(1f), ShieldStats.bytesText(bytes), "Data saved (est.)", Color(0xFF7B83FF))
            StatCard(Modifier.weight(1f), ShieldStats.timeText(secs), "Time saved (est.)", ShieldGreen)
        }
        if (sites.isNotEmpty()) {
            Spacer(Modifier.height(22.dp))
            Text("Top sites", Modifier.fillMaxWidth(), fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(10.dp))
            sites.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    row.forEach { (host, url) ->
                        Column(
                            Modifier.width(76.dp).clickable { onOpen(url) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            SiteAvatar(host, 52.dp)
                            Spacer(Modifier.height(4.dp))
                            Text(host, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.width(76.dp)) }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatCard(modifier: Modifier, value: String, label: String, color: Color) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.Start) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(Modifier.height(2.dp))
            Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
        }
    }
}
