package com.videograb.app.net

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A tiny relay inside the app (only reachable from this phone, 127.0.0.1). The web pages, the
 * download engine and the app's own requests go through it, and it finds each site's address
 * with [DohResolver] instead of the internet provider's blocked lookup.
 *
 * It does not read or change secure (https) traffic: for those it only connects the two ends
 * ("CONNECT" tunnel), so the encryption stays between the page and the real site.
 */
object LocalProxy {
    private const val TAG = "LocalProxy"
    @Volatile var port = 0
        private set
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "relay").apply { isDaemon = true } }

    @Synchronized
    fun start(): Int {
        if (port != 0) return port
        val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        thread(name = "relay-accept", isDaemon = true) {
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { Log.e(TAG, "accept", e); break }
                pool.execute { handle(s) }
            }
        }
        Log.i(TAG, "relay on 127.0.0.1:$port")
        return port
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 30_000
            val inp = BufferedInputStream(client.getInputStream(), 65536)
            val head = readHead(inp) ?: return client.close()
            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val parts = lines.firstOrNull()?.split(" ") ?: return client.close()
            if (parts.size < 3) return client.close()
            val (method, target, version) = Triple(parts[0], parts[1], parts[2])
            val out = client.getOutputStream()
            if (method.equals("CONNECT", ignoreCase = true)) {
                val (host, p) = hostPort(target, 443)
                upstream = connect(host, p) ?: return fail(client, out)
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                out.flush()
            } else {
                // Plain http: the page sends the full address; the site wants only the path
                val uri = URI(target)
                val host = uri.host ?: return fail(client, out)
                upstream = connect(host, if (uri.port > 0) uri.port else 80) ?: return fail(client, out)
                val path = (uri.rawPath?.ifEmpty { "/" } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
                val sb = StringBuilder("$method $path $version\r\n")
                for (l in lines.drop(1)) {
                    val name = l.substringBefore(':').trim().lowercase()
                    if (name in dropHeaders) continue
                    sb.append(l).append("\r\n")
                }
                // One request per connection keeps the relay simple (the page opens another when needed)
                sb.append("Connection: close\r\n\r\n")
                upstream.getOutputStream().apply { write(sb.toString().toByteArray(Charsets.ISO_8859_1)); flush() }
            }
            client.soTimeout = 0
            pipe(client, inp, upstream)
        } catch (e: Exception) {
            Log.w(TAG, "relay error: $e")
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private val dropHeaders = setOf("proxy-connection", "connection", "keep-alive", "proxy-authorization")

    private fun fail(client: Socket, out: OutputStream) {
        runCatching {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }
        runCatching { client.close() }
    }

    /** Reads up to the empty line that ends the request head. */
    private fun readHead(inp: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var last4 = 0
        while (buf.size() < 65536) {
            val b = inp.read()
            if (b < 0) return null
            buf.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) return buf.toString("ISO-8859-1")
        }
        return null
    }

    private fun hostPort(s: String, def: Int): Pair<String, Int> {
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            val host = s.substring(1, end)
            val p = s.substring(end + 1).removePrefix(":").toIntOrNull() ?: def
            return host to p
        }
        val i = s.lastIndexOf(':')
        return if (i > 0) s.substring(0, i) to (s.substring(i + 1).toIntOrNull() ?: def) else s to def
    }

    private fun connect(host: String, port: Int): Socket? {
        val addrs = DohResolver.resolve(host)
        if (addrs.isEmpty()) Log.w(TAG, "no address found for $host")
        for (a in addrs) {
            try {
                return Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(a, port), 10_000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "connect $host ($a):$port failed: ${e.message}")
            }
        }
        // Last try: the phone's own lookup (it may know an address the encrypted services don't)
        val local = try { InetAddress.getAllByName(host).filter { it !in addrs } } catch (e: Exception) { emptyList() }
        for (a in local) {
            try {
                return Socket().apply { tcpNoDelay = true; keepAlive = true; connect(InetSocketAddress(a, port), 10_000) }
            } catch (e: Exception) {
                Log.w(TAG, "connect $host ($a, phone lookup):$port failed: ${e.message}")
            }
        }
        return null
    }

    /** Copies both directions until either side is done, then closes both. */
    private fun pipe(client: Socket, clientIn: InputStream, upstream: Socket) {
        val down = pool.submit {
            copy(upstream.getInputStream(), client.getOutputStream())
            runCatching { client.shutdownOutput() }
        }
        copy(clientIn, upstream.getOutputStream())
        runCatching { upstream.shutdownOutput() }
        runCatching { down.get() }
        runCatching { client.close() }
        runCatching { upstream.close() }
    }

    private fun copy(i: InputStream, o: OutputStream) {
        val b = ByteArray(65536)
        try {
            while (true) {
                val n = i.read(b)
                if (n < 0) break
                o.write(b, 0, n)
                o.flush()
            }
        } catch (_: Exception) {
        }
    }
}
