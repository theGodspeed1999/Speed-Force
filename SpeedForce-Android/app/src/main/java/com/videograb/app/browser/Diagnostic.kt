package com.videograb.app.browser

import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebViewClient
import org.json.JSONObject

/** "Why didn't this page load?": the page in assets/diag, shown by the app itself. */
object Diagnostic {
    /** An address that can never be a real site; the page is shown "from" here. */
    const val BASE = "https://diag.speedforce.invalid/"

    /** The kind of failure, or null when it is not one worth a diagnosis (blocked, stopped, ...). */
    fun kindOf(error: WebResourceError): String? {
        val text = error.description?.toString().orEmpty()
        if ("ERR_BLOCKED" in text || "ERR_ABORTED" in text || "ERR_CACHE_MISS" in text) return null
        if ("ERR_INTERNET_DISCONNECTED" in text) return "offline"
        return when (error.errorCode) {
            WebViewClient.ERROR_HOST_LOOKUP -> "dns"
            WebViewClient.ERROR_TIMEOUT -> "timeout"
            WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_IO, WebViewClient.ERROR_FAILED_SSL_HANDSHAKE,
            WebViewClient.ERROR_PROXY_AUTHENTICATION, WebViewClient.ERROR_UNKNOWN -> "connect"
            else -> null
        }
    }

    fun page(context: Context, url: String, kind: String, status: Int): String {
        fun read(name: String) = context.assets.open("diag/$name").bufferedReader().use { it.readText() }
        val info = JSONObject().put("url", url).put("kind", kind).put("status", status).toString().replace("<", "\\u003c")
        return read("index.html").replace(
            "<script src=\"diag.js\"></script>",
            "<script>window.SF_DIAG=$info;</script>\n<script>${read("diag.js")}</script>",
        )
    }
}
