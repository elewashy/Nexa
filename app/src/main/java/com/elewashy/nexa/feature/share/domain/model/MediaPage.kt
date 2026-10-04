package com.elewashy.nexa.feature.share.domain.model

/**
 * A browser page that shows one specific piece of content on a supported
 * platform (a video, reel, post or tweet) — as opposed to feeds, profiles,
 * search results and other pages that the share extractors cannot handle.
 *
 * [contentId] is the platform's own identifier (tweet ID, post shortcode,
 * video ID). [contentKey] identifies the content independently of tracking
 * parameters, fragments and host aliases (`x.com` vs `twitter.com`), so page
 * reports and URL changes for the same content can be matched reliably.
 */
data class MediaPage(
    val platform: SharePlatform,
    val contentId: String,
    val contentKey: String,
    val presence: MediaPresence,
)

enum class MediaPresence {
    /** The page type always carries media (a YouTube watch page, an Instagram reel, …). */
    GUARANTEED,

    /**
     * The page type may or may not carry media (a tweet, a Threads post, a
     * Facebook post): only the rendered page can tell.
     */
    REQUIRES_PAGE_CHECK,
}
