package com.videograb.app

import com.videograb.app.util.StreamKind
import com.videograb.app.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlUtilsTest {

    @Test fun addressBarInput() {
        assertEquals("https://example.com", UrlUtils.toUrlOrSearch("example.com"))
        assertEquals("https://example.com/a/b?x=1", UrlUtils.toUrlOrSearch("  example.com/a/b?x=1 "))
        assertEquals("http://site.org/v", UrlUtils.toUrlOrSearch("http://site.org/v"))
        assertEquals("https://duckduckgo.com/?q=big+buck+bunny", UrlUtils.toUrlOrSearch("big buck bunny"))
        assertEquals("https://duckduckgo.com/?q=hello", UrlUtils.toUrlOrSearch("hello"))
        assertEquals("", UrlUtils.toUrlOrSearch("   "))
    }

    @Test fun extractSharedLink() {
        assertEquals("https://site.com/watch?v=1", UrlUtils.extractUrl("Look at this! https://site.com/watch?v=1"))
        assertEquals("https://a.b/c", UrlUtils.extractUrl("https://a.b/c."))
        assertNull(UrlUtils.extractUrl("no link here"))
        assertNull(UrlUtils.extractUrl(null))
    }

    @Test fun hostAndOrigin() {
        assertEquals("example.com", UrlUtils.host("https://www.Example.com/path"))
        assertEquals("cdn.x.net", UrlUtils.host("https://cdn.x.net:8443/a.m3u8?t=1"))
        assertEquals("https://cdn.x.net:8443", UrlUtils.origin("https://cdn.x.net:8443/a.m3u8"))
        assertEquals("https://x.com", UrlUtils.origin("https://x.com/page"))
    }

    @Test fun adHostMatching() {
        val list = setOf("exoclick.com", "doubleclick.net")
        assertTrue(UrlUtils.isBlockedHost("exoclick.com", list))
        assertTrue(UrlUtils.isBlockedHost("syndication.exoclick.com", list))
        assertTrue(UrlUtils.isBlockedHost("a.b.doubleclick.net", list))
        assertFalse(UrlUtils.isBlockedHost("notexoclick.com", list))
        assertFalse(UrlUtils.isBlockedHost("example.com", list))
        assertFalse(UrlUtils.isBlockedHost(null, list))
    }

    @Test fun streamClassification() {
        assertEquals(StreamKind.HLS, UrlUtils.classifyStream("https://cdn.site/hls/master.m3u8?token=abc"))
        assertEquals(StreamKind.DASH, UrlUtils.classifyStream("https://cdn.site/manifest.mpd"))
        assertEquals(StreamKind.MP4, UrlUtils.classifyStream("https://cdn.site/video_720.mp4?e=1#t=5"))
        assertEquals(StreamKind.WEBM, UrlUtils.classifyStream("http://x/y.webm"))
        assertNull(UrlUtils.classifyStream("https://cdn.site/seg-001.ts"))
        assertNull(UrlUtils.classifyStream("https://cdn.site/chunk.m4s"))
        assertNull(UrlUtils.classifyStream("https://site/page.html?file=a.mp4"))
        assertNull(UrlUtils.classifyStream("blob:https://site/123"))
    }

    @Test fun dedupeAndShortName() {
        assertEquals(
            UrlUtils.dedupeKey("https://cdn/x/master.m3u8?t=1"),
            UrlUtils.dedupeKey("https://CDN/x/master.m3u8?t=2"),
        )
        assertEquals("cdn.site/…/master.m3u8", UrlUtils.shortName("https://cdn.site/a/b/master.m3u8?t=1"))
    }

    @Test fun cookieFile() {
        val txt = UrlUtils.netscapeCookies("site.com", "a=1; session=xyz=2 ; bad; =x")
        val lines = txt.trim().lines()
        assertEquals(2, lines.size)
        assertEquals(".site.com\tTRUE\t/\tFALSE\t2147483647\ta\t1", lines[0])
        assertEquals(".site.com\tTRUE\t/\tFALSE\t2147483647\tsession\txyz=2", lines[1])
        assertEquals("", UrlUtils.netscapeCookies("site.com", null))
    }
}
