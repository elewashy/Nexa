package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.feature.adblock.data.engine.Hostnames
import com.elewashy.nexa.feature.adblock.data.persistence.SiteSettingsDao
import com.elewashy.nexa.feature.adblock.data.persistence.SiteSettingsEntity
import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted per-site switches. Only sites that differ from the defaults are
 * stored; saving default settings removes the row. Hot-path lookups go
 * through [AdBlockPolicyStore], which caches this table in memory.
 */
@Singleton
class SiteSettingsRepository @Inject constructor(
    private val dao: SiteSettingsDao,
) {
    /** Sites with custom settings, alphabetically. */
    val sites: Flow<List<SiteAdBlockSettings>> = dao.observeAll().map { rows -> rows.map { it.toModel() } }

    suspend fun save(settings: SiteAdBlockSettings) {
        if (settings.isDefault) {
            dao.delete(settings.host)
        } else {
            dao.upsert(
                SiteSettingsEntity(
                    host = settings.host,
                    blockingEnabled = settings.blockingEnabled,
                    cosmeticFilteringEnabled = settings.cosmeticFilteringEnabled,
                    popupBlockingEnabled = settings.popupBlockingEnabled,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun remove(host: String) {
        dao.delete(host)
    }

    private fun SiteSettingsEntity.toModel() = SiteAdBlockSettings(
        host = host,
        blockingEnabled = blockingEnabled,
        cosmeticFilteringEnabled = cosmeticFilteringEnabled,
        popupBlockingEnabled = popupBlockingEnabled,
    )

    companion object {
        /**
         * The site key for user input or a page URL: the lowercase,
         * punycode hostname without a leading `www.` (so a setting made on
         * `www.example.com` also covers `example.com` and its other
         * subdomains). Null when [input] has no valid hostname.
         */
        fun siteKey(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            val withoutScheme = trimmed.substringAfter("://", trimmed)
            val rawHost = withoutScheme
                .substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':')
            val host = Hostnames.normalize(rawHost) ?: return null
            if (Hostnames.isIpAddress(host)) return host
            if (!Hostnames.isValidHostname(host) || !host.contains('.')) return null
            return host.removePrefix("www.").takeIf { it.contains('.') } ?: host
        }
    }
}
