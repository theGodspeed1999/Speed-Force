package com.videograb.app.browser

import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.videograb.app.Prefs

/** Security settings shared by every WebView in the app (browser tabs and the ad billboard). */
object WebSecurity {

    fun harden(wv: WebView) {
        val s = wv.settings
        // Web pages may not read files on the phone or other apps' content
        s.allowFileAccess = false
        s.allowContentAccess = false
        // No location access for websites
        s.setGeolocationEnabled(false)
        // Never load insecure (http) scripts/images inside secure (https) pages
        s.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        s.saveFormData = false
        // Google Safe Browsing: warns about phishing and malware sites
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
            WebSettingsCompat.setSafeBrowsingEnabled(s, true)
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, Prefs.thirdPartyCookies)
    }

    /** Websites never get camera, microphone, MIDI or protected-media (DRM ID) access. */
    fun deny(request: PermissionRequest) = request.deny()

    fun denyLocation(origin: String?, callback: GeolocationPermissions.Callback?) {
        callback?.invoke(origin, false, false)
    }
}
