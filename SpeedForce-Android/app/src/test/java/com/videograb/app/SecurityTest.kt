package com.videograb.app

import com.videograb.app.util.UrlUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityTest {

    @Test fun stripsTrackingCodes() {
        assertEquals("https://s.com/v?id=5", UrlUtils.stripTracking("https://s.com/v?utm_source=x&id=5&fbclid=abc"))
        assertEquals("https://s.com/v", UrlUtils.stripTracking("https://s.com/v?utm_medium=a&gclid=b"))
        assertEquals("https://s.com/v?a=1#t=30", UrlUtils.stripTracking("https://s.com/v?a=1&UTM_CAMPAIGN=z#t=30"))
        assertEquals("https://s.com/v?q=a+b&page=2", UrlUtils.stripTracking("https://s.com/v?q=a+b&page=2"))
        assertEquals("https://s.com/v", UrlUtils.stripTracking("https://s.com/v"))
    }

    @Test fun httpsUpgrade() {
        assertEquals("https://site.com/a?b=1", UrlUtils.upgradeToHttps("http://site.com/a?b=1"))
        assertEquals("https://site.com/", UrlUtils.upgradeToHttps("https://site.com/"))
        assertEquals("http://192.168.0.1/admin", UrlUtils.upgradeToHttps("http://192.168.0.1/admin"))
        assertEquals("http://localhost:8080/", UrlUtils.upgradeToHttps("http://localhost:8080/"))
        assertEquals("about:blank", UrlUtils.upgradeToHttps("about:blank"))
    }

    @Test fun localHosts() {
        assertTrue(UrlUtils.isLocalHost("10.0.0.5"))
        assertTrue(UrlUtils.isLocalHost("172.20.1.1"))
        assertTrue(UrlUtils.isLocalHost("printer.local"))
        assertFalse(UrlUtils.isLocalHost("172.40.1.1"))
        assertFalse(UrlUtils.isLocalHost("example.com"))
        assertFalse(UrlUtils.isLocalHost("8.8.8.8"))
    }

    @Test fun webEngineVersionCheck() {
        val v = com.videograb.app.util.VersionUtils
        assertEquals(157, v.major("157.0.8103.30"))
        assertEquals(33, v.behind("124.0.6367.219", "157.0.8103.30"))
        assertTrue(v.isOutdated("124.0.6367.219", "157.0.8103.30"))
        assertFalse(v.isOutdated("156.0.8078.79", "157.0.8103.30")) // one behind = normal rollout delay
        assertFalse(v.isOutdated("157.0.8103.30", "157.0.8103.30"))
        assertFalse(v.isOutdated("unknown", "157.0.8103.30"))
        assertFalse(v.isOutdated("124.0.1", null)) // offline: no false alarm
    }

    @Test fun domainHoppingAdLoaders() {
        assertTrue(UrlUtils.isRotatingAdLoader("https://vitting.org/22/0c68eb516d80d3a1ce9486707ffc5f64"))
        assertTrue(UrlUtils.isRotatingAdLoader("https://ethnicexpressions.org/4/bd8639e509742d33f8ffb85eb3be837e?var=1"))
        assertFalse(UrlUtils.isRotatingAdLoader("https://videosite.com/video/id=ab30145418312366"))
        assertFalse(UrlUtils.isRotatingAdLoader("https://site.com/22/not-a-hash"))
        assertFalse(UrlUtils.isRotatingAdLoader("https://cdn.site.com/videos/0c68eb516d80d3a1ce9486707ffc5f64.mp4"))
    }

    @Test fun fakeChatAdLoaders() {
        assertTrue(UrlUtils.isRotatingAdLoader("https://29548.gomatinequisheoi.com/4/js/253804"))
        assertTrue(UrlUtils.isRotatingAdLoader("https://29892691-29548-ex.supechcopa.com/iCNDDIU3PAvoZtczvVjALS5Il9k7"))
        assertTrue(UrlUtils.isRotatingAdLoader("https://alaphoid.com/14/64fcd576b2001131a9761227e458c4d3"))
        assertFalse(UrlUtils.isRotatingAdLoader("https://videosite.b-cdn.net//static/js/main.min.js?v=8.3"))
        assertFalse(UrlUtils.isRotatingAdLoader("https://cdn.site.com/2024/js/app.js"))
        assertFalse(UrlUtils.isRotatingAdLoader("https://www.site.com/4/js/253804"))
    }

    @Test fun searchResultsPages() {
        assertEquals("cat videos", UrlUtils.searchQuery("https://duckduckgo.com/?q=cat+videos&ia=web"))
        assertEquals("a&b", UrlUtils.searchQuery("https://www.google.com/search?client=x&q=a%26b"))
        assertEquals(null, UrlUtils.searchQuery("https://duckduckgo.com/"))
        assertEquals(null, UrlUtils.searchQuery("https://site.com/find?q=cat"))
    }

    @Test fun scamMessageBoxes() {
        assertTrue(UrlUtils.isScamMessage("WARNING! Your phone is infected with 4 viruses!"))
        assertTrue(UrlUtils.isScamMessage("Congratulations! You have won an iPhone 16"))
        assertTrue(UrlUtils.isScamMessage("Your battery is damaged. Clean now"))
        assertFalse(UrlUtils.isScamMessage("Please enter a valid email address"))
        assertFalse(UrlUtils.isScamMessage("Are you 18 years or older?"))
        assertFalse(UrlUtils.isScamMessage(null))
    }

    @Test fun preRollAdRequestsAreRecognised() {
        assertTrue(UrlUtils.isVastRequest("https://pubads.g.doubleclick.net/gampad/ads?iu=/1/x&output=vast&env=vp"))
        assertTrue(UrlUtils.isVastRequest("https://s.magsrv.com/v1/vast.php?idzone=123"))
        assertTrue(UrlUtils.isVastRequest("https://ads.net/serve/vmap?id=1"))
        assertTrue(UrlUtils.isVastRequest("https://ads.net/tag/preroll.xml"))
        assertFalse(UrlUtils.isVastRequest("https://ads.net/banner.js"))
        assertFalse(UrlUtils.isVastRequest("https://ads.net/creative/300x250.png"))
    }

    @Test fun tabUnderSiteCheck() {
        assertEquals("site.com", UrlUtils.siteOf("m.site.com"))
        assertEquals("example.co.uk", UrlUtils.siteOf("cdn.example.co.uk"))
        assertTrue(UrlUtils.sameSite("https://www.site.com/v/1", "https://m.site.com/v/2"))
        assertFalse(UrlUtils.sameSite("https://site.com/v/1", "https://casino-win.top/?p=1"))
        assertFalse(UrlUtils.sameSite("https://a.co.uk/", "https://b.co.uk/"))
        assertFalse(UrlUtils.sameSite(null, "https://site.com/"))
    }

    @Test fun previewClipsGoLast() {
        assertEquals("93818767_720p.mp4", UrlUtils.fileName("https://videosite.com/v/93818767_720p.mp4?t=1"))
        assertTrue(UrlUtils.looksLikePreviewClip("https://videosite.com/v/preview_93818767.mp4"))
        assertTrue(UrlUtils.looksLikePreviewClip("https://videosite.com/static/site.webm"))
        assertFalse(UrlUtils.looksLikePreviewClip("https://videosite.com/v/93818767_720p.mp4"))
        assertFalse(UrlUtils.looksLikePreviewClip("https://cdn.site.com/hls/master.m3u8"))
    }

    @Test fun onlyMediaFilesAreSaved() {
        assertTrue(UrlUtils.isMediaExtension("mp4"))
        assertTrue(UrlUtils.isMediaExtension("MKV"))
        assertTrue(UrlUtils.isMediaExtension("m4a"))
        assertFalse(UrlUtils.isMediaExtension("apk"))
        assertFalse(UrlUtils.isMediaExtension("exe"))
        assertFalse(UrlUtils.isMediaExtension("html"))
        assertFalse(UrlUtils.isMediaExtension(null))
    }
}
