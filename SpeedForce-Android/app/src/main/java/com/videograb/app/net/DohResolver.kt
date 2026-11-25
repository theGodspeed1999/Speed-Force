package com.videograb.app.net

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Looks up web addresses through Cloudflare (and Google as a spare) over an encrypted HTTPS
 * connection, instead of asking the internet provider. Providers block sites by answering
 * "no address" or a fake address; this way the real address comes back.
 * The servers are reached by their fixed numbers (1.1.1.1 ...), so no lookup is needed to find them.
 */
object DohResolver {
    private const val TAG = "DohResolver"

    private val servers = listOf(
        "https://1.1.1.1/dns-query",
        "https://1.0.0.1/dns-query",
        "https://8.8.8.8/resolve",
    )

    private class Entry(val addrs: List<InetAddress>, val until: Long)
    private val cache = ConcurrentHashMap<String, Entry>()

    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

    fun isIpLiteral(host: String) = ipv4.matches(host) || host.contains(':')

    /** Addresses for [host]; falls back to the phone's normal lookup when the encrypted one fails. */
    fun resolve(host: String): List<InetAddress> {
        val h = host.trim('[', ']').lowercase()
        if (isIpLiteral(h) || h == "localhost") return listOf(InetAddress.getByName(h))
        val now = System.currentTimeMillis()
        cache[h]?.let { if (it.until > now) return it.addrs }
        var ttl = 300
        var found = emptyList<InetAddress>()
        for (type in listOf("A", "AAAA")) {
            val r = query(h, type) ?: continue
            if (r.first.isNotEmpty()) {
                found = r.first; ttl = r.second; break
            }
        }
        if (found.isEmpty()) {
            // Encrypted lookup unavailable: use the phone's own (may be blocked, but better than nothing)
            found = try { InetAddress.getAllByName(h).toList() } catch (e: Exception) { emptyList() }
            ttl = 60
        }
        if (found.isNotEmpty()) cache[h] = Entry(found, now + ttl.coerceIn(30, 3600) * 1000L)
        return found
    }

    /**
     * One question to the encrypted servers: addresses and how long they stay valid (seconds).
     * An empty answer is not taken at once: while a site moves to new servers, one service may
     * already say "no address" while another still has it (seen with some video hosts).
     */
    private fun query(host: String, type: String): Pair<List<InetAddress>, Int>? {
        var empty: Pair<List<InetAddress>, Int>? = null
        for (s in servers) {
            val r = ask(s, host, type) ?: continue
            if (r.first.isNotEmpty()) return r
            empty = r
        }
        return empty
    }

    private fun ask(s: String, host: String, type: String): Pair<List<InetAddress>, Int>? {
        val want = if (type == "A") 1 else 28
        run {
            try {
                val url = URL("$s?name=${URLEncoder.encode(host, "UTF-8")}&type=$type")
                // Never through our own relay: that would ask itself
                val c = url.openConnection(Proxy.NO_PROXY) as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.setRequestProperty("Accept", "application/dns-json")
                try {
                    if (c.responseCode != 200) return null
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val ans = o.optJSONArray("Answer")
                    val list = ArrayList<InetAddress>()
                    var ttl = 3600
                    if (ans != null) for (i in 0 until ans.length()) {
                        val a = ans.getJSONObject(i)
                        if (a.optInt("type") != want) continue
                        val data = a.optString("data")
                        if (!isIpLiteral(data)) continue
                        list.add(InetAddress.getByName(data))
                        ttl = minOf(ttl, a.optInt("TTL", 300))
                    }
                    return list to ttl
                } finally {
                    c.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "lookup of $host at $s failed: ${e.message}")
            }
        }
        return null
    }
}
