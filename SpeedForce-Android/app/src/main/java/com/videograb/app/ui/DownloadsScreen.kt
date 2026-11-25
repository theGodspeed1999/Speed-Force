package com.videograb.app.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videograb.app.MainActivity
import com.videograb.app.download.DownloadItem
import com.videograb.app.download.DownloadManager
import com.videograb.app.download.MediaSaver
import com.videograb.app.download.Status
import com.videograb.app.util.UrlUtils

@Composable
fun DownloadsScreen(activity: MainActivity) {
    val items by DownloadManager.items.collectAsState()
    var link by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    fun go() {
        val url = UrlUtils.extractUrl(link) ?: UrlUtils.toUrlOrSearch(link).takeIf { !it.contains("duckduckgo.com/?q=") }
        if (url == null) {
            Toast.makeText(ctx, "That doesn't look like a web link", Toast.LENGTH_SHORT).show()
            return
        }
        activity.picker.open(PickRequest(pageUrl = url))
        link = ""
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Download from link", fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = link, onValueChange = { link = it }, singleLine = true,
                    placeholder = { Text("Paste a video page link") },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go() }),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ link = clipboard.getText()?.text ?: "" }) { Text("Paste") }
                    Button({ go() }, enabled = link.isNotBlank()) { Text("Get video") }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Downloads", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (items.any { !it.isActive && it.status != Status.PAUSED }) {
                TextButton({ DownloadManager.clearFinished() }) { Text("Clear finished") }
            }
        }
        if (items.isEmpty()) {
            Text(
                "Nothing yet. Open a page in the Browser, tap the red Download button on a video, and pick a quality.",
                color = Color.Gray, modifier = Modifier.padding(top = 12.dp),
            )
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items, key = { it.id }) { DownloadRow(it) }
        }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(item.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val statusText = when (item.status) {
                Status.QUEUED -> "Waiting"
                Status.RUNNING -> item.stage.ifEmpty { "Downloading" }
                Status.PAUSED -> "Paused"
                Status.DONE -> if (item.direct) "Saved to Downloads" else "Saved to gallery"
                Status.FAILED -> "Failed"
                Status.CANCELED -> "Cancelled"
            }
            Text("${item.formatLabel} · $statusText", fontSize = 12.sp, color = Color.Gray)
            if (item.status == Status.RUNNING || item.status == Status.PAUSED) {
                LinearProgressIndicator(
                    progress = { (item.percent / 100f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
                val parts = listOfNotNull(
                    "%.0f%%".format(item.percent),
                    item.speed.ifEmpty { null },
                    item.eta.ifEmpty { null }?.let { "ETA $it" },
                    item.sizeText.ifEmpty { null }?.let { "of $it" },
                )
                Text(parts.joinToString(" · "), fontSize = 12.sp)
            }
            if (item.status == Status.DONE && item.sizeText.isNotEmpty()) Text(item.sizeText, fontSize = 12.sp)
            if (item.status == Status.FAILED && item.error.isNotEmpty()) {
                Text(item.error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                when (item.status) {
                    Status.RUNNING, Status.QUEUED -> {
                        TextButton({ DownloadManager.pause(item.id) }) { Text("Pause") }
                        TextButton({ DownloadManager.cancel(item.id) }) { Text("Cancel") }
                    }
                    Status.PAUSED -> {
                        TextButton({ DownloadManager.resume(item.id) }) { Text("Resume") }
                        TextButton({ DownloadManager.cancel(item.id) }) { Text("Cancel") }
                    }
                    Status.FAILED, Status.CANCELED -> {
                        TextButton({ DownloadManager.retry(item.id) }) { Text("Retry") }
                        TextButton({ DownloadManager.remove(item.id) }) { Text("Remove") }
                    }
                    Status.DONE -> {
                        TextButton({
                            try {
                                val uri = Uri.parse(item.savedUri)
                                val mime = ctx.contentResolver.getType(uri) ?: MediaSaver.mimeFor(item.savedUri.substringAfterLast('.'))
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            } catch (e: Exception) {
                                Toast.makeText(ctx, if (item.direct) "Open it from your Files app (Download/" + com.videograb.app.Prefs.folderName + ")" else "Open it from your Gallery (Movies/VideoGrab)", Toast.LENGTH_LONG).show()
                            }
                        }) { Text(if (item.direct) "Open" else "Play") }
                        TextButton({ DownloadManager.remove(item.id) }) { Text("Remove from list") }
                    }
                }
            }
        }
    }
}
