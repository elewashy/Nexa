package com.elewashy.nexa.feature.downloads.data.persistence

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus

/** Room storage codes — explicit and stable, never enum ordinals. */
object DownloadStatusCodes {
    const val PENDING = 0
    const val DOWNLOADING = 1
    const val PAUSED = 2
    const val COMPLETED = 3
    const val FAILED = 4
    const val CANCELLED = 5

    fun toCode(status: DownloadStatus): Int = when (status) {
        DownloadStatus.PENDING -> PENDING
        DownloadStatus.DOWNLOADING -> DOWNLOADING
        DownloadStatus.PAUSED -> PAUSED
        DownloadStatus.COMPLETED -> COMPLETED
        DownloadStatus.FAILED -> FAILED
        DownloadStatus.CANCELLED -> CANCELLED
    }

    fun fromCode(code: Int): DownloadStatus = when (code) {
        PENDING -> DownloadStatus.PENDING
        DOWNLOADING -> DownloadStatus.DOWNLOADING
        PAUSED -> DownloadStatus.PAUSED
        COMPLETED -> DownloadStatus.COMPLETED
        FAILED -> DownloadStatus.FAILED
        CANCELLED -> DownloadStatus.CANCELLED
        else -> DownloadStatus.PAUSED
    }
}

/**
 * Persistent structural state of one download. High-frequency runtime values
 * (speed, ETA, failure tally) deliberately live only in memory.
 *
 * The (status, created_at) index serves the completed-retention prune and the
 * active-work scan; the UI list stays small enough to sort in memory.
 */
@Entity(
    tableName = "downloads",
    indices = [Index(value = ["status", "created_at"])],
)
data class DownloadEntity(
    @PrimaryKey val id: Long,
    val url: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "total_bytes") val totalBytes: Long = -1,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long = 0,
    val status: Int = DownloadStatusCodes.PENDING,
    @ColumnInfo(name = "mime_type") val mimeType: String? = null,
    @ColumnInfo(name = "user_agent") val userAgent: String? = null,
    val referer: String? = null,
    val origin: String? = null,
    val cookies: String? = null,
    val source: String = "UNKNOWN",
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "was_waiting_for_network") val wasWaitingForNetwork: Boolean = false,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
)

/**
 * Persisted byte-range state of one segment. The composite primary key is the
 * restore access pattern (whole-row read ordered by start_byte); the foreign
 * key cascades so cancellations/prunes can never orphan segment rows.
 */
@Entity(
    tableName = "download_segments",
    primaryKeys = ["download_id", "start_byte"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = DownloadEntity::class,
            parentColumns = ["id"],
            childColumns = ["download_id"],
            onDelete = androidx.room.ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadSegmentEntity(
    @ColumnInfo(name = "download_id") val downloadId: Long,
    @ColumnInfo(name = "start_byte") val startByte: Long,
    @ColumnInfo(name = "end_byte") val endByte: Long,
    @ColumnInfo(name = "downloaded_bytes") val downloadedBytes: Long,
    val completed: Boolean,
)

/**
 * Single-row sequence storage replacing the JSON `lastId` field.
 *
 * [legacyImported] records, inside the import transaction itself, that the
 * one-time legacy (JSON/prefs) import ran — the row-count gate alone could
 * resurrect deleted records after a kill between commit and artifact rename.
 */
@Entity(tableName = "download_meta")
data class DownloadMetaEntity(
    @PrimaryKey val id: Int = 1,
    @ColumnInfo(name = "last_id") val lastId: Long = 0,
    @ColumnInfo(name = "legacy_imported", defaultValue = "0")
    val legacyImported: Boolean = false,
)
