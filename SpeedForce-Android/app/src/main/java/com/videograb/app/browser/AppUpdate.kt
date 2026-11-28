package com.videograb.app.browser

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.videograb.app.util.VersionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Checks GitHub Releases on startup or manually, verifies SHA-256, and cleans up old APKs. */
object AppUpdate {
    const val CURRENT_VERSION = "1.5.4"
    private const val LATEST_API = "https://api.github.com/repos/theGodspeed1999/Speed-Force/releases/latest"

    data class Ready(val version: String, val apk: File)

    sealed class CheckResult {
        data class UpdateReady(val ready: Ready) : CheckResult()
        data object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
    }

    fun isNewer(remoteTag: String, current: String = CURRENT_VERSION): Boolean {
        val clean = remoteTag.trim().trimStart('v', 'V').substringBefore('-')
        if (clean.isEmpty()) return false
        return VersionUtils.comparator.compare(clean, current) > 0
    }

    private fun preferredAssetName(): String =
        if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) "SpeedForce-Android-arm64.apk"
        else "SpeedForce-Android-universal.apk"

    /** Removes downloaded APKs for this or older versions so they don't waste phone storage. */
    fun cleanOldUpdates(context: Context) {
        val dir = File(context.cacheDir, "updates")
        if (!dir.isDirectory) return
        dir.listFiles()?.forEach { f ->
            val ver = f.name.removePrefix("SpeedForce-").removeSuffix(".apk")
            if (f.name.endsWith(".tmp") || !f.name.endsWith(".apk") || !isNewer(ver)) {
                f.delete()
            }
        }
    }

    suspend fun checkAndDownload(context: Context): Ready? =
        (checkNow(context) as? CheckResult.UpdateReady)?.ready

    suspend fun checkNow(context: Context, onProgress: (String) -> Unit = {}): CheckResult = withContext(Dispatchers.IO) {
        cleanOldUpdates(context)
        try {
            onProgress("Checking GitHub…")
            val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "SpeedForce")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) {
                val code = conn.responseCode
                conn.disconnect()
                return@withContext CheckResult.Failed("GitHub returned HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            if (!isNewer(tag)) return@withContext CheckResult.UpToDate
            val version = tag.trim().trimStart('v', 'V')

            val wanted = preferredAssetName()
            val assets = json.optJSONArray("assets") ?: return@withContext CheckResult.Failed("No release files found")
            var url: String? = null
            var assetName = wanted
            var sumsUrl: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name == wanted || (url == null && name == "SpeedForce-Android-universal.apk")) {
                    url = a.optString("browser_download_url", "").ifEmpty { null }
                    assetName = name
                } else if (name == "SHA256SUMS.txt") {
                    sumsUrl = a.optString("browser_download_url", "").ifEmpty { null }
                }
            }
            if (url == null) return@withContext CheckResult.Failed("No APK in release")
            val expectedHash = sumsUrl?.let { fetchExpectedHash(it, assetName) }
            if (sumsUrl != null && expectedHash == null) return@withContext CheckResult.Failed("Could not verify checksum list")

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "SpeedForce-$version.apk")
            if (target.exists() && target.length() > 0) {
                if (expectedHash == null || sha256(target) == expectedHash) return@withContext CheckResult.UpdateReady(Ready(version, target))
                target.delete()
            }

            onProgress("Downloading $version…")
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "SpeedForce-$version.apk.tmp")
            val dl = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SpeedForce")
            }
            if (dl.responseCode != 200) {
                val code = dl.responseCode
                dl.disconnect()
                return@withContext CheckResult.Failed("Download failed (HTTP $code)")
            }
            dl.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            dl.disconnect()
            if (expectedHash != null && sha256(tmp) != expectedHash) {
                tmp.delete()
                return@withContext CheckResult.Failed("Checksum mismatch")
            }
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return@withContext CheckResult.Failed("Could not save APK")
            }
            CheckResult.UpdateReady(Ready(version, target))
        } catch (e: Exception) {
            CheckResult.Failed(e.message ?: "Check failed")
        }
    }

    private fun fetchExpectedHash(sumsUrl: String, fileName: String): String? = try {
        val c = (URL(sumsUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "SpeedForce")
        }
        val text = if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else ""
        c.disconnect()
        text.lineSequence().map { it.trim() }
            .firstOrNull { it.length > 65 && it.endsWith(fileName) }
            ?.substring(0, 64)?.lowercase()
            ?.takeIf { h -> h.all { it in '0'..'9' || it in 'a'..'f' } }
    } catch (_: Exception) { null }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
