package com.elewashy.nexa.feature.browser.data.resources

/**
 * Remote resources downloaded and cached by [BrowserResourceRepository]:
 * the content-blocking filter lists.
 *
 * The default selection mirrors uBlock Origin's defaults (uBO filters,
 * badware, privacy, quick fixes, unbreak, EasyList, EasyPrivacy, Peter
 * Lowe's list) plus Nexa's own list. uBO lists and Nexa's list are
 * [trusted]: like uBO, only they may use trusted-only scriptlets.
 * Regional lists are enabled for matching UI languages only.
 *
 * @property mirrorUrls fallbacks tried in order when the primary URL fails.
 * @property languages UI language codes enabling a regional list; empty = always enabled.
 */
enum class BrowserResourceId(
    val cacheFileName: String,
    val remoteUrl: String,
    val trusted: Boolean,
    val mirrorUrls: List<String> = emptyList(),
    val languages: Set<String> = emptySet(),
) {
    NexaFilters(
        cacheFileName = "filters/nexa.txt",
        remoteUrl = "https://raw.githubusercontent.com/elewashy/Nexa/main/web_resources/filters/blocklist.txt",
        trusted = true,
    ),
    UBlockFilters(
        cacheFileName = "filters/ublock_filters.txt",
        remoteUrl = "https://ublockorigin.github.io/uAssets/filters/filters.min.txt",
        trusted = true,
        mirrorUrls = listOf("https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/filters/filters.min.txt"),
    ),
    UBlockBadware(
        cacheFileName = "filters/ublock_badware.txt",
        remoteUrl = "https://ublockorigin.github.io/uAssets/filters/badware.min.txt",
        trusted = true,
        mirrorUrls = listOf("https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/filters/badware.min.txt"),
    ),
    UBlockPrivacy(
        cacheFileName = "filters/ublock_privacy.txt",
        remoteUrl = "https://ublockorigin.github.io/uAssets/filters/privacy.min.txt",
        trusted = true,
        mirrorUrls = listOf("https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/filters/privacy.min.txt"),
    ),
    UBlockQuickFixes(
        cacheFileName = "filters/ublock_quick_fixes.txt",
        remoteUrl = "https://ublockorigin.github.io/uAssets/filters/quick-fixes.min.txt",
        trusted = true,
        mirrorUrls = listOf("https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/filters/quick-fixes.min.txt"),
    ),
    UBlockUnbreak(
        cacheFileName = "filters/ublock_unbreak.txt",
        remoteUrl = "https://ublockorigin.github.io/uAssets/filters/unbreak.min.txt",
        trusted = true,
        mirrorUrls = listOf("https://cdn.jsdelivr.net/gh/uBlockOrigin/uAssetsCDN@main/filters/unbreak.min.txt"),
    ),
    EasyList(
        cacheFileName = "filters/easylist.txt",
        remoteUrl = "https://easylist.to/easylist/easylist.txt",
        trusted = false,
        mirrorUrls = listOf("https://ublockorigin.github.io/uAssets/thirdparties/easylist.txt"),
    ),
    EasyPrivacy(
        cacheFileName = "filters/easyprivacy.txt",
        remoteUrl = "https://easylist.to/easylist/easyprivacy.txt",
        trusted = false,
        mirrorUrls = listOf("https://ublockorigin.github.io/uAssets/thirdparties/easyprivacy.txt"),
    ),
    PeterLoweList(
        cacheFileName = "filters/peter_lowe.txt",
        remoteUrl = "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=1&mimetype=plaintext",
        trusted = false,
    ),
    ListeAr(
        cacheFileName = "filters/liste_ar.txt",
        remoteUrl = "https://easylist-downloads.adblockplus.org/Liste_AR.txt",
        trusted = false,
        languages = setOf("ar"),
    );

    /** Whether this list belongs to the active selection for UI [language] (ISO 639 code). */
    fun isEnabledFor(language: String): Boolean = languages.isEmpty() || language in languages

    companion object {
        fun enabledFor(language: String): List<BrowserResourceId> = entries.filter { it.isEnabledFor(language) }
    }
}

/**
 * Outcome of one refresh attempt.
 *
 * @property checked a network check was performed (false when not yet due).
 * @property updated the cached content changed.
 * @property failed the check failed (network/HTTP error or invalid content); the previous cache is kept.
 * @property available a usable cached copy exists.
 */
data class BrowserResourceRefreshResult(
    val id: BrowserResourceId,
    val checked: Boolean,
    val updated: Boolean,
    val failed: Boolean,
    val available: Boolean,
)
