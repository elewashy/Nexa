package com.elewashy.nexa.feature.downloads.domain.model

/** Persisted presentation choice for the Download Manager. */
enum class DownloadManagerLayout(val storedValue: Int) {
    /** Date-grouped media gallery with contextual file filters. */
    MediaGallery(0),

    /** Active/completed tab layout optimized for task scanning. */
    TabbedList(1);

    companion object {
        fun fromStoredValue(value: Int): DownloadManagerLayout =
            entries.firstOrNull { it.storedValue == value } ?: MediaGallery
    }
}
