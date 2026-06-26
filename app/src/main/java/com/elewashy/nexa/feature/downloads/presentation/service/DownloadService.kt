package com.elewashy.nexa.feature.downloads.presentation.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DownloadService — thin foreground-service shell.
 *
 * Responsibilities:
 *  - Android foreground-service lifecycle (startForeground within 5 s).
 *  - Intent routing into suspend calls on [DownloadRepository].
 *
 * All state ownership, the download engine, persistence, notifications, and
 * connectivity monitoring live in the repository (see `DownloadRepositoryImpl`).
 * UI observers go straight to the repository's `downloads: StateFlow` via
 * `ObserveDownloadsUseCase` — the legacy LocalBroadcast path and service
 * binding were removed in sub-phase 3.6.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    // The service is started via `startService()` and is never bound. Android
    // still requires `onBind` to be implemented; returning null is the
    // documented way to refuse binding.
    override fun onBind(intent: Intent?): IBinder? = null

    // ── Injected collaborators ─────────────────────────────────────────

    @Inject lateinit var repository: DownloadRepository

    /**
     * Application-scoped coroutine scope. Used for intent handlers so that
     * pending suspend calls are not cancelled when the service is torn down.
     */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    companion object {
        private const val TAG = "DownloadService"

        // Actions
        const val ACTION_START_DOWNLOAD  = "com.elewashy.nexa.ACTION_START_DOWNLOAD"
        const val ACTION_PAUSE_DOWNLOAD  = "com.elewashy.nexa.ACTION_PAUSE_DOWNLOAD"
        const val ACTION_RESUME_DOWNLOAD = "com.elewashy.nexa.ACTION_RESUME_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.elewashy.nexa.ACTION_CANCEL_DOWNLOAD"   // Cancel + delete file
        const val ACTION_REMOVE_DOWNLOAD = "com.elewashy.nexa.ACTION_REMOVE_DOWNLOAD"   // Remove from list only (keep file)
        const val ACTION_RETRY_DOWNLOAD  = "com.elewashy.nexa.ACTION_RETRY_DOWNLOAD"
        const val ACTION_OPEN_DOWNLOADS  = "com.elewashy.nexa.ACTION_OPEN_DOWNLOADS"

        // Intent extras
        const val EXTRA_URL             = "extra_url"
        const val EXTRA_FILE_NAME       = "extra_file_name"
        const val EXTRA_MIME_TYPE       = "extra_mime_type"
        const val EXTRA_USER_AGENT      = "extra_user_agent"
        const val EXTRA_REFERER         = "extra_referer"
        const val EXTRA_ORIGIN          = "extra_origin"
        const val EXTRA_COOKIES         = "extra_cookies"
        const val EXTRA_DOWNLOAD_ID     = "extra_download_id"
        const val EXTRA_SOURCE          = "extra_source"
        const val EXTRA_FORCE_EXTENSION = "extra_force_extension"

        /** Builds an intent to start a new download. */
        fun createStartIntent(
            context: Context,
            url: String,
            fileName: String,
            mimeType: String?,
            userAgent: String?,
            referer: String?,
            origin: String?,
            cookies: String?,
            source: String,
            forceExtension: String? = null
        ): Intent = Intent(context, DownloadService::class.java).apply {
            action = ACTION_START_DOWNLOAD
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_FILE_NAME, fileName)
            putExtra(EXTRA_MIME_TYPE, mimeType)
            putExtra(EXTRA_USER_AGENT, userAgent)
            putExtra(EXTRA_REFERER, referer)
            putExtra(EXTRA_ORIGIN, origin)
            putExtra(EXTRA_COOKIES, cookies)
            putExtra(EXTRA_SOURCE, source)
            putExtra(EXTRA_FORCE_EXTENSION, forceExtension)
        }

        /** Builds a control intent (pause / resume / cancel / retry). */
        fun createControlIntent(context: Context, action: String, downloadId: Long): Intent =
            Intent(context, DownloadService::class.java).apply {
                this.action = action
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
    }

    // ===================================================================
    //  Lifecycle
    // ===================================================================

    override fun onCreate() {
        super.onCreate()

        // Hands the service reference to the repository so it can satisfy the
        // Android 12+ 5-second startForeground contract and own any
        // Service-scoped notification posting.
        repository.attachService(this)

        Log.d(TAG, "DownloadService created (custom engine)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD  -> handleStartDownload(intent)
            ACTION_PAUSE_DOWNLOAD  -> handleControlIntent(intent, Control.PAUSE)
            ACTION_RESUME_DOWNLOAD -> handleControlIntent(intent, Control.RESUME)
            ACTION_CANCEL_DOWNLOAD -> handleControlIntent(intent, Control.CANCEL)
            ACTION_REMOVE_DOWNLOAD -> handleControlIntent(intent, Control.REMOVE)
            ACTION_RETRY_DOWNLOAD  -> handleControlIntent(intent, Control.RETRY)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        repository.detachService()
        Log.d(TAG, "DownloadService destroyed")
    }

    /**
     * Called by the system when the [android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC]
     * foreground service has exhausted its 6-hour daily quota (Android 15+).
     * We must call [stopSelf] promptly — the system throws a fatal
     * [android.app.RemoteServiceException] if we don't.
     */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Log.w(TAG, "dataSync FGS quota exhausted — stopping service (startId=$startId)")
            stopSelf(startId)
        }
    }

    // ===================================================================
    //  Intent handlers (thin adapters over the repository)
    // ===================================================================

    private enum class Control { PAUSE, RESUME, CANCEL, REMOVE, RETRY }

    private fun handleStartDownload(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        val request = DownloadRequest(
            url = url,
            fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                ?: "download_${System.currentTimeMillis()}",
            mimeType = intent.getStringExtra(EXTRA_MIME_TYPE),
            userAgent = intent.getStringExtra(EXTRA_USER_AGENT),
            referer = intent.getStringExtra(EXTRA_REFERER),
            origin = intent.getStringExtra(EXTRA_ORIGIN),
            cookies = intent.getStringExtra(EXTRA_COOKIES),
            source = intent.getStringExtra(EXTRA_SOURCE) ?: "UNKNOWN",
            forceExtension = intent.getStringExtra(EXTRA_FORCE_EXTENSION)
        )
        appScope.launch { repository.start(request) }
    }

    private fun handleControlIntent(intent: Intent, control: Control) {
        val id = intent.getLongExtra(EXTRA_DOWNLOAD_ID, -1L)
        if (id == -1L) return
        appScope.launch {
            when (control) {
                Control.PAUSE  -> repository.pause(id)
                Control.RESUME -> repository.resume(id)
                Control.CANCEL -> repository.cancel(id)
                Control.REMOVE -> repository.remove(id)
                Control.RETRY  -> repository.retry(id)
            }
        }
    }
}
