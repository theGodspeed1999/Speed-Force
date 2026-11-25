package com.videograb.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.videograb.app.util.UrlUtils

/** Bottom sheet that lists the resolutions to choose from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerSheet(picker: PickerController) {
    val ui = picker.ui
    if (ui is PickerUi.Hidden) return
    val ctx = LocalContext.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = { picker.close() }, sheetState = state) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            when (ui) {
                is PickerUi.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp))
                        Text(ui.message)
                    }
                    Text(
                        "The first check after opening the app can take 10–20 seconds.",
                        fontSize = 12.sp, color = Color.Gray,
                    )
                    TextButton({ picker.close() }) { Text("Cancel") }
                }

                is PickerUi.Ready -> {
                    val info = ui.infos[ui.entry]
                    Text(info.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        (if (info.durationSec > 0) "Length " + com.videograb.app.engine.FormatOptionsBuilder.duration(info.durationSec) + " · " else "") + "Saved as MP4",
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                    Text("Source: ${ui.candidate.label}", fontSize = 12.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ui.infos.size > 1) {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ui.infos.forEachIndexed { i, _ ->
                                FilterChip(i == ui.entry, { picker.selectEntry(i) }, label = { Text("Video ${i + 1}") })
                            }
                        }
                    }
                    Text("Pick a quality and tap its Download button:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    var showAll by remember(ui.candidate) { mutableStateOf(false) }
                    LazyColumn {
                        itemsIndexed(ui.options) { i, opt ->
                            val highlight = i == ui.defaultIndex
                            val start = {
                                picker.choose(opt)
                                Toast.makeText(ctx, "Added to Downloads: ${opt.label}", Toast.LENGTH_SHORT).show()
                            }
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (highlight) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable { start() }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(opt.label, fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal)
                                    if (opt.detail.isNotEmpty()) Text(opt.detail, fontSize = 12.sp, color = Color.Gray)
                                }
                                Button(start, contentPadding = PaddingValues(horizontal = 14.dp)) {
                                    Text("↓ Download")
                                }
                            }
                            HorizontalDivider()
                        }
                        if (ui.others.isNotEmpty()) {
                            item {
                                Text("Not the right video? Other videos found on this page:", Modifier.padding(top = 16.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            val shown = if (showAll) ui.others else ui.others.take(3)
                            items(shown) { c -> SourceRow(c, picker, ui.others + ui.candidate) }
                            if (ui.others.size > shown.size) {
                                item {
                                    TextButton({ showAll = true }) { Text("Show ${ui.others.size - shown.size} more") }
                                }
                            }
                        }
                    }
                }

                is PickerUi.Failed -> {
                    Text("The engine could not read this page", fontWeight = FontWeight.Bold)
                    Text(ui.message, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(vertical = 6.dp))
                    if (ui.others.isEmpty()) {
                        Text(
                            "Tip: press Play on the video, wait until it starts, then tap Download again — " +
                                "the app captures the video stream while it plays. If a site recently changed, " +
                                "try Settings → Update engine.",
                            fontSize = 13.sp,
                        )
                    } else {
                        Text("Captured streams — tap one to see its qualities, or Download it directly:", fontSize = 13.sp)
                        LazyColumn { items(ui.others) { c -> SourceRow(c, picker, ui.others) } }
                    }
                    TextButton({ picker.close() }) { Text("Close") }
                }

                PickerUi.Hidden -> {}
            }
        }
    }
}

@Composable
private fun SourceRow(c: Candidate, picker: PickerController, all: List<Candidate>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // File name first: it is what tells the videos apart (the site name is the same on every row)
        Column(Modifier.weight(1f).clickable { picker.tryCandidate(c, all) }) {
            Text(UrlUtils.fileName(c.url).ifEmpty { c.label }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.label.substringBefore(':') + " · " + (UrlUtils.host(c.url) ?: ""), fontSize = 11.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton({ picker.tryCandidate(c, all) }) { Text("Qualities") }
        TextButton({ picker.downloadDirect(c) }) { Text("Download") }
    }
    HorizontalDivider()
}
