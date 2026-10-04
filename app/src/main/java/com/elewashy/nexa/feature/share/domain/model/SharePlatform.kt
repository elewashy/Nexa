package com.elewashy.nexa.feature.share.domain.model

/** A platform the share extractors support; [VIDEO] is any other site (direct media links). */
enum class SharePlatform(val id: String) {
    YOUTUBE("youtube"),
    FACEBOOK("facebook"),
    INSTAGRAM("instagram"),
    THREADS("threads"),
    TIKTOK("tiktok"),
    TWITTER("twitter"),
    VIDEO("video");

    companion object {
        /** Platform serving [host] (case-insensitive, `www.` and subdomains included). */
        fun fromHost(host: String?): SharePlatform {
            val normalized = host?.trim('.')?.lowercase()?.removePrefix("www.") ?: return VIDEO
            fun matches(domain: String) = normalized == domain || normalized.endsWith(".$domain")
            return when {
                matches("youtube.com") || matches("youtu.be") -> YOUTUBE
                matches("facebook.com") || matches("fb.watch") || matches("fb.com") -> FACEBOOK
                matches("instagram.com") -> INSTAGRAM
                matches("threads.net") || matches("threads.com") -> THREADS
                matches("tiktok.com") -> TIKTOK
                matches("twitter.com") || matches("x.com") -> TWITTER
                else -> VIDEO
            }
        }
    }
}
