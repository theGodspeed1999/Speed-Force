package com.videograb.app.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import com.videograb.app.Prefs
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Learns the exact "Accept-Language" header the phone's web engine sends.
 *
 * Some video hosts (LuluStream and similar) tie each playlist link to the browser that opened the
 * player: the same User-Agent and Accept-Language must come back, or the server answers 403.
 * yt-dlp normally sends its own "en-us,en;q=0.5", so it has to copy the browser's value instead.
 *
 * Android does not show this header to apps (the engine adds it after the app sees a request),
 * so it is measured once: a hidden WebView opens a tiny server on 127.0.0.1 inside the app,
 * which reads the header. The answer is stored until the engine version or phone language changes.
 */
object BrowserHeaders {
    private const val TAG = "BrowserHeaders"

    @Volatile var acceptLanguage: String? = null
        private set

    private fun key(context: Context): String {
        val engine = try { WebViewCompat.getCurrentWebViewPackage(context)?.versionName } catch (e: Exception) { null }
        return "${engine ?: "?"}|${Locale.getDefault().toLanguageTag()}|${android.os.LocaleList.getDefault().toLanguageTags()}"
    }

    /** Call on the main thread after startup. Cheap when the stored answer is still valid. */
    fun init(context: Context) {
        val app = context.applicationContext
        val k = key(app)
        if (Prefs.acceptLanguageKey == k && Prefs.acceptLanguage.isNotEmpty()) {
            acceptLanguage = Prefs.acceptLanguage
            return
        }
        acceptLanguage = Prefs.acceptLanguage.ifEmpty { null } // old value until the new one arrives
        measure(app) { value ->
            if (value.isNullOrBlank()) return@measure
            acceptLanguage = value
            Prefs.acceptLanguage = value
            Prefs.acceptLanguageKey = k
            Log.i(TAG, "Accept-Language: $value")
        }
    }

    private fun measure(app: Context, done: (String?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        val server = try {
            ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 30_000 } // a phone's first web engine start can be slow
        } catch (e: Exception) {
            Log.w(TAG, "no local server", e); return
        }
        var view: WebView? = null
        thread(name = "accept-language") {
            var value: String? = null
            try {
                server.accept().use { s ->
                    s.soTimeout = 4000
                    val reader = s.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val i = line.indexOf(':')
                        if (i > 0 && line.substring(0, i).trim().equals("Accept-Language", ignoreCase = true)) {
                            value = line.substring(i + 1).trim()
                        }
                    }
                    s.getOutputStream().write(
                        "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "measure failed", e)
            } finally {
                runCatching { server.close() }
                main.post {
                    view?.let { runCatching { it.stopLoading(); it.destroy() } }
                    view = null
                    done(value)
                }
            }
        }
        try {
            view = WebView(app).apply {
                webViewClient = WebViewClient()
                loadUrl("http://127.0.0.1:${server.localPort}/")
            }
        } catch (e: Exception) {
            Log.w(TAG, "no web engine", e)
            runCatching { server.close() }
        }
    }
}
