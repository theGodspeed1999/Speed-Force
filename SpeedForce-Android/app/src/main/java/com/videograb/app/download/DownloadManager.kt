package com.videograb.app.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.videograb.app.App
import com.videograb.app.MainActivity
import com.videograb.app.Prefs
import com.videograb.app.R
import com.videograb.app.engine.DownloadOption
import com.videograb.app.engine.Engine
import com.videograb.app.engine.ProgressParser
import com.videograb.app.engine.RequestContext
import com.videograb.app.util.UrlUtils
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The download queue. Runs up to Prefs.maxParallel yt-dlp processes at a time. */
object DownloadManager {
    private const val TAG = "DownloadManager"
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    private val storeFile by lazy { File(app.filesDir, "downloads.json") }

    fun init(context: Context) {
        app = context.applicationContext
        try {
            if (storeFile.exists()) {
                val arr = JSONArray(storeFile.readText())
                _items.value = (0 until arr.length()).map { DownloadItem.fromJson(arr.getJSONObject(it)) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not load list", e)
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            _items.value.forEach { arr.put(it.toJson()) }
            storeFile.writeText(arr.toString())
        } catch (e: Exception) {
            Log.e(TAG, "could not save list", e)
        }
    }

    /** Change the list (and save it to disk when [save]). */
    private fun edit(save: Boolean = true, change: (List<DownloadItem>) -> List<DownloadItem>) {
        synchronized(lock) { _items.value = change(_items.value) }
        if (save) persist()
    }

    private fun update(id: String, save: Boolean = false, change: (DownloadItem) -> DownloadItem) =
        edit(save) { list -> list.map { if (it.id == id) change(it) else it } }

    fun get(id: String) = _items.value.firstOrNull { it.id == id }

    private val workRoot get() = File(app.getExternalFilesDir(null) ?: app.filesDir, "work")
    private fun workDir(id: String) = File(workRoot, id)

    fun enqueue(url: String, title: String, option: DownloadOption, ctx: RequestContext, entryIndex: Int = 1): DownloadItem {
        val item = DownloadItem(
            id = UUID.randomUUID().toString().take(12), url = url, title = title.ifBlank { url },
            format = option.format, formatLabel = option.label, audioOnly = option.audioOnly,
            entryIndex = entryIndex, ctx = ctx,
        )
        edit { listOf(item) + it }
        pump()
        return item
    }

    /** Any file that is not a video: downloaded as it is and saved to the phone's Downloads folder. */
    fun enqueueDirect(url: String, ctx: RequestContext): DownloadItem {
        val guess = android.net.Uri.parse(url).lastPathSegment?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
        val item = DownloadItem(
            id = UUID.randomUUID().toString().take(12), url = url, title = guess?.takeIf { it.isNotBlank() } ?: url,
            format = "", formatLabel = "File", audioOnly = false, ctx = ctx, direct = true,
        )
        edit { listOf(item) + it }
        pump()
        return item
    }

    private fun runDirect(item: DownloadItem) {
        val id = item.id
        val dir = workDir(id)
        try {
            val r = DirectDownloader.run(
                item.url, item.ctx, dir, item.title,
                shouldStop = { val s = get(id)?.status; s == null || s == Status.PAUSED || s == Status.CANCELED },
                progress = { pct, speed, eta, total -> update(id) { it.copy(percent = pct, speed = speed, eta = eta, sizeText = total, stage = "") } },
            )
            update(id) { it.copy(stage = "Saving to Downloads…", percent = 100f, title = r.name) }
            val uri = MediaSaver.saveFile(app, r.file, r.name, r.mime, Prefs.folderName)
            val size = ProgressParser.humanSize(r.file.length())
            dir.deleteRecursively()
            update(id, save = true) {
                it.copy(status = Status.DONE, savedUri = uri.toString(), stage = "", speed = "", eta = "", sizeText = size,
                    ctx = it.ctx.copy(cookies = emptyMap()))
            }
            notifyFinished(id, true)
        } catch (e: Throwable) {
            when (get(id)?.status) {
                Status.PAUSED -> update(id, save = true) { it.copy(speed = "", eta = "", stage = "") }
                Status.CANCELED -> { dir.deleteRecursively(); persist() }
                null -> dir.deleteRecursively()
                else -> {
                    Log.e(TAG, "file download failed", e)
                    update(id, save = true) { it.copy(status = Status.FAILED, error = e.message ?: e.toString(), speed = "", eta = "", stage = "") }
                    notifyFinished(id, false)
                }
            }
        }
    }

    /** Start queued downloads while there are free slots. */
    fun pump() {
        val toStart = ArrayList<DownloadItem>()
        synchronized(lock) {
            val free = Prefs.maxParallel - jobs.size
            if (free <= 0) return@synchronized
            _items.value.asReversed() // oldest first
                .filter { it.status == Status.QUEUED && !jobs.containsKey(it.id) }
                .take(free)
                .forEach { item ->
                    val job = scope.launch(start = CoroutineStart.LAZY) { run(item.id) }
                    jobs[item.id] = job
                    job.invokeOnCompletion {
                        jobs.remove(item.id)
                        pump()
                    }
                    toStart.add(item)
                }
        }
        toStart.forEach { item ->
            update(item.id) { it.copy(status = Status.RUNNING, error = "", stage = "Starting…") }
            jobs[item.id]?.start()
        }
        if (_items.value.any { it.isActive }) DownloadService.start(app)
    }

    private fun run(id: String) {
        val item = get(id) ?: return
        if (item.direct) { runDirect(item); return }
        val dir = workDir(id).apply { mkdirs() }
        val cookies = Engine.cookieFile(app, id)
        try {
            kotlinx.coroutines.runBlocking { Engine.awaitReady() }
            val req = YoutubeDLRequest(item.url)
            req.addOption("-f", item.format)
            // The name you saw in the list; video addresses often have only a long code as file name
            val name = item.title.replace(Regex("[\\\\/:*?\"<>|%\\p{Cntrl}]"), "_").trim().take(80).ifEmpty { "video" }
            req.addOption("-o", File(dir, "$name.%(ext)s").absolutePath)
            req.addOption("--playlist-items", item.entryIndex.toString())
            req.addOption("--no-mtime")
            req.addOption("--newline")
            req.addOption("--no-warnings")
            // Faster: streams fetch 8 pieces at once, plain files use 8 connections (aria2c)
            req.addOption("--concurrent-fragments", "8")
            if (!item.singleConnection) {
                req.addOption("--downloader", "libaria2c.so")
                req.addOption("--downloader", "dash,m3u8:native")
                req.addOption("--downloader-args", "aria2c:-x 8 -s 8 -k 1M --file-allocation=none --summary-interval=1 --console-log-level=warn --async-dns=false")
            }
            req.addOption("--retries", "10")
            req.addOption("--fragment-retries", "10")
            req.addOption("--socket-timeout", "30")
            if (item.audioOnly) {
                req.addOption("-x")
                req.addOption("--audio-format", "m4a")
            } else {
                // Always MP4: plays in every gallery app (H.264/H.265/VP9/AV1 + AAC/Opus all fit)
                req.addOption("--merge-output-format", "mp4")
                // Other containers (webm, mkv, ts, flv ...) are turned into MP4 afterwards by Mp4Fixer,
                // which falls back to converting when a plain repack is not possible
                // (yt-dlp's own --remux-video would fail the whole download instead).
            }
            Engine.applyContext(req, item.ctx, cookies)

            var last = 0L
            YoutubeDL.getInstance().execute(req, id) { progress, eta, line ->
                val stage = when {
                    line.contains("[Merger]") -> "Joining video + sound…"
                    line.contains("[ExtractAudio]") -> "Converting audio…"
                    line.contains("[FixupM3u8]") || line.contains("[Fixup") -> "Fixing file…"
                    else -> null
                }
                val p = ProgressParser.parse(line)
                val now = System.currentTimeMillis()
                if (stage == null && now - last < 400) return@execute
                last = now
                update(id) { cur ->
                    cur.copy(
                        percent = p?.percent ?: if (progress > 0) progress else cur.percent,
                        speed = p?.speed ?: cur.speed,
                        eta = if (eta > 0) formatEta(eta) else (p?.eta ?: ""),
                        sizeText = p?.total ?: cur.sizeText,
                        stage = stage ?: if (p != null) "" else cur.stage,
                    )
                }
            }

            val found = dir.listFiles()
                ?.filter { it.isFile && it.name != "cookies.txt" && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.contains(".temp") && !it.name.endsWith(".json") }
                ?.maxByOrNull { it.length() }
                ?: throw IllegalStateException("Download finished but no file was produced")
            if (!UrlUtils.isMediaExtension(found.extension)) {
                // Safety: a site could try to push an app (APK) or program instead of a video
                dir.deleteRecursively()
                throw IllegalStateException("Blocked for safety: '${found.name}' is not a video or audio file")
            }
            // Every video ends up as .mp4, whatever the site served
            val out = Mp4Fixer.ensure(app, found, item.audioOnly) { s -> update(id) { it.copy(stage = s) } }
            update(id) { it.copy(stage = "Saving to gallery…", percent = 100f) }
            val uri = MediaSaver.save(app, out, Prefs.folderName)
            val size = ProgressParser.humanSize(out.length())
            dir.deleteRecursively()
            update(id, save = true) {
                // Login cookies are no longer needed once the video is saved: forget them
                it.copy(status = Status.DONE, savedUri = uri.toString(), stage = "", speed = "", eta = "", sizeText = size,
                    ctx = it.ctx.copy(cookies = emptyMap()))
            }
            notifyFinished(id, true)
        } catch (e: Throwable) {
            val cur = get(id)
            when (cur?.status) {
                Status.PAUSED -> update(id, save = true) { it.copy(speed = "", eta = "", stage = "") }
                Status.CANCELED -> { dir.deleteRecursively(); persist() }
                null -> dir.deleteRecursively()
                else -> if (!cur.singleConnection && (e.message ?: "").contains("aria2c")) {
                    // Some servers refuse several connections at once: start again the plain way
                    Log.w(TAG, "aria2c failed, retrying with one connection")
                    dir.deleteRecursively()
                    update(id, save = true) { it.copy(status = Status.QUEUED, singleConnection = true, percent = 0f, stage = "Retrying…") }
                } else if ((e.message ?: "").contains("403") && (cur.ctx.referer != null || cur.ctx.origin != null)) {
                    // Server refused because we said which page we came from (Twitter/X does this): retry without.
                    // The job finishing triggers pump(), which starts it again.
                    Log.w(TAG, "403 with referer, retrying without")
                    update(id, save = true) {
                        it.copy(status = Status.QUEUED, ctx = it.ctx.copy(referer = null, origin = null), stage = "Retrying…")
                    }
                } else {
                    Log.e(TAG, "download failed", e)
                    update(id, save = true) {
                        it.copy(status = Status.FAILED, error = Engine.shortError(e), speed = "", eta = "", stage = "")
                    }
                    notifyFinished(id, false)
                }
            }
        } finally {
            cookies.delete()
        }
    }

    private fun formatEta(sec: Long): String =
        if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

    /** Stops yt-dlp and the aria2c it started (which would otherwise keep downloading on its own). */
    private fun stopProcess(id: String) {
        YoutubeDL.getInstance().destroyProcessById(id)
        val folder = workDir(id).absolutePath
        File("/proc").listFiles { f -> f.name.all(Char::isDigit) }?.forEach { p ->
            val cmd = runCatching { File(p, "cmdline").readText() }.getOrDefault("")
            if (cmd.contains("aria2c") && cmd.contains(folder)) android.os.Process.killProcess(p.name.toInt())
        }
    }

    fun pause(id: String) {
        val item = get(id) ?: return
        if (!item.isActive) return
        update(id, save = true) { it.copy(status = Status.PAUSED, speed = "", eta = "") }
        if (!item.direct) stopProcess(id)
    }

    fun resume(id: String) {
        update(id, save = true) { it.copy(status = Status.QUEUED, error = "") }
        pump()
    }

    fun retry(id: String) {
        // Clean restart: forget partial files
        if (!jobs.containsKey(id)) workDir(id).deleteRecursively()
        update(id, save = true) { it.copy(status = Status.QUEUED, error = "", percent = 0f) }
        pump()
    }

    fun cancel(id: String) {
        update(id, save = true) { it.copy(status = Status.CANCELED, speed = "", eta = "", stage = "") }
        if (jobs.containsKey(id)) { if (get(id)?.direct != true) stopProcess(id) } else workDir(id).deleteRecursively()
    }

    fun remove(id: String) {
        if (get(id)?.isActive == true) cancel(id)
        workDir(id).deleteRecursively()
        edit { list -> list.filter { it.id != id } }
    }

    fun clearFinished() {
        edit { list -> list.filter { it.isActive || it.status == Status.PAUSED } }
    }

    fun clearAll() {
        _items.value.filter { it.isActive }.forEach { cancel(it.id) }
        edit { emptyList() }
        workRoot.deleteRecursively()
    }

    private fun notifyFinished(id: String, ok: Boolean) {
        val item = get(id) ?: return
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val open = PendingIntent.getActivity(
            app, id.hashCode(), Intent(app, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(app, App.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (ok) (if (item.direct) "Saved to Downloads" else "Saved to gallery") else "Download failed")
            .setContentText(item.title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(id.hashCode(), n) } catch (e: SecurityException) { /* notifications not allowed */ }
    }
}
