package com.videograb.app

import com.videograb.app.net.DohResolver
import com.videograb.app.net.LocalProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/** Needs the internet: runs only with VG_NET_TEST=1 (not part of the normal build). */
class RelayNetTest {
    private fun viaRelay(url: String, referer: String? = null): Int {
        val port = LocalProxy.start()
        val c = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))) as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 15) Chrome/156 Mobile Safari/537.36")
        referer?.let { c.setRequestProperty("Referer", it) }
        val code = c.responseCode
        c.inputStream.use { it.readBytes() }
        c.disconnect()
        return code
    }

    @Test fun blockedSiteResolvesAndLoads() {
        assumeTrue(System.getenv("VG_NET_TEST") != null)
        assertTrue(DohResolver.resolve("example.com").isNotEmpty())
        assertEquals(200, viaRelay("https://example.com/", "https://example.org/"))
        assertEquals(200, viaRelay("https://example.org/"))
        assertEquals(200, viaRelay("http://example.com/"))
    }
}
