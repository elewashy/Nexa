package com.elewashy.nexa.feature.browser.presentation.webview

import android.os.Build
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
/**
 * WebViewConfigurator — Centralizes WebView settings configuration.
 *
 * Applies performance, security, and mobile-specific optimizations
 * in a single deterministic pass.  Stateless utility — call [configure]
 * once from the `AndroidView` factory in `BrowserActivity`.
 */
object WebViewConfigurator {

    /**
     * Applies all WebView settings in a single pass.
     *
     * Ordering:
     *  1. Core feature flags (JS, DOM storage)
     *  2. Cache / network
     *  3. Zoom / viewport
     *  4. Rendering
     *  5. Security hardening
     *  6. Mobile UX tweaks
     *  7. Scroll / overscroll behaviour
     */
    fun configure(webView: WebView) {
        webView.settings.apply {
            // ── 1. Core ────────────────────────────────────────────
            javaScriptEnabled = true
            domStorageEnabled = true

            // ── 2. Cache / network ─────────────────────────────────
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false

            // ── 3. Zoom / viewport ─────────────────────────────────
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false

            // ── 4. Rendering ───────────────────────────────────────
            blockNetworkImage = false
            mediaPlaybackRequiresUserGesture = false
            loadsImagesAutomatically = true
            // Note: setRenderPriority(HIGH) removed — deprecated since API 18, no-op on modern devices.

            // ── 5. Security ────────────────────────────────────────
            javaScriptCanOpenWindowsAutomatically = false
            safeBrowsingEnabled = true

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                allowUniversalAccessFromFileURLs = false
            }
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

            // Stealth: remove the "; wv" WebView marker from user-agent.
            // Uses a targeted replace to avoid mangling other occurrences.
            userAgentString = userAgentString?.replace("; wv", "")?.replace(Regex("Version/\\d+\\.\\d+\\s?"), "")

            // ── 6. Mobile UX ───────────────────────────────────────
            setNeedInitialFocus(false)
            setSupportMultipleWindows(false)
            setGeolocationEnabled(false)
            textZoom = 100
            minimumFontSize = 8
            minimumLogicalFontSize = 8
        }

        // ── 7. Scroll behaviour ────────────────────────────────
        webView.overScrollMode = View.OVER_SCROLL_NEVER
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = true

        // ── 8. Process Priority ────────────────────────────────
        // Keeps the WebView renderer process from being deprioritized or killed
        // while the app is in the foreground, ensuring smooth transitions
        // after periods of idleness on RAM-constrained devices.
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_BOUND, false)

        // Note: setLayerType(LAYER_TYPE_HARDWARE) removed — WebView already
        // uses hardware acceleration when the window has it enabled (default
        // since API 14). Explicitly setting it forces an unnecessary offscreen
        // buffer that doubles VRAM usage and prevents partial invalidation.
    }
}
