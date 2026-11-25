package com.videograb.app.engine

/** Reads yt-dlp's "[download]  42.0% of 10.00MiB at 1.20MiB/s ETA 00:05" lines. */
object ProgressParser {
    data class Progress(val percent: Float?, val total: String?, val speed: String?, val eta: String?)

    private val pct = Regex("""\[download]\s+([\d.]+)%""")
    private val total = Regex("""of\s+~?\s*([\d.]+\s*[KMGT]?i?B)""")
    private val speed = Regex("""at\s+([\d.]+\s*[KMGT]?i?B/s)""")
    private val eta = Regex("""ETA\s+([\d:]+)""")

    // aria2c: "[#2089b0 12MiB/100MiB(12%) CN:8 DL:4.2MiB ETA:20s]"
    private val aria = Regex("""\[#\w+ [\d.]+\w*/([\d.]+\w+)\((\d+)%\)(?: CN:\d+)?(?: DL:([\d.]+\w+))?(?: ETA:(\w+))?""")

    fun parse(line: String): Progress? {
        aria.find(line)?.let { m ->
            val (total, percent, dl, left) = m.destructured
            return Progress(percent.toFloatOrNull(), total, dl.ifEmpty { null }?.let { "$it/s" }, left.ifEmpty { null })
        }
        if (!line.contains("[download]")) return null
        val p = pct.find(line)?.groupValues?.get(1)?.toFloatOrNull()
        val t = total.find(line)?.groupValues?.get(1)?.replace(" ", "")
        val s = speed.find(line)?.groupValues?.get(1)?.replace(" ", "")
        val e = eta.find(line)?.groupValues?.get(1)
        if (p == null && s == null) return null
        return Progress(p, t, s, e)
    }

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble()
        var i = 0
        while (v >= 1024 && i < units.size - 1) {
            v /= 1024; i++
        }
        return if (i <= 1) String.format(java.util.Locale.US, "%.0f %s", v, units[i])
        else String.format(java.util.Locale.US, "%.1f %s", v, units[i])
    }
}
