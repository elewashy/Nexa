package com.elewashy.nexa.feature.adblock.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The user's filter-list selection. Built-in catalog lists have a row only
 * once the user changed them (absent = catalog default, so new default
 * lists reach existing users); imported lists always have a row carrying
 * their [url] and [title].
 */
@Entity(tableName = "adblock_filter_lists")
data class FilterListSettingEntity(
    @PrimaryKey @ColumnInfo(name = "list_key") val listKey: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    /** Source URL of an imported list; null for built-in lists. */
    @ColumnInfo(name = "url") val url: String? = null,
    /** Title of an imported list (from its `! Title:` header once downloaded); null for built-in lists. */
    @ColumnInfo(name = "title") val title: String? = null,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

/**
 * A user filter rule (uBO "My filters"), one rule per row so each can be
 * edited and toggled individually. The unique index rejects duplicates.
 */
@Entity(
    tableName = "adblock_custom_rules",
    indices = [Index(value = ["rule"], unique = true)],
)
data class CustomRuleEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "rule") val rule: String,
    @ColumnInfo(name = "enabled") val enabled: Boolean,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/**
 * Per-site switches (uBO's trusted sites and per-site switches). [host]
 * covers the host and all its subdomains. Rows exist only while at least
 * one switch differs from the default (all enabled).
 */
@Entity(tableName = "adblock_site_settings")
data class SiteSettingsEntity(
    @PrimaryKey @ColumnInfo(name = "host") val host: String,
    @ColumnInfo(name = "blocking_enabled") val blockingEnabled: Boolean,
    @ColumnInfo(name = "cosmetic_filtering_enabled") val cosmeticFilteringEnabled: Boolean,
    @ColumnInfo(name = "popup_blocking_enabled") val popupBlockingEnabled: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

/** Blocking counters aggregated per local calendar day, written in batches. */
@Entity(tableName = "adblock_daily_stats")
data class DailyStatsEntity(
    /** Local date as epoch day. */
    @PrimaryKey @ColumnInfo(name = "day") val day: Long,
    @ColumnInfo(name = "blocked_requests") val blockedRequests: Long,
    @ColumnInfo(name = "blocked_popups") val blockedPopups: Long,
    @ColumnInfo(name = "blocked_pages") val blockedPages: Long,
    @ColumnInfo(name = "removed_params") val removedParams: Long,
    @ColumnInfo(name = "bytes_saved") val bytesSaved: Long,
    @ColumnInfo(name = "pages_filtered") val pagesFiltered: Long,
)

/**
 * Blocked-request counts per registrable domain of the blocked request
 * (the ad/tracker domain, never the visited site). Private tabs are not
 * recorded. Pruned to the most-blocked domains.
 */
@Entity(
    tableName = "adblock_blocked_domains",
    indices = [Index(value = ["blocked_count"])],
)
data class BlockedDomainEntity(
    @PrimaryKey @ColumnInfo(name = "domain") val domain: String,
    @ColumnInfo(name = "blocked_count") val blockedCount: Long,
    @ColumnInfo(name = "last_blocked_at") val lastBlockedAt: Long,
)
