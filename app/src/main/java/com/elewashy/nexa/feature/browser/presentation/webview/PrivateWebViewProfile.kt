package com.elewashy.nexa.feature.browser.presentation.webview

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Owns the ephemeral WebView profile used by every private tab.
 *
 * Multi-profile support is supplied by the installed Android System WebView,
 * not the OS API level. Callers must keep private browsing unavailable when
 * [isSupported] is false rather than silently sharing the default profile.
 */
@SuppressLint("RequiresFeature") // Every entry point checks MULTI_PROFILE before invoking guarded APIs.
class PrivateWebViewProfile {
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private var sessionPrepared = false

    /** Assigns the isolated profile before the WebView is configured or loaded. */
    fun attach(webView: WebView): Boolean {
        if (!isSupported) return false
        if (!sessionPrepared) {
            deleteProfileIfPossible() // removes data left by a killed private session
            sessionPrepared = true
        }
        WebViewCompat.setProfile(webView, PROFILE_NAME)
        return true
    }

    fun cookieManager(webView: WebView): CookieManager =
        if (isSupported) WebViewCompat.getProfile(webView).cookieManager
        else CookieManager.getInstance()

    /**
     * Ends the current private session. Call only after every WebView associated with the profile
     * is destroyed. A no-op when no private WebView was attached since the last clear, so callers
     * may invoke it on every workspace change without touching the WebView provider.
     */
    fun clearSession() {
        if (!sessionPrepared) return
        deleteProfileIfPossible()
        sessionPrepared = false
    }

    private fun deleteProfileIfPossible() {
        val store = runCatching { ProfileStore.getInstance() }.getOrNull() ?: return
        val deleted = runCatching { store.deleteProfile(PROFILE_NAME) }.getOrDefault(false)
        if (deleted) return

        // Some providers keep the Profile object alive briefly after the last
        // WebView is destroyed. Clear every exposed store even when deletion
        // must be retried at the next session boundary.
        runCatching {
            store.getProfile(PROFILE_NAME)?.let { profile ->
                profile.cookieManager.removeAllCookies(null)
                profile.webStorage.deleteAllData()
                profile.geolocationPermissions.clearAll()
            }
        }
    }

    private companion object {
        const val PROFILE_NAME = "nexa_private"
    }
}
