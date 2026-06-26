package com.elewashy.nexa.feature.downloads.data

import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for download state.
 *
 * Implementations own the download engine, persistence, notifications, and
 * connectivity-triggered auto-resume. Collaborators (the foreground service,
 * ViewModels) observe [downloads] and dispatch commands via the suspend APIs.
 */
interface DownloadRepository {

    /**
     * Sorted snapshot stream of all known downloads. Emits on every status
     * or progress change at the same frequency as the pre-refactor broadcast.
     *
     * Sort order (preserved from `DownloadService.getDownloadItems`):
     *   DOWNLOADING → PENDING → other, then newest `createdAt` first.
     */
    val downloads: StateFlow<List<DownloadItem>>

    /**
     * Signals the repository that the foreground service has started and is
     * ready to receive `startForeground` calls. Called once from
     * `DownloadService.onCreate`. Idempotent.
     *
     * The Android foreground-service contract requires the Service instance
     * itself to invoke `startForeground` within 5 s of `startService`, so the
     * repository cannot do it directly — the service passes itself in.
     */
    fun attachService(service: android.app.Service)

    /**
     * Releases the service reference obtained via [attachService]. Called from
     * `DownloadService.onDestroy` so the repository no longer tries to post
     * foreground notifications against a dead service.
     */
    fun detachService()

    /** Enqueues a new download. Resolves filename from the server asynchronously. */
    suspend fun start(request: DownloadRequest)

    /** Pauses the download if it is DOWNLOADING or PENDING. */
    suspend fun pause(id: Long)

    /** Resumes a PAUSED or FAILED download. Re-enqueues into the engine if needed. */
    suspend fun resume(id: Long)

    /** Cancels and deletes the on-disk file. */
    suspend fun cancel(id: Long)

    /** Removes from the list but keeps the on-disk file. */
    suspend fun remove(id: Long)

    /** Same semantics as [resume] — preserved for intent-action parity. */
    suspend fun retry(id: Long)

    /** Observes a single download by ID. Emits null once the item is gone. */
    fun observe(id: Long): Flow<DownloadItem?>
}
