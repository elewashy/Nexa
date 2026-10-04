package com.elewashy.nexa.feature.share.domain.model

/** Why an extraction failed, so the UI can tell the user what to do about it. */
enum class ExtractionError {
    /** The platform or a backend could not be reached (offline, timeout, 5xx). Retrying may help. */
    NETWORK,

    /** The link was read, but it has no downloadable media (text post, removed or private content). */
    NO_MEDIA,

    /** The link is not one the extractors understand. */
    UNSUPPORTED,
}
