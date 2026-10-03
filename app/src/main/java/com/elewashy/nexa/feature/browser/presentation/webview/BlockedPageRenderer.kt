package com.elewashy.nexa.feature.browser.presentation.webview

import android.content.Context
import android.webkit.WebResourceResponse
import com.elewashy.nexa.R
import java.io.ByteArrayInputStream

/**
 * Renders the page shown instead of a top-level document blocked by a
 * `$document` / strict hostname filter (uBO's "strict blocking" page): it
 * names the URL and the filter, and offers to go back or proceed once.
 */
class BlockedPageRenderer(private val context: Context) {

    fun render(url: String, filter: String?, proceedUrl: String): WebResourceResponse {
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            append("<meta name=\"color-scheme\" content=\"light dark\">")
            append("<title>").append(escape(context.getString(R.string.adblock_page_blocked_title))).append("</title>")
            append("<style>")
            append("body{font-family:system-ui,sans-serif;margin:0;padding:32px 24px;line-height:1.5}")
            append("main{max-width:560px;margin:0 auto}h1{font-size:1.4em;margin:0 0 12px}")
            append("code{display:block;padding:10px 12px;border-radius:8px;background:rgba(127,127,127,.15);")
            append("word-break:break-all;font-size:.85em;margin:6px 0 16px}")
            append(".actions{display:flex;gap:12px;flex-wrap:wrap;margin-top:24px}")
            append("a{display:inline-block;padding:10px 18px;border-radius:20px;text-decoration:none;font-weight:600}")
            append(".back{background:#1a73e8;color:#fff}.proceed{border:1px solid rgba(127,127,127,.5);color:inherit}")
            append("</style></head><body><main>")
            append("<h1>").append(escape(context.getString(R.string.adblock_page_blocked_title))).append("</h1>")
            append("<p>").append(escape(context.getString(R.string.adblock_page_blocked_message))).append("</p>")
            append("<code>").append(escape(url)).append("</code>")
            if (!filter.isNullOrEmpty()) {
                append("<p>").append(escape(context.getString(R.string.adblock_page_blocked_filter))).append("</p>")
                append("<code>").append(escape(filter)).append("</code>")
            }
            append("<div class=\"actions\">")
            append("<a class=\"back\" href=\"").append(WebViewContentBlocker.BACK_URL).append("\">")
                .append(escape(context.getString(R.string.adblock_page_blocked_back))).append("</a>")
            append("<a class=\"proceed\" href=\"").append(escape(proceedUrl)).append("\">")
                .append(escape(context.getString(R.string.adblock_page_blocked_proceed))).append("</a>")
            append("</div></main></body></html>")
        }
        return WebResourceResponse(
            "text/html", "utf-8", 200, "OK",
            mapOf("Cache-Control" to "no-store", "Content-Security-Policy" to "script-src 'none'"),
            ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
        )
    }

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
}
