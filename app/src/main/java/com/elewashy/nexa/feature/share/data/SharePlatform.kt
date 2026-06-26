package com.elewashy.nexa.feature.share.data

import androidx.core.net.toUri

enum class SharePlatform(val id: String) {
    YOUTUBE("youtube"),
    FACEBOOK("facebook"),
    INSTAGRAM("instagram"),
    THREADS("threads"),
    TIKTOK("tiktok"),
    TWITTER("twitter"),
    VIDEO("video")
}

object SharePlatformDetector {
    private val urlRegex = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    fun extractFirstUrl(text: String): String? {
        return urlRegex.find(text)
            ?.value
            ?.trimEnd('.', ',', ';', ':', ')', ']', '}', '>', '"', '\'')
    }

    fun detect(url: String?): SharePlatform {
        val host = url?.hostOrNull() ?: return SharePlatform.VIDEO

        return when {
            host.matchesHost("youtube.com") || host.matchesHost("youtu.be") -> SharePlatform.YOUTUBE
            host.matchesHost("facebook.com") || host.matchesHost("fb.watch") || host.matchesHost("fb.com") -> SharePlatform.FACEBOOK
            host.matchesHost("instagram.com") -> SharePlatform.INSTAGRAM
            host.matchesHost("threads.net") || host.matchesHost("threads.com") -> SharePlatform.THREADS
            host.matchesHost("tiktok.com") -> SharePlatform.TIKTOK
            host.matchesHost("twitter.com") || host.matchesHost("x.com") -> SharePlatform.TWITTER
            else -> SharePlatform.VIDEO
        }
    }

    private fun String.hostOrNull(): String? {
        return try {
            toUri().host
                ?.removePrefix("www.")
                ?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

    private fun String.matchesHost(domain: String): Boolean {
        return this == domain || endsWith(".$domain")
    }
}
