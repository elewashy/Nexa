package com.elewashy.nexa.feature.downloads.domain.model

/** Stable limits derived from the engine's worst case of 16 HTTP segments per file. */
object DownloadSettingsDefaults {
    const val DEFAULT_CONCURRENT_DOWNLOADS = 3

    /** 5 × 16 segments = 80 requests, leaving 16 slots in the engine's 96-request dispatcher. */
    const val MAX_CONCURRENT_DOWNLOADS = 5
    const val MIN_CONCURRENT_DOWNLOADS = 1
    const val UNLIMITED_SPEED_BYTES_PER_SECOND = 0L
    const val MIN_CUSTOM_SPEED_BYTES_PER_SECOND = 1L * 1024
    const val MAX_CUSTOM_SPEED_BYTES_PER_SECOND = 1L * 1024 * 1024 * 1024
    val DEFAULT_FILTER_IDS: Set<String> = setOf(
        DownloadFilterCategory.Videos.storedId,
        DownloadFilterCategory.Audio.storedId,
        DownloadFilterCategory.Other.storedId,
    )

    fun clampConcurrentDownloads(value: Int): Int = value.coerceIn(
        MIN_CONCURRENT_DOWNLOADS,
        MAX_CONCURRENT_DOWNLOADS,
    )

    fun sanitizeSpeedLimit(value: Long): Long = when {
        value <= UNLIMITED_SPEED_BYTES_PER_SECOND -> UNLIMITED_SPEED_BYTES_PER_SECOND
        else -> value.coerceIn(MIN_CUSTOM_SPEED_BYTES_PER_SECOND, MAX_CUSTOM_SPEED_BYTES_PER_SECOND)
    }

    /** Parses a user-entered KiB/s value, including locale-specific decimal digits. */
    fun parseCustomSpeedLimit(input: String): Long? {
        val normalized = buildString {
            input.trim().forEach { character ->
                val digit = character.digitToIntOrNull() ?: return null
                append(digit)
            }
        }
        return normalized.takeIf(String::isNotEmpty)
            ?.toLongOrNull()
            ?.takeIf { it in 1L..MAX_CUSTOM_SPEED_BYTES_PER_SECOND / BYTES_PER_KIB }
            ?.times(BYTES_PER_KIB)
    }

    private const val BYTES_PER_KIB = 1024L
}

enum class DownloadFilterCategory(val storedId: String) {
    Videos("videos"),
    Audio("audio"),
    Images("images"),
    Apk("apk"),
    Pdf("pdf"),
    Archives("archives"),
    Other("other");

    companion object {
        fun fromStoredIds(ids: Set<String>): Set<DownloadFilterCategory> =
            ids.mapNotNullTo(linkedSetOf()) { id -> entries.firstOrNull { it.storedId == id } }
    }
}

/** Pure projection from stored filter preferences and actual content to visible filter state. */
object DownloadFilterPolicy {
    fun dedicatedCategories(enabled: Set<DownloadFilterCategory>): Set<DownloadFilterCategory> =
        enabled - DownloadFilterCategory.Other

    fun matches(
        item: DownloadItem,
        selected: DownloadFilterCategory?,
        enabled: Set<DownloadFilterCategory>,
    ): Boolean {
        if (selected == null) return true
        val actual = DownloadFileClassifier.category(item)
        return if (selected == DownloadFilterCategory.Other) {
            actual !in dedicatedCategories(enabled)
        } else {
            actual == selected
        }
    }

    /** Returns only enabled categories represented by content; Other is the current complement. */
    fun visibleCounts(
        items: List<DownloadItem>,
        enabled: Set<DownloadFilterCategory>,
    ): Map<DownloadFilterCategory, Int> {
        if (items.isEmpty()) return emptyMap()
        val dedicated = dedicatedCategories(enabled)
        val actualCounts = items.groupingBy(DownloadFileClassifier::category).eachCount()
        return buildMap {
            DownloadFilterCategory.entries.forEach { category ->
                if (category != DownloadFilterCategory.Other && category in dedicated) {
                    actualCounts[category]?.takeIf { it > 0 }?.let { put(category, it) }
                }
            }
            if (DownloadFilterCategory.Other in enabled) {
                val otherCount = actualCounts.entries.sumOf { (category, count) ->
                    if (category !in dedicated) count else 0
                }
                if (otherCount > 0) put(DownloadFilterCategory.Other, otherCount)
            }
        }
    }
}

object DownloadFileClassifier {
    fun category(item: DownloadItem): DownloadFilterCategory {
        val mime = item.mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val extension = item.fileName.substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("video/") || extension in VIDEO_EXTENSIONS -> DownloadFilterCategory.Videos
            mime.startsWith("audio/") || extension in AUDIO_EXTENSIONS -> DownloadFilterCategory.Audio
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> DownloadFilterCategory.Images
            mime == APK_MIME || extension == "apk" -> DownloadFilterCategory.Apk
            mime == PDF_MIME || extension == "pdf" -> DownloadFilterCategory.Pdf
            mime in ARCHIVE_MIMES || extension in ARCHIVE_EXTENSIONS -> DownloadFilterCategory.Archives
            else -> DownloadFilterCategory.Other
        }
    }

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val PDF_MIME = "application/pdf"
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "avi", "mov", "3gp")
    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "opus")
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heic")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
    private val ARCHIVE_MIMES = setOf(
        "application/zip",
        "application/vnd.rar",
        "application/x-rar-compressed",
        "application/x-7z-compressed",
        "application/x-tar",
        "application/gzip",
        "application/x-bzip2",
        "application/x-xz",
    )
}
