package com.elewashy.nexa.feature.downloads.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface DownloadsDao {

    /**
     * ON CONFLICT DO UPDATE — never INSERT OR REPLACE: replace performs an
     * implicit delete of the existing row, which would fire the segments
     * foreign key's ON DELETE CASCADE and silently destroy resume state.
     */
    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun byId(id: Long): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM downloads")
    suspend fun count(): Int

    /** Ids of completed downloads beyond the [keep] most recent. */
    @Query(
        "SELECT id FROM downloads WHERE status = ${DownloadStatusCodes.COMPLETED} " +
            "AND id NOT IN (SELECT id FROM downloads WHERE status = ${DownloadStatusCodes.COMPLETED} " +
            "ORDER BY created_at DESC LIMIT :keep)"
    )
    suspend fun completedIdsBeyondKeep(keep: Int): List<Long>

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    // ── Segments ────────────────────────────────────────────────────────

    @Query("SELECT * FROM download_segments WHERE download_id = :id ORDER BY start_byte")
    suspend fun segmentsFor(id: Long): List<DownloadSegmentEntity>

    @Query("DELETE FROM download_segments WHERE download_id = :id")
    suspend fun deleteSegments(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<DownloadSegmentEntity>)

    /**
     * Replace-all segment rows + progress in one transaction, mirroring the
     * old atomic snapshot write. The empty-segment guard lives in the caller
     * (a restored-but-unresumed task must not wipe its own resume state).
     */
    @Transaction
    suspend fun replaceSegments(id: Long, segments: List<DownloadSegmentEntity>) {
        deleteSegments(id)
        if (segments.isNotEmpty()) insertSegments(segments)
    }

    @Transaction
    suspend fun upsertWithSegments(entity: DownloadEntity, segments: List<DownloadSegmentEntity>) {
        upsert(entity)
        replaceSegments(entity.id, segments)
    }

    // ── Meta / id sequence ──────────────────────────────────────────────

    /** Full-row write used ONLY by the legacy import transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setMeta(meta: DownloadMetaEntity)

    /**
     * Sequence update that never touches [DownloadMetaEntity.legacyImported]:
     * a full-row REPLACE would reset the marker on the first download created
     * after the import, re-opening the resurrection window.
     */
    @Query(
        "INSERT INTO download_meta (id, last_id, legacy_imported) VALUES (1, :lastId, 0) " +
            "ON CONFLICT(id) DO UPDATE SET last_id = :lastId"
    )
    suspend fun updateLastId(lastId: Long)

    /** Sets the one-shot import marker without clobbering the sequence. */
    @Query(
        "INSERT INTO download_meta (id, last_id, legacy_imported) VALUES (1, 0, 1) " +
            "ON CONFLICT(id) DO UPDATE SET legacy_imported = 1"
    )
    suspend fun markLegacyImported()

    @Query("SELECT legacy_imported FROM download_meta WHERE id = 1")
    suspend fun legacyImported(): Boolean?

    @Query("SELECT last_id FROM download_meta WHERE id = 1")
    suspend fun lastId(): Long?
}
