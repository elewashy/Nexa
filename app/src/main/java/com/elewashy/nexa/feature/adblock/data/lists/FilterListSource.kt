package com.elewashy.nexa.feature.adblock.data.lists

import com.elewashy.nexa.feature.adblock.data.resources.RemoteResource
import com.elewashy.nexa.feature.adblock.domain.model.FilterListCategory
import java.security.MessageDigest

/** A filter list the engine can compile: a catalog entry or a list imported by URL. */
sealed interface FilterListSource : RemoteResource {
    /** Display name. Imported lists use the list's `! Title:` header once downloaded. */
    val title: String
    val category: FilterListCategory

    /** Whether the list may use trusted-only scriptlets (uBO's trusted sources). */
    val trusted: Boolean
}

/**
 * A third-party list the user imported by URL (uBO's "Import…" custom
 * lists). Never trusted: like uBO, imported lists cannot run trusted-only
 * scriptlets.
 */
data class ImportedFilterList(
    override val key: String,
    val url: String,
    override val title: String,
) : FilterListSource {
    override val category: FilterListCategory get() = FilterListCategory.Imported
    override val trusted: Boolean get() = false
    override val cacheFileName: String get() = "filters/imported/${key.removePrefix(KEY_PREFIX)}.txt"
    override val downloadUrls: List<String> get() = listOf(url)

    companion object {
        const val KEY_PREFIX = "Imported_"

        /** Stable key for [url]: the same URL always maps to the same cache entry. */
        fun keyFor(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            return KEY_PREFIX + digest.take(KEY_BYTES).joinToString("") { "%02x".format(it) }
        }

        fun isImportedKey(key: String): Boolean = key.startsWith(KEY_PREFIX)

        private const val KEY_BYTES = 8
    }
}
