package com.elewashy.nexa.feature.adblock.domain.model

/**
 * Groups of filter lists, in display order. Mirrors uBlock Origin's
 * "Filter lists" pane: built-in defaults, then lists by purpose, regional
 * lists and lists the user imported by URL.
 */
enum class FilterListCategory {
    Default,
    Ads,
    Privacy,
    Malware,
    Annoyances,
    Multipurpose,
    Regional,
    Imported,
}
