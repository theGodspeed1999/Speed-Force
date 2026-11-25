package com.videograb.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.videograb.app.browser.AdFilter
import com.videograb.app.download.DownloadManager
import com.videograb.app.engine.Engine
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        // Before any page or download starts: look sites up through Cloudflare, not the provider
        com.videograb.app.net.SecureDns.apply()
        createChannels()
        DownloadManager.init(this)
        AdFilter.init(this) // small lists; loaded before any page opens
        // Learn the web engine's language header a moment after start, so yt-dlp can copy it
        android.os.Handler(mainLooper).postDelayed({ com.videograb.app.browser.BrowserHeaders.init(this) }, 2000)
        // Unpacking Python + yt-dlp + ffmpeg takes a few seconds on first launch
        thread(name = "engine-init") {
            Engine.init(this)
            // Keep yt-dlp fresh automatically (sites change often); at most every 3 days
            if (System.currentTimeMillis() - Prefs.lastEngineUpdate > 3L * 24 * 3600 * 1000) {
                val result = kotlinx.coroutines.runBlocking { Engine.update(this@App) }
                android.util.Log.i("Engine", "auto-update: $result")
                if (!result.startsWith("Update failed") && !result.startsWith("Engine not ready")) {
                    Prefs.lastEngineUpdate = System.currentTimeMillis()
                }
            }
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_PROGRESS = "progress"
        const val CHANNEL_DONE = "done"
    }
}
