package com.elewashy.nexa.feature.browser.domain.model

/**
 * How often content-blocking filter lists are checked for updates. Stored as
 * hours; [Manual] disables automatic checks (updates only from Settings).
 */
enum class FilterUpdateInterval(val hours: Int) {
    Manual(0),
    SixHours(6),
    TwelveHours(12),
    Daily(24),
    ThreeDays(72),
    Weekly(168);

    /** Interval in milliseconds, or null when automatic updates are disabled. */
    val intervalMs: Long? get() = if (hours == 0) null else hours * 60L * 60L * 1000L

    companion object {
        val DEFAULT = Daily

        fun fromStoredValue(hours: Int): FilterUpdateInterval =
            entries.firstOrNull { it.hours == hours } ?: DEFAULT
    }
}
