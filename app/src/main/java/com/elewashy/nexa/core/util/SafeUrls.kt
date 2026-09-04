package com.elewashy.nexa.core.util

import android.webkit.URLUtil
import androidx.core.net.toUri

/**
 * URL safety gate for any URL the app loads on its own initiative
 * (startup, restore, bookmarks, new tabs). Only plain http(s) pages pass —
 * a persisted javascript:, data: or file: URL must never load automatically.
 */
object SafeUrls {

    fun isSafeLoadableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!URLUtil.isValidUrl(url)) return false
        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase() ?: return false
        return uri.isHierarchical && !uri.host.isNullOrBlank() &&
            (scheme == "http" || scheme == "https")
    }
}
