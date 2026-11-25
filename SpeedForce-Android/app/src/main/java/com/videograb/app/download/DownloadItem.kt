package com.videograb.app.download

import com.videograb.app.engine.RequestContext
import org.json.JSONObject

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELED }

data class DownloadItem(
    val id: String,
    val url: String,
    val title: String,
    val format: String,
    val formatLabel: String,
    val audioOnly: Boolean,
    /** Which video on the page (1 = first) when a page holds several. */
    val entryIndex: Int = 1,
    val ctx: RequestContext = RequestContext(),
    val status: Status = Status.QUEUED,
    val percent: Float = 0f,
    val speed: String = "",
    val eta: String = "",
    val sizeText: String = "",
    val stage: String = "",
    val error: String = "",
    val savedUri: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    /** This server didn't work with several connections at once: one is used. */
    val singleConnection: Boolean = false,
    /** A plain file (document, app, archive...) fetched directly, not a video found by yt-dlp. */
    val direct: Boolean = false,
) {
    val isActive get() = status == Status.QUEUED || status == Status.RUNNING

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("url", url); put("title", title); put("format", format)
        put("formatLabel", formatLabel); put("audioOnly", audioOnly); put("entry", entryIndex)
        put("status", status.name); put("percent", percent.toDouble()); put("sizeText", sizeText)
        put("error", error); put("savedUri", savedUri); put("createdAt", createdAt); put("single", singleConnection); put("direct", direct)
        put("referer", ctx.referer ?: ""); put("ua", ctx.userAgent ?: ""); put("origin", ctx.origin ?: "")
        put("cookies", JSONObject(ctx.cookies as Map<*, *>))
        put("lang", ctx.acceptLanguage ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject): DownloadItem {
            val cj = o.optJSONObject("cookies")
            val cookies = HashMap<String, String>()
            cj?.keys()?.forEach { k -> cookies[k] = cj.optString(k) }
            var st = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.FAILED)
            // After the app restarts, unfinished downloads wait for the user to press Resume
            if (st == Status.RUNNING || st == Status.QUEUED) st = Status.PAUSED
            return DownloadItem(
                id = o.getString("id"), url = o.getString("url"), title = o.optString("title"),
                format = o.optString("format", "b"), formatLabel = o.optString("formatLabel"),
                audioOnly = o.optBoolean("audioOnly"), entryIndex = o.optInt("entry", 1),
                ctx = RequestContext(
                    o.optString("referer").ifEmpty { null }, o.optString("ua").ifEmpty { null },
                    o.optString("origin").ifEmpty { null }, cookies,
                    o.optString("lang").ifEmpty { null },
                ),
                status = st, percent = o.optDouble("percent", 0.0).toFloat(), sizeText = o.optString("sizeText"),
                error = o.optString("error"), savedUri = o.optString("savedUri"), createdAt = o.optLong("createdAt"),
                singleConnection = o.optBoolean("single"), direct = o.optBoolean("direct"),
            )
        }
    }
}
