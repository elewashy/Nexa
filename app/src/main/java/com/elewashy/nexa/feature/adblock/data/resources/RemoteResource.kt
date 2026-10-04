package com.elewashy.nexa.feature.adblock.data.resources

/**
 * A remote file downloaded and cached by [BrowserResourceRepository].
 *
 * @property key stable persistence key; prefixes the cache metadata, so it must never change for a resource.
 * @property cacheFileName path of the cached copy, relative to the resource directory.
 * @property downloadUrls URLs tried in order (primary, then mirrors).
 */
interface RemoteResource {
    val key: String
    val cacheFileName: String
    val downloadUrls: List<String>
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
    val resource: RemoteResource,
    val checked: Boolean,
    val updated: Boolean,
    val failed: Boolean,
    val available: Boolean,
)
