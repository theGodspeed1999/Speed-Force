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

/** Checks GitHub Releases on startup and downloads a newer APK when available. */
object AppUpdate {
    const val CURRENT_VERSION = "1.5.0"
    private const val LATEST_API = "https://api.github.com/repos/theGodspeed1999/Speed-Force/releases/latest"

    data class Ready(val version: String, val apk: File)

    fun isNewer(remoteTag: String, current: String = CURRENT_VERSION): Boolean {
        val clean = remoteTag.trim().trimStart('v', 'V').substringBefore('-')
        if (clean.isEmpty()) return false
        return VersionUtils.comparator.compare(clean, current) > 0
    }

    private fun preferredAssetName(): String =
        if (Build.SUPPORTED_ABIS.any { it.contains("arm64") }) "SpeedForce-Android-arm64.apk"
        else "SpeedForce-Android-universal.apk"

    suspend fun checkAndDownload(context: Context): Ready? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "SpeedForce")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            if (conn.responseCode != 200) { conn.disconnect(); return@withContext null }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            val tag = json.optString("tag_name", "")
            if (!isNewer(tag)) return@withContext null
            val version = tag.trim().trimStart('v', 'V')

            val wanted = preferredAssetName()
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                if (name == wanted || (url == null && name == "SpeedForce-Android-universal.apk")) {
                    url = a.optString("browser_download_url", "").ifEmpty { null }
                }
            }
            if (url == null) return@withContext null

            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "SpeedForce-$version.apk")
            if (target.exists() && target.length() > 0) return@withContext Ready(version, target)

            // Remove older cached APKs before downloading the new one
            dir.listFiles()?.forEach { it.delete() }
            val tmp = File(dir, "SpeedForce-$version.apk.tmp")
            val dl = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "SpeedForce")
            }
            if (dl.responseCode != 200) { dl.disconnect(); return@withContext null }
            dl.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            dl.disconnect()
            if (!tmp.renameTo(target)) { tmp.delete(); return@withContext null }
            Ready(version, target)
        } catch (_: Exception) {
            null
        }
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
