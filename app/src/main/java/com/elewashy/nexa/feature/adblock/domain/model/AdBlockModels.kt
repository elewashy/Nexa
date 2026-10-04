package com.elewashy.nexa.feature.adblock.domain.model

import java.time.LocalDate

/**
 * Per-site switches, uBO style. A setting for `example.com` also covers
 * every subdomain; the most specific host wins.
 *
 * @property blockingEnabled false = trusted site: no filtering of any kind (uBO's power switch).
 * @property cosmeticFilteringEnabled element hiding and scriptlets (uBO's "no cosmetic filtering" switch).
 * @property popupBlockingEnabled pop-up / pop-under blocking (uBO's "no pop-ups" switch, inverted).
 */
data class SiteAdBlockSettings(
    val host: String,
    val blockingEnabled: Boolean = true,
    val cosmeticFilteringEnabled: Boolean = true,
    val popupBlockingEnabled: Boolean = true,
) {
    /** True when every switch has its default value (no stored row is needed). */
    val isDefault: Boolean get() = blockingEnabled && cosmeticFilteringEnabled && popupBlockingEnabled
}

/** What a filter rule does, as understood by the engine. */
enum class RuleKind {
    /** Network block (`||ads.example^`, hosts-file and plain hostname lines). */
    Block,

    /** `$important` network block: wins over ordinary exceptions. */
    ImportantBlock,

    /** Network exception (`@@…`). */
    Allow,

    /** Element hiding / procedural / CSS injection (`##…`). */
    ElementHiding,

    /** Cosmetic exception (`#@#…`). */
    ElementHidingException,

    /** Scriptlet injection (`##+js(…)`) or its exception. */
    Scriptlet,

    /** `$removeparam`, `$csp`, `$redirect-rule` and other modifier filters that never block by themselves. */
    Modifier,

    /** `$badfilter`: disables another filter. */
    BadFilter,

    /** Comment line; kept but ignored. */
    Comment,

    /** Valid syntax that needs "Allow trusted custom rules" (trusted-only scriptlets). */
    RequiresTrust,

    /** Syntax the engine cannot apply in WebView, or invalid. */
    Unsupported,
    ;

    val isEffective: Boolean get() = this != Comment && this != RequiresTrust && this != Unsupported
}

data class CustomRule(
    val id: Long,
    val text: String,
    val enabled: Boolean,
    val kind: RuleKind,
    val updatedAt: Long,
)

/** Status of one filter list, derived from its cache metadata. */
enum class FilterListState {
    /** Not selected. */
    Disabled,

    /** Selected; the first download is pending. */
    Downloading,

    /** Selected and compiled into the engine. */
    Active,

    /** Selected; the last update failed but the previous copy is still active. */
    UpdateFailed,

    /** Selected; never downloaded successfully, so it contributes no rules yet. */
    Unavailable,
}

/**
 * A filter list as shown on the Filter lists page.
 *
 * @property ruleCount rules this list added to the engine (duplicates of earlier lists not counted); null if not compiled.
 * @property lastUpdatedAt last successful check (0 = never).
 * @property lastFailedAt last failed check (0 = none since the last success).
 * @property url source URL of an imported list.
 */
data class FilterListInfo(
    val key: String,
    val title: String,
    val category: FilterListCategory,
    val enabled: Boolean,
    val state: FilterListState,
    val ruleCount: Int?,
    val lastUpdatedAt: Long,
    val lastFailedAt: Long,
    val regions: String,
    val url: String?,
    val isDefault: Boolean,
)

/** Blocking counters for one day. */
data class DailyBlockingStats(
    val date: LocalDate,
    val blockedRequests: Long,
    val bytesSaved: Long,
)

/**
 * Lifetime and recent blocking statistics.
 *
 * @property since first day statistics were recorded (null = nothing recorded yet).
 * @property recentDays the last [RECENT_DAYS] days, oldest first, zero-filled.
 */
data class AdBlockStatistics(
    val blockedRequests: Long,
    val blockedPopups: Long,
    val blockedPages: Long,
    val removedParams: Long,
    val bytesSaved: Long,
    val pagesFiltered: Long,
    val since: LocalDate?,
    val recentDays: List<DailyBlockingStats>,
) {
    val today: DailyBlockingStats? get() = recentDays.lastOrNull()

    /** Blocked items of every kind. */
    val totalBlocked: Long get() = blockedRequests + blockedPopups + blockedPages

    val isEmpty: Boolean get() = since == null

    companion object {
        const val RECENT_DAYS = 7
    }
}

data class BlockedDomain(
    val domain: String,
    val blockedCount: Long,
    val lastBlockedAt: Long,
)
