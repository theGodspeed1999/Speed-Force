package com.videograb.app.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.videograb.app.App
import com.videograb.app.MainActivity
import com.videograb.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps the app alive (with a notification) while downloads run, even with the screen off. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(buildNotification("Preparing download…", 0, true))
        if (!started) {
            started = true
            acquireLocks()
            scope.launch {
                DownloadManager.items.collectLatest { list ->
                    val active = list.filter { it.isActive }
                    if (active.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val running = active.filter { it.status == Status.RUNNING }
                        val pct = if (running.isEmpty()) 0 else running.map { it.percent }.average().toInt()
                        val text = if (running.size == 1) "${running[0].title} · ${running[0].speed}"
                        else "${running.size} running, ${active.size - running.size} waiting"
                        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                        try { nm.notify(NOTIF_ID, buildNotification(text, pct, running.isEmpty())) } catch (_: SecurityException) {}
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: android.app.Notification) {
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        } catch (e: Exception) {
            Log.e("DownloadService", "startForeground failed", e)
        }
    }

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean) =
        NotificationCompat.Builder(this, App.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Speed Force: downloading")
            .setContentText(text)
            .setProgress(100, percent, indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, 1),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoGrab:download").apply { acquire(6 * 60 * 60 * 1000L) }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "VideoGrab:wifi").apply { acquire() }
        } catch (e: Exception) {
            Log.w("DownloadService", "locks", e)
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits background data work to ~6h/day; stop politely.
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 42

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses to start it while the app is in the background; downloads still run
                Log.w("DownloadService", "could not start service", e)
            }
        }
    }
}
