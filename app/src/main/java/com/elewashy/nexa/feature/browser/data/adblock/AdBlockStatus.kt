package com.elewashy.nexa.feature.browser.data.adblock

/**
 * Observable state of the content blocker, shown in Settings.
 *
 * @property phase what the blocker is doing right now.
 * @property networkFilters / [cosmeticFilters] / [scriptletFilters] counts of the active engine.
 * @property activeLists lists compiled into the active engine.
 * @property enabledLists lists in the current selection (some may still be downloading).
 * @property failedLists lists whose last update attempt failed; their previous copy stays active.
 */
data class AdBlockStatus(
    val phase: Phase = Phase.Loading,
    val networkFilters: Int = 0,
    val cosmeticFilters: Int = 0,
    val scriptletFilters: Int = 0,
    val activeLists: Int = 0,
    val enabledLists: Int = 0,
    val failedLists: Int = 0,
) {
    enum class Phase {
        /** Compiling the cached lists at startup; requests wait briefly for it. */
        Loading,

        /** Engine active and idle. */
        Ready,

        /** Checking / downloading lists; the active engine keeps filtering meanwhile. */
        Updating,
    }

    val totalFilters: Int get() = networkFilters + cosmeticFilters + scriptletFilters
}

/** Result of an update pass. */
data class FilterUpdateResult(
    /** Lists whose check completed (200 or 304). */
    val checked: Int,
    /** Lists whose content changed. */
    val updated: Int,
    /** Lists whose check failed; their previous copy is kept. */
    val failed: Int,
) {
    val success: Boolean get() = failed == 0
}
