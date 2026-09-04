package com.elewashy.nexa.feature.browser.domain.model

/** Persisted compact-window browser toolbar placement. Expanded layouts continue to use a rail. */
enum class BrowserNavigationBarPosition(val storedValue: Int) {
    Bottom(0),
    Top(1);

    companion object {
        fun fromStoredValue(value: Int): BrowserNavigationBarPosition =
            entries.firstOrNull { it.storedValue == value } ?: Bottom
    }
}
