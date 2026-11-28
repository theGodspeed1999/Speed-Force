package com.videograb.app.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun versionComparison() {
        assertTrue(AppUpdate.isNewer("v1.5.3", "1.5.2"))
        assertTrue(AppUpdate.isNewer("v1.6.0", "1.5.2"))
        assertTrue(AppUpdate.isNewer("2.0.0", "1.5.2"))
        assertFalse(AppUpdate.isNewer("v1.5.2", "1.5.2"))
        assertFalse(AppUpdate.isNewer("v1.5.1", "1.5.2"))
        assertFalse(AppUpdate.isNewer("", "1.5.2"))
    }
}
