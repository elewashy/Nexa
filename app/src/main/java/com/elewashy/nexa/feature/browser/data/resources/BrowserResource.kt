package com.elewashy.nexa.feature.browser.data.resources

enum class BrowserResourceKind {
    Filter,
    JavaScript,
}

enum class BrowserResourceOwner {
    Nexa,
    External,
}

private const val NEXA_CHECK_EVERY_RUN = 0L
private const val EXTERNAL_FILTER_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L

enum class BrowserResourceId(
    val kind: BrowserResourceKind,
    val owner: BrowserResourceOwner,
    val cacheFileName: String,
    val remoteUrl: String,
    val updateIntervalMs: Long,
) {
    InternalAdFilters(
        kind = BrowserResourceKind.Filter,
        owner = BrowserResourceOwner.Nexa,
        cacheFileName = "filters/blocklist.txt",
        remoteUrl = "https://raw.githubusercontent.com/elewashy/Nexa/main/web_resources/filters/blocklist.txt",
        updateIntervalMs = NEXA_CHECK_EVERY_RUN,
    ),
    ValidLinks(
        kind = BrowserResourceKind.Filter,
        owner = BrowserResourceOwner.Nexa,
        cacheFileName = "filters/allowlist.txt",
        remoteUrl = "https://raw.githubusercontent.com/elewashy/Nexa/main/web_resources/filters/allowlist.txt",
        updateIntervalMs = NEXA_CHECK_EVERY_RUN,
    ),
    PreLoadScript(
        kind = BrowserResourceKind.JavaScript,
        owner = BrowserResourceOwner.Nexa,
        cacheFileName = "scripts/pre_load.js",
        remoteUrl = "https://raw.githubusercontent.com/elewashy/Nexa/main/web_resources/scripts/pre_load.js",
        updateIntervalMs = NEXA_CHECK_EVERY_RUN,
    ),
    PostLoadScript(
        kind = BrowserResourceKind.JavaScript,
        owner = BrowserResourceOwner.Nexa,
        cacheFileName = "scripts/post_load.js",
        remoteUrl = "https://raw.githubusercontent.com/elewashy/Nexa/main/web_resources/scripts/post_load.js",
        updateIntervalMs = NEXA_CHECK_EVERY_RUN,
    ),
    EasyList(
        kind = BrowserResourceKind.Filter,
        owner = BrowserResourceOwner.External,
        cacheFileName = "filters/external_easylist.txt",
        remoteUrl = "https://easylist.to/easylist/easylist.txt",
        updateIntervalMs = EXTERNAL_FILTER_CHECK_INTERVAL_MS,
    ),
    EasyPrivacy(
        kind = BrowserResourceKind.Filter,
        owner = BrowserResourceOwner.External,
        cacheFileName = "filters/external_easyprivacy.txt",
        remoteUrl = "https://easylist.to/easylist/easyprivacy.txt",
        updateIntervalMs = EXTERNAL_FILTER_CHECK_INTERVAL_MS,
    ),
    OneHostsLite(
        kind = BrowserResourceKind.Filter,
        owner = BrowserResourceOwner.External,
        cacheFileName = "filters/external_1hosts_lite.txt",
        remoteUrl = "https://badmojr.github.io/1Hosts/Lite/adblock.txt",
        updateIntervalMs = EXTERNAL_FILTER_CHECK_INTERVAL_MS,
    );

    companion object {
        val adBlockFilters: List<BrowserResourceId> = listOf(
            InternalAdFilters,
            EasyList,
            EasyPrivacy,
            OneHostsLite,
        )

        val scripts: List<BrowserResourceId> = listOf(PreLoadScript, PostLoadScript)
    }
}

data class BrowserResourceRefreshResult(
    val id: BrowserResourceId,
    val checked: Boolean,
    val updated: Boolean,
    val available: Boolean,
)
