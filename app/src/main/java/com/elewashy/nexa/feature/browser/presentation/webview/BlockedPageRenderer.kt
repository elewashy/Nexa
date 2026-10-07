package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebResourceResponse
import androidx.core.os.ConfigurationCompat
import com.elewashy.nexa.R
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * Renders the page shown instead of a top-level document blocked by a `$document` / strict
 * hostname filter (uBO's "strict blocking" page).
 *
 * Laid out like Chrome's interstitials, in Material 3 colors: an illustration, a heading, the
 * blocked site, why it was blocked, and "Go back" as the obvious way out. The address, the
 * matching filter and "Proceed anyway" sit under a collapsed Details section, so continuing to a
 * blocked page is a deliberate choice. The page follows the app's light/dark theme, uses the
 * wallpaper accent on Android 12+, respects the UI language and its text direction, and runs no
 * script at all (its Content-Security-Policy forbids every script, frame and remote resource).
 *
 * Safe to call from WebView's IO thread: it only reads resources.
 */
class BlockedPageRenderer(private val context: Context) {

    private val palette: Palette by lazy { Palette.from(context) }

    fun render(url: String, filter: String?, proceedUrl: String): WebResourceResponse {
        val html = buildHtml(url, filter, proceedUrl)
        return WebResourceResponse(
            "text/html", "utf-8", 200, "OK",
            mapOf(
                "Cache-Control" to "no-store",
                "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
                "X-Content-Type-Options" to "nosniff",
            ),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

    internal fun buildHtml(url: String, filter: String?, proceedUrl: String): String {
        val configuration = context.resources.configuration
        val locale = ConfigurationCompat.getLocales(configuration)[0]
        val rtl = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val host = hostOf(url)
        val title = context.getString(R.string.adblock_page_blocked_title)

        return buildString(4096) {
            append("<!DOCTYPE html><html lang=\"").append(escape(locale?.toLanguageTag() ?: "en"))
            append("\" dir=\"").append(if (rtl) "rtl" else "ltr").append("\"><head>")
            append("<meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta name=\"color-scheme\" content=\"light dark\">")
            append("<title>").append(escape(title)).append("</title>")
            append("<style>").append(palette.css).append(STYLE).append("</style>")
            append("</head><body><main>")

            append("<div class=\"icon\" aria-hidden=\"true\">").append(SHIELD_ICON).append("</div>")
            append("<h1>").append(escape(context.getString(R.string.adblock_page_blocked_heading))).append("</h1>")
            append("<p class=\"host\" dir=\"ltr\">").append(escape(host)).append("</p>")
            append("<p>").append(escape(context.getString(R.string.adblock_page_blocked_message))).append("</p>")

            append("<div class=\"actions\"><a class=\"button\" href=\"")
                .append(WebViewContentBlocker.BACK_URL).append("\">")
                .append(escape(context.getString(R.string.adblock_page_blocked_back)))
                .append("</a></div>")

            append("<details><summary>")
                .append(escape(context.getString(R.string.adblock_page_blocked_details)))
                .append("</summary><div class=\"details\">")
            append("<div class=\"label\">")
                .append(escape(context.getString(R.string.adblock_page_blocked_address)))
                .append("</div><code dir=\"ltr\">").append(escape(url)).append("</code>")
            if (!filter.isNullOrEmpty()) {
                append("<div class=\"label\">")
                    .append(escape(context.getString(R.string.adblock_page_blocked_filter)))
                    .append("</div><code dir=\"ltr\">").append(escape(filter)).append("</code>")
            }
            append("<p class=\"warning\">")
                .append(escape(context.getString(R.string.adblock_page_blocked_proceed_warning)))
                .append("</p>")
            append("<a class=\"link\" href=\"").append(escape(proceedUrl)).append("\">")
                .append(escape(context.getString(R.string.adblock_page_blocked_proceed)))
                .append("</a>")
            append("</div></details>")

            append("</main></body></html>")
        }
    }

    /** Colors as CSS custom properties: Material 3 baseline, accent from the wallpaper on 12+. */
    private class Palette(val css: String) {
        companion object {
            fun from(context: Context): Palette {
                var lightPrimary = "#0b57d0"
                var darkPrimary = "#a8c7fa"
                var darkOnPrimary = "#062e6f"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    lightPrimary = context.colorHex(android.R.color.system_accent1_600)
                    darkPrimary = context.colorHex(android.R.color.system_accent1_200)
                    darkOnPrimary = context.colorHex(android.R.color.system_accent1_800)
                }
                return Palette(
                    ":root{--bg:#fdfcff;--on-bg:#1b1b1f;--muted:#44474e;--surface:#eff0f7;" +
                        "--primary:$lightPrimary;--on-primary:#ffffff;" +
                        "--container:#ffdad6;--on-container:#410002}" +
                        "@media (prefers-color-scheme:dark){:root{--bg:#131316;--on-bg:#e3e2e6;" +
                        "--muted:#c4c6cf;--surface:#1f1f23;--primary:$darkPrimary;" +
                        "--on-primary:$darkOnPrimary;--container:#93000a;--on-container:#ffdad6}}"
                )
            }

            private fun Context.colorHex(resId: Int): String =
                String.format("#%06x", getColor(resId) and 0xFFFFFF)
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: url

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '&' -> append("&amp;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private companion object {
        /** No script, frame, plugin or remote resource; only the page's own inline styles. */
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"

        // Material 3 type scale (headline small, body large, label large) and 48dp touch targets.
        const val STYLE =
            "*{box-sizing:border-box}html{-webkit-text-size-adjust:100%}" +
                "body{margin:0;background:var(--bg);color:var(--on-bg);" +
                "font:16px/24px system-ui,Roboto,sans-serif;letter-spacing:.5px}" +
                "main{max-width:600px;margin:0 auto;padding:40px 24px 32px}" +
                ".icon{width:72px;height:72px;border-radius:24px;background:var(--container);" +
                "color:var(--on-container);display:flex;align-items:center;justify-content:center;" +
                "margin-bottom:24px}.icon svg{width:36px;height:36px;fill:currentColor}" +
                "h1{font-size:24px;line-height:32px;font-weight:400;letter-spacing:0;margin:0 0 12px}" +
                "p{margin:0 0 12px;color:var(--muted)}" +
                ".host{color:var(--on-bg);font-weight:500;overflow-wrap:anywhere;text-align:start}" +
                ".actions{display:flex;justify-content:flex-end;margin:32px 0 8px}" +
                ".button{display:inline-flex;align-items:center;justify-content:center;min-height:48px;" +
                "min-width:96px;padding:0 24px;border-radius:24px;background:var(--primary);" +
                "color:var(--on-primary);text-decoration:none;font-size:14px;line-height:20px;" +
                "font-weight:500;letter-spacing:.1px}" +
                "details{margin-top:8px}summary{display:inline-flex;align-items:center;min-height:48px;" +
                "cursor:pointer;color:var(--primary);font-size:14px;font-weight:500;letter-spacing:.1px;" +
                "list-style:none}summary::-webkit-details-marker{display:none}" +
                ".details{padding-top:4px}" +
                ".label{font-size:12px;line-height:16px;font-weight:500;color:var(--muted);margin:12px 0 4px}" +
                "code{display:block;padding:12px 16px;border-radius:12px;background:var(--surface);" +
                "color:var(--on-bg);overflow-wrap:anywhere;text-align:left;" +
                "font:13px/20px ui-monospace,'Roboto Mono',monospace;letter-spacing:0}" +
                ".warning{margin-top:20px}" +
                ".link{display:inline-flex;align-items:center;min-height:48px;color:var(--primary);" +
                "font-size:14px;font-weight:500;letter-spacing:.1px}" +
                "a:focus-visible,summary:focus-visible{outline:3px solid var(--primary);outline-offset:2px}"

        /** Material "gpp_bad" (shield with a cross), Apache License 2.0. */
        const val SHIELD_ICON =
            "<svg viewBox=\"0 0 24 24\"><path d=\"M12 2 4 5v6.09c0 5.05 3.41 9.76 8 10.91 " +
                "4.59-1.15 8-5.86 8-10.91V5l-8-3zm3.5 12.09-1.41 1.41L12 13.42 9.91 15.5 8.5 " +
                "14.09 10.59 12 8.5 9.91 9.91 8.5 12 10.59l2.09-2.09 1.41 1.41L13.42 12l2.08 " +
                "2.09z\"/></svg>"
    }
}
