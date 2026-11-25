package com.videograb.app

import com.videograb.app.browser.FilterEngine
import com.videograb.app.engine.StreamProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilterEngineTest {
    private fun engine(vararg rules: String) = FilterEngine().apply { add(rules.toList()) }
    private val any = FilterEngine.ALL_SUB

    @Test
    fun plainHostRuleCoversSubdomains() {
        val e = engine("||ads.example.com^")
        assertTrue(e.blocks("https://ads.example.com/a.js", "https://site.com/", any))
        assertTrue(e.blocks("https://x.ads.example.com/a.js", "https://site.com/", any))
        assertFalse(e.blocks("https://example.com/a.js", "https://site.com/", any))
        assertFalse(e.blocks("https://notads.example.com.evil/a.js", "https://site.com/", any))
    }

    @Test
    fun pathWildcardsAndSeparators() {
        val e = engine("/banner/*/img^", "||videosite.com/092-", "&ad_zone=")
        assertTrue(e.blocks("https://a.com/banner/x/y/img?1", null, any))
        assertFalse(e.blocks("https://a.com/banner/x/imgs", null, any))
        assertTrue(e.blocks("https://videosite.com/092-abc.js", "https://videosite.com/", any))
        assertFalse(e.blocks("https://videosite.com/videos/1", "https://videosite.com/", any))
        assertTrue(e.blocks("https://a.com/x?a=1&ad_zone=3", null, any))
    }

    @Test
    fun options() {
        val e = engine("||cdn.com/ad\$script,third-party", "||x.com/y\$domain=a.com|~b.a.com", "||z.com/p\$image")
        assertTrue(e.blocks("https://cdn.com/ad.js", "https://site.com/", FilterEngine.SCRIPT))
        assertFalse(e.blocks("https://cdn.com/ad.js", "https://cdn.com/", FilterEngine.SCRIPT))
        assertFalse(e.blocks("https://cdn.com/ad.js", "https://site.com/", FilterEngine.IMAGE))
        assertTrue(e.blocks("https://x.com/y", "https://a.com/", any))
        assertFalse(e.blocks("https://x.com/y", "https://b.a.com/", any))
        assertFalse(e.blocks("https://x.com/y", "https://other.com/", any))
        assertTrue(e.blocks("https://z.com/p.png", null, FilterEngine.IMAGE))
    }

    @Test
    fun exceptionsWin() {
        val e = engine("||ads.com^", "@@||ads.com/needed.js\$script", "||block.me^\$important", "@@||block.me^")
        assertTrue(e.blocks("https://ads.com/x.js", "https://s.com/", FilterEngine.SCRIPT))
        assertFalse(e.blocks("https://ads.com/needed.js", "https://s.com/", FilterEngine.SCRIPT))
        assertTrue(e.blocks("https://block.me/", "https://s.com/", any))
    }

    @Test
    fun badfilterCancelsARule() {
        val e = engine("||gone.com^", "||gone.com^\$badfilter", "||kept.com^")
        assertFalse(e.blocks("https://gone.com/", null, any))
        assertTrue(e.blocks("https://kept.com/", null, any))
    }

    @Test
    fun rulesWeCannotFollowAreSkipped() {
        val e = engine("||a.com^\$removeparam=x", "||b.com^\$csp=script-src", "||c.com^\$redirect-rule=noop.js")
        assertFalse(e.blocks("https://a.com/", null, any))
        assertFalse(e.blocks("https://b.com/", null, any))
        assertFalse(e.blocks("https://c.com/", null, any))
    }

    @Test
    fun hidingRules() {
        val e = engine(
            "##.ad-box", "###banner", "##div[data-ad]", "site.com##.only-here", "site.*##.any-tld",
            "site.com#@#.ad-box", "x.com##.t:has-text(Ad)", "##+js(nowoif)", "##.a > .b",
        )
        val on = { h: String, c: List<String>, i: List<String> -> e.selectorsFor(h, "https://$h/", c, i, true) }
        assertTrue(".ad-box" in on("other.com", listOf("ad-box"), emptyList()))
        assertFalse(".ad-box" in on("other.com", emptyList(), emptyList()))
        assertTrue("#banner" in on("other.com", emptyList(), listOf("banner")))
        assertTrue("div[data-ad]" in on("other.com", emptyList(), emptyList()))
        assertTrue(".only-here" in on("www.site.com", emptyList(), emptyList()))
        assertTrue(".any-tld" in on("site.org", emptyList(), emptyList()))
        assertFalse(".ad-box" in on("site.com", listOf("ad-box"), emptyList()))
        assertTrue(".a > .b" in on("o.com", listOf("a"), emptyList()))
        assertTrue(on("x.com", emptyList(), emptyList()).none { it.contains("has-text") || it.contains("js(") })
    }

    @Test
    fun requestTypes() {
        assertEquals(FilterEngine.DOCUMENT, FilterEngine.typeOf("https://a/b", "text/html", true))
        assertEquals(FilterEngine.FRAME, FilterEngine.typeOf("https://a/b", "text/html,*/*", false))
        assertEquals(FilterEngine.IMAGE, FilterEngine.typeOf("https://a/b", "image/avif,image/webp,*/*", false))
        assertEquals(FilterEngine.SCRIPT, FilterEngine.typeOf("https://a/b.js?1", "*/*", false))
    }

    /** The real lists: every page request goes through this, so it must be quick and catch real ads. */
    @Test
    fun realLists() {
        val dir = File("src/main/assets/filters")
        val e = FilterEngine()
        val t0 = System.currentTimeMillis()
        dir.listFiles()!!.forEach { e.add(it.readLines()) }
        val loadMs = System.currentTimeMillis() - t0
        val ads = listOf(
            "https://creative.stripchat.com/x/y.mp4", "https://securepubads.g.doubleclick.net/tag/js/gpt.js",
            "https://www.googletagservices.com/tag/js/gpt.js", "https://cdn.taboola.com/libtrc/x/loader.js", "https://a.magsrv.com/ad-provider.js",
            "https://syndication.exoclick.com/splash.php", "https://ads.trafficjunky.net/ads",
        )
        for (u in ads) assertTrue(u, e.blocks(u, "https://videosite.com/videos/1", FilterEngine.SCRIPT or FilterEngine.XHR or FilterEngine.OTHER))
        val fine = listOf(
            "https://www.google.com/search?q=x", "https://cdn.jsdelivr.net/npm/hls.js@1", "https://www.wikipedia.org/static/x.js",
            "https://i.ytimg.com/vi/x/hq.jpg", "https://www.youtube.com/s/player/base.js",
        )
        for (u in fine) assertFalse(u, e.blocks(u, "https://news-site.org/", FilterEngine.SCRIPT or FilterEngine.IMAGE))
        val t1 = System.nanoTime()
        repeat(200) { i -> ads.forEach { e.blocks("$it?$i", "https://videosite.com/", FilterEngine.SCRIPT) }; fine.forEach { e.blocks("$it&$i", "https://s.org/", FilterEngine.SCRIPT) } }
        val perRequestUs = (System.nanoTime() - t1) / 1000 / (200 * (ads.size + fine.size))
        println("FILTER rules=${e.ruleCount} load=${loadMs}ms perRequest=${perRequestUs}us base=${e.selectorsFor("example.com", "https://example.com/", emptyList(), emptyList(), true).size} selectors")
        assertTrue("too slow: $perRequestUs us", perRequestUs < 5000)
    }

    @Test
    fun hlsPlaylists() {
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\n360.m3u8\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2000000,BANDWIDTH=2800000,RESOLUTION=1280x720\n720.m3u8\n"
        val p = StreamProbe.parsePlaylist(master)!!
        assertEquals(listOf(360, 720), p.variants.map { it.height })
        assertEquals(listOf(800000L, 2800000L), p.variants.map { it.bandwidth })
        val media = StreamProbe.parsePlaylist("#EXTM3U\n#EXTINF:10.0,\na.ts\n#EXTINF:5.5,\nb.ts\n#EXT-X-ENDLIST")!!
        assertEquals(15, media.durationSec)
        assertEquals(2, media.segments)
        assertEquals("a.ts", media.firstSegment)
        assertEquals(null, StreamProbe.parsePlaylist("<html>"))
        val rows = StreamProbe.options(StreamProbe.Result(p.variants.map { StreamProbe.Quality(it.height, it.bandwidth) }, 100, 0), 0, true)
        assertEquals(listOf("Best quality", "720p", "360p", "Audio only"), rows.map { it.label })
        assertTrue(rows[1].detail, rows[1].detail.startsWith("MP4 (from HLS) · ~33"))
    }
}
