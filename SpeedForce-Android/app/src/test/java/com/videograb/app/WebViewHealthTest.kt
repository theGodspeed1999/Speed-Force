package com.videograb.app

import com.videograb.app.browser.WebViewHealth
import com.videograb.app.util.VersionUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WebViewHealthTest {
    // Google's real rollout on 2026-11-10: 158 had reached 0.25% of phones, 156 most of them
    private val today = """{"releases":[
        {"version":"156.0.8078.47","fraction":0.78008},
        {"version":"156.0.8078.50","fraction":0.09751},
        {"version":"157.0.8103.38","fraction":0.09751},
        {"version":"157.0.8103.40","fraction":0.00995},
        {"version":"157.0.8103.50","fraction":0.00995},
        {"version":"157.0.8103.39","fraction":0.0025},
        {"version":"158.0.8127.25","fraction":0.0025}]}"""

    @Test fun servedIsWhatMostPhonesGet() {
        assertEquals("156.0.8078.50", WebViewHealth.servedVersion(today))
        assertEquals("158.0.8127.25", WebViewHealth.newestVersion(today))
    }

    @Test fun phoneOn156IsNotWarned() {
        val served = WebViewHealth.servedVersion(today)
        assertFalse(VersionUtils.isOutdated("156.0.8078.47", served))
    }

    @Test fun versionOrder() {
        assertEquals(1, VersionUtils.comparator.compare("157.0.8103.38", "156.0.8078.50"))
        assertEquals(-1, VersionUtils.comparator.compare("156.0.8078.47", "156.0.8078.50"))
    }
}
