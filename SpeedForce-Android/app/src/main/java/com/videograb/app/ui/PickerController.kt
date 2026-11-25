package com.videograb.app.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.videograb.app.Prefs
import com.videograb.app.browser.SniffedStream
import com.videograb.app.download.DownloadManager
import com.videograb.app.engine.DownloadOption
import com.videograb.app.engine.Engine
import com.videograb.app.engine.FormatOptionsBuilder
import com.videograb.app.engine.MediaInfo
import com.videograb.app.engine.RequestContext
import com.videograb.app.util.StreamKind
import com.videograb.app.util.UrlUtils
import com.videograb.app.engine.StreamProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the user asked to download. */
data class PickRequest(
    val pageUrl: String,
    val frameUrl: String? = null,
    val directUrl: String? = null,
    val title: String = "",
    val streams: List<SniffedStream> = emptyList(),
    /** Length the page's player reports (seconds), used when the video's own data doesn't say. */
    val durationHint: Int = 0,
    /** Picture size the page's player reports ("720"). */
    val heightHint: Int = 0,
    /** Streams the tab has caught so far; asked again while the picker waits for the player to start. */
    val liveStreams: (() -> List<SniffedStream>)? = null,
)

/** One address we can hand to yt-dlp, with the headers to use. */
data class Candidate(val url: String, val label: String, val ctx: RequestContext, val isStream: Boolean)

sealed class PickerUi {
    data object Hidden : PickerUi()
    data class Loading(val message: String) : PickerUi()
    data class Ready(
        val candidate: Candidate,
        val infos: List<MediaInfo>,
        val entry: Int,
        val options: List<DownloadOption>,
        val defaultIndex: Int,
        val others: List<Candidate>,
    ) : PickerUi()
    data class Failed(val message: String, val others: List<Candidate>) : PickerUi()
}

class PickerController(private val context: Context, private val scope: CoroutineScope) {
    var ui by mutableStateOf<PickerUi>(PickerUi.Hidden)
        private set
    private var job: Job? = null

    fun close() {
        job?.cancel()
        ui = PickerUi.Hidden
    }

    private fun candidates(req: PickRequest): List<Candidate> {
        val list = ArrayList<Candidate>()
        val added = HashSet<String>()
        fun add(url: String?, label: String, referer: String?, ua: String? = null, stream: Boolean = false) {
            if (!UrlUtils.isHttp(url) || !added.add(UrlUtils.dedupeKey(url!!))) return
            val origin = if (stream && referer != null) UrlUtils.origin(referer) else null
            val ctx = RequestContext.capture(context, listOf(url, req.pageUrl, req.frameUrl, referer), referer, ua, origin)
            list.add(Candidate(url, label, ctx, stream))
        }
        add(req.pageUrl, "This page", null)
        add(req.frameUrl, "Video player frame", req.pageUrl)
        add(req.directUrl, "Video file", req.frameUrl ?: req.pageUrl, stream = true)
        // Captured streams: playlists (HLS/DASH) first because they list every quality.
        // The master playlist (all qualities) is normally the first one a player loads.
        req.streams.sortedWith(
            compareBy<SniffedStream>(
                // Little hover previews / background clips go last
                { if (UrlUtils.looksLikePreviewClip(it.url)) 1 else 0 },
                { if (it.kind == StreamKind.HLS || it.kind == StreamKind.DASH) 0 else 1 },
                { if (UrlUtils.looksLikeMasterPlaylist(it.url)) 0 else 1 },
                { it.foundAt },
            )
        )
            .take(12)
            .forEach { s ->
                add(s.url, "Captured ${s.kind.name}: ${UrlUtils.shortName(s.url)}", s.referer ?: req.pageUrl, s.userAgent, stream = true)
            }
        return list
    }

    fun open(req: PickRequest) {
        val all = candidates(req)
        if (all.isEmpty()) {
            ui = PickerUi.Failed("Nothing to download on this page.", emptyList())
            return
        }
        hint = req.durationHint
        heightHint = req.heightHint
        directUrl = req.directUrl
        title = req.title
        tryInOrder(all, all, req)
    }

    private var hint = 0
    private var heightHint = 0
    private var directUrl: String? = null
    private var title = ""

    /** Qualities straight from a captured stream, in about a second. Null when none of them answers. */
    private suspend fun fastLook(order: List<Candidate>, all: List<Candidate>): PickerUi.Ready? = withContext(Dispatchers.IO) {
        val streams = order.filter { it.isStream && !UrlUtils.looksLikePreviewClip(it.url) }.take(4)
        val looks = streams.map { c -> c to async { StreamProbe.probe(c.url, c.ctx, hint) } }
        for ((c, look) in looks) {
            val r = look.await() ?: continue
            // A few seconds or a few MB without a known length: an ad or preview, not the film
            val tooSmall = r.qualities.isEmpty() && (r.sizeBytes in 1 until 200_000L || r.durationSec < 30 && r.sizeBytes in 1 until 5_000_000L)
            if (tooSmall) continue
            val isHls = c.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            val name = title.ifBlank { UrlUtils.fileName(c.url) }
            val info = MediaInfo(name, c.url, "", emptyList(), r.durationSec.takeIf { it > 0 } ?: hint)
            val opts = StreamProbe.options(r, Prefs.maxResolution, isHls, if (c.url == directUrl) heightHint else 0)
            return@withContext PickerUi.Ready(c, listOf(info), 0, opts, FormatOptionsBuilder.defaultIndex(opts, Prefs.maxResolution), all.filter { it.url != c.url })
        }
        null
    }

    /** User tapped one of the "other streams" rows. */
    fun tryCandidate(c: Candidate, all: List<Candidate>) = tryInOrder(listOf(c), all)

    private fun tryInOrder(order: List<Candidate>, all: List<Candidate>, watch: PickRequest? = null) {
        job?.cancel()
        job = scope.launch {
            var lastError = ""
            var knownCands = all
            val live = watch?.liveStreams
            val noStreamYet = order.none { it.isStream && !UrlUtils.looksLikePreviewClip(it.url) }
            ui = PickerUi.Loading(
                if (live != null && noStreamYet) "Starting the video to find its address…" else "Looking for video qualities…"
            )
            // Show what the captured streams say at once; yt-dlp may still find more behind it.
            // The app has just started the page's player, so streams it loads now are read too (up to 15 s).
            val fast = launch {
                val probed = HashSet<String>()
                val until = System.currentTimeMillis() + if (live != null) 15_000 else 0
                var cands = order
                while (true) {
                    val fresh = cands.filter { it.isStream && probed.add(UrlUtils.dedupeKey(it.url)) }
                    if (fresh.isNotEmpty()) fastLook(fresh, knownCands)?.let { r ->
                        if (ui is PickerUi.Loading) ui = r
                        return@launch
                    }
                    if (ui !is PickerUi.Loading || live == null || System.currentTimeMillis() > until) return@launch
                    delay(400)
                    val now = live()
                    if (now.size != watch!!.streams.size || cands === order) {
                        knownCands = candidates(watch.copy(streams = now))
                        cands = knownCands
                    }
                }
            }
            // yt-dlp starts a whole Python engine: give the player a head start when it is about to answer
            if (live != null && noStreamYet) delay(2500)
            // Each failed look costs many seconds on a phone: try the likely ones, the rest stay listed
            val tries = order.take(4)
            for ((i, c) in tries.withIndex()) {
                if (ui !is PickerUi.Loading) break // the quick look already answered
                ui = PickerUi.Loading(
                    "Looking for video qualities…\n${c.label}" + if (tries.size > 1) "  (${i + 1}/${tries.size})" else ""
                )
                // Most video servers need to know which page you came from, but some (e.g. Twitter/X's)
                // refuse requests that mention another site. So: try with it, and without it only after a refusal (403).
                val variants = if (c.isStream && (c.ctx.referer != null || c.ctx.origin != null))
                    listOf(c, c.copy(ctx = c.ctx.copy(referer = null, origin = null)))
                else listOf(c)
                for ((vi, v) in variants.withIndex()) {
                    if (vi > 0 && !lastError.contains("403")) break
                    try {
                        val infos = Engine.fetchInfo(context, v.url, v.ctx)
                        val first = infos.indexOfFirst { it.formats.isNotEmpty() }
                        if (first < 0) {
                            lastError = "No video formats found"; continue
                        }
                        // Keep a fast result on screen unless yt-dlp knows more qualities
                        val shown = ui as? PickerUi.Ready
                        val known = withContext(Dispatchers.IO) { fillSizes(infos, first, v) }
                        val more = FormatOptionsBuilder.build(known[first], Prefs.maxResolution).size
                        if (shown == null || more > maxOf(shown.options.size, 3)) showInfo(v, known, first, all)
                        return@launch
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        lastError = Engine.shortError(e)
                    }
                }
            }
            fast.join() // the player may still be about to answer
            if (ui !is PickerUi.Ready) ui = PickerUi.Failed(lastError.ifEmpty { "No video found on this page" }, knownCands.filter { it.isStream })
        }
    }

    fun selectEntry(index: Int) {
        val r = ui as? PickerUi.Ready ?: return
        showInfo(r.candidate, r.infos, index, r.others)
    }

    private fun showInfo(c: Candidate, infos: List<MediaInfo>, entry: Int, all: List<Candidate>) {
        val maxRes = Prefs.maxResolution
        val known = infos.mapIndexed { i, x -> if (i == entry && x.durationSec <= 0 && hint > 0) x.copy(durationSec = hint) else x }
        val opts = FormatOptionsBuilder.build(known[entry], maxRes)
        ui = PickerUi.Ready(c, known, entry, opts, FormatOptionsBuilder.defaultIndex(opts, maxRes), all.filter { it.url != c.url })
    }

    /** yt-dlp often doesn't say how big a plain file is, but the file's server does. */
    private fun fillSizes(infos: List<MediaInfo>, entry: Int, c: Candidate): List<MediaInfo> {
        val info = infos[entry]
        val formats = info.formats.map { f ->
            val plain = f.url.isNotEmpty() && !f.protocol.startsWith("m3u8") && !f.protocol.contains("dash")
            if (f.fileSize > 0 || !plain) f else f.copy(fileSize = StreamProbe.probe(f.url, c.ctx, 0)?.sizeBytes ?: 0L)
        }
        return infos.mapIndexed { i, x -> if (i == entry) x.copy(formats = formats) else x }
    }

    /** Add to the download queue. */
    fun choose(option: DownloadOption) {
        val r = ui as? PickerUi.Ready ?: return
        val info = r.infos[r.entry]
        DownloadManager.enqueue(r.candidate.url, info.title, option, r.candidate.ctx, r.entry + 1)
        job?.cancel()
        ui = PickerUi.Hidden
    }

    /** Download a stream as-is ("best") without asking yt-dlp for formats first. */
    fun downloadDirect(c: Candidate) {
        val title = c.url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').ifBlank { "video" }
        DownloadManager.enqueue(c.url, title, DownloadOption("Best", "", "bv*+ba/b"), c.ctx)
        job?.cancel()
        ui = PickerUi.Hidden
    }
}
