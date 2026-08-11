package com.elewashy.nexa.feature.update.presentation

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.update.data.UpdateArtifactVerifier
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.ChangelogsRepository
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import javax.inject.Inject

/** Thrown when a downloaded update fails integrity verification. */
class UpdateVerificationException(message: String) : Exception(message)

/** Thrown when the metadata receipt for a downloaded update cannot be written. */
class UpdateMetadataException(cause: Throwable) : Exception(cause)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val managerUpdateRepository: ManagerUpdateRepository,
    private val updateRepository: UpdateRepository,
    private val httpClientProvider: HttpClientProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    var downloadedSize by mutableLongStateOf(0L)
        private set

    var totalSize by mutableLongStateOf(0L)
        private set

    val downloadProgress by derivedStateOf {
        if (downloadedSize == 0L || totalSize == 0L) 0f
        else (downloadedSize.toFloat() / totalSize.toFloat()).coerceIn(0f, 1f)
    }

    var state by mutableStateOf(State.CAN_DOWNLOAD)
        private set

    var releaseInfo by mutableStateOf<ReleaseInfo?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var changelogsState by mutableStateOf<ChangelogsUiState>(ChangelogsUiState.Loading)
        private set

    private val updateDir = File(appContext.filesDir, "update").apply { mkdirs() }

    /**
     * User-facing verification errors, shown raw in the UI. Localized at
     * construction (application context is locale-stable for the process).
     */
    private val ERR_CHECKSUM_MISMATCH =
        appContext.getString(R.string.update_verification_failed_checksum)
    private val ERR_CHECKSUM_UNPARSEABLE =
        appContext.getString(R.string.update_verification_failed_unparseable_checksum)
    private val ERR_SIGNATURE_MISMATCH =
        appContext.getString(R.string.update_verification_failed_signature)

    private var downloadJob: Job? = null

    // The blocking OkHttp call of the in-flight download, if any. cancel()
    // interrupts the blocking read loop, which coroutine cancellation alone
    // cannot do.
    @Volatile
    private var activeCall: Call? = null

    init {
        viewModelScope.launch {
            try {
                releaseInfo = managerUpdateRepository.getUpdateOrNull()
                    ?: throw Exception(appContext.getString(R.string.app_up_to_date))

                val readyToInstall = withContext(ioDispatcher) {
                    cleanOrphanFiles()
                    verifyStoredUpdate()
                }
                state = if (readyToInstall) State.CAN_INSTALL else State.CAN_DOWNLOAD
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch update info", e)
                state = State.FAILED
                errorMessage = e.message
            }
        }
        loadChangelogs()
    }

    private fun loadChangelogs() {
        viewModelScope.launch {
            changelogsState = ChangelogsUiState.Loading
            changelogsState = try {
                val releases = ChangelogsRepository(updateRepository, includePrereleases = false)
                    .getReleases()
                ChangelogsUiState.Loaded(releases)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load changelogs", e)
                ChangelogsUiState.Error(e.message)
            }
        }
    }

    fun downloadUpdate() {
        val release = releaseInfo ?: return
        if (state == State.DOWNLOADING) return

        state = State.DOWNLOADING
        errorMessage = null
        downloadedSize = 0L
        totalSize = release.fileSize.takeIf { it > 0 } ?: 0L

        downloadJob = viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    val apkFile = apkFile()
                    val partialFile = partialApkFile()
                    val metaFile = metadataFile()
                    apkFile.parentFile?.mkdirs()
                    apkFile.delete()
                    metaFile.delete()

                    downloadApkWithResume(release.downloadUrl, partialFile)

                    // The blocking read loop above cannot be interrupted by
                    // coroutine cancellation; a cancel that arrived mid-download
                    // must not finalize the APK.
                    currentCoroutineContext().ensureActive()

                    val actualSha256 = UpdateArtifactVerifier.sha256Hex(partialFile)
                    // Match against the final APK name (checksum assets reference
                    // "<name>.apk", never the transient ".part" file).
                    val verifiedByChecksum = verifyChecksum(release, apkFile.name, actualSha256)

                    // Signing certificate must match the installed app even when
                    // the checksum check passed — the checksum proves origin, the
                    // certificate proves the APK can replace the running app.
                    if (!UpdateArtifactVerifier.isSignedByInstalledAppSigner(appContext, partialFile)) {
                        throw UpdateVerificationException(ERR_SIGNATURE_MISMATCH)
                    }

                    // Write the metadata BEFORE the rename: if this fails (e.g.
                    // disk full) the download is still the .part file, so the
                    // error paths below can never delete a fully-verified APK.
                    try {
                        writeMetadata(metaFile, release.version, actualSha256, verifiedByChecksum)
                    } catch (e: Exception) {
                        metaFile.delete()
                        throw UpdateMetadataException(e)
                    }

                    currentCoroutineContext().ensureActive()

                    if (!partialFile.renameTo(apkFile)) {
                        metaFile.delete()
                        throw Exception("Failed to finalize update APK")
                    }
                }
                currentCoroutineContext().ensureActive()
                state = State.CAN_INSTALL
            } catch (e: CancellationException) {
                // Keep the partial file so the next attempt resumes via HTTP Range.
                throw e
            } catch (e: UpdateVerificationException) {
                Log.e(TAG, "Update verification failed", e)
                deleteAllUpdateFiles()
                state = State.FAILED
                errorMessage = e.message
            } catch (e: UpdateMetadataException) {
                // Only the receipt write failed: keep the downloaded .part so
                // the retry resumes instead of re-downloading. Never delete a
                // verified download here.
                Log.e(TAG, "Failed to write update metadata", e)
                state = State.FAILED
                errorMessage = appContext.getString(R.string.download_update_failed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update", e)
                // A cancelled download must not be reported as a failure:
                // cancelUpdate() owns the state transition.
                if (!currentCoroutineContext().isActive) return@launch
                withContext(ioDispatcher) {
                    // Never leave a finalized APK/meta behind after a failure; the
                    // partial file is kept so the download can resume.
                    apkFile().delete()
                    metadataFile().delete()
                }
                state = State.FAILED
                errorMessage = appContext.getString(R.string.download_update_failed)
            }
        }
    }

    fun retryDownload() {
        downloadUpdate()
    }

    fun cancelUpdate() {
        val job = downloadJob
        job?.cancel()
        // Interrupt the blocking read loop directly: coroutine cancellation
        // alone cannot stop a blocking OkHttp read.
        activeCall?.cancel()

        downloadedSize = 0L
        totalSize = 0L
        state = State.CAN_DOWNLOAD

        viewModelScope.launch(ioDispatcher) {
            // Let the cancelled download unwind first; the download job checks
            // cancellation before rename/state transitions, so after join() it
            // can no longer flip state or finalize the APK under our cleanup.
            job?.join()
            apkFile().delete()
            metadataFile().delete()
            // Keep the .part file: the next download resumes it via HTTP Range.
        }
    }

    fun getDownloadedApkFile(): File? {
        val file = apkFile()
        return if (state == State.CAN_INSTALL && file.exists() && file.length() > 0) file else null
    }

    /**
     * Downloads [url] into [partialFile], resuming an existing partial file via
     * HTTP Range when the server supports it.
     */
    private fun downloadApkWithResume(url: String, partialFile: File) {
        var retriedAfterInvalidRange = false
        while (true) {
            val existingBytes = if (partialFile.exists()) partialFile.length() else 0L
            val request = Request.Builder()
                .url(url)
                .get()
                .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
                .build()

            var restartFromScratch = false
            val call = httpClientProvider.client.newCall(request)
            activeCall = call
            try {
                call.execute().use { response ->
                    when {
                        response.code == HTTP_RANGE_NOT_SATISFIABLE && existingBytes > 0 -> {
                            // Stale partial file (e.g. asset re-uploaded): restart once.
                            partialFile.delete()
                            restartFromScratch = !retriedAfterInvalidRange
                            retriedAfterInvalidRange = true
                        }
                        !response.isSuccessful -> throw Exception("HTTP ${response.code}")
                        else -> {
                            val body = response.body
                            val appending = response.code == HTTP_PARTIAL_CONTENT && existingBytes > 0
                            val expectedTotal = if (appending) {
                                contentRangeTotal(response.header("Content-Range"))
                                    ?: existingBytes + body.contentLength()
                            } else {
                                body.contentLength()
                            }
                            if (expectedTotal > 0) totalSize = expectedTotal
                            var totalRead = if (appending) existingBytes else 0L
                            downloadedSize = totalRead

                            FileOutputStream(partialFile, appending).use { output ->
                                val input = body.byteStream()
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                    totalRead += bytesRead
                                    downloadedSize = totalRead
                                }
                                output.flush()
                            }

                            if (expectedTotal > 0 && partialFile.length() != expectedTotal) {
                                throw Exception("Downloaded size does not match content length")
                            }
                            return
                        }
                    }
                }
            } finally {
                activeCall = null
            }
            if (!restartFromScratch) {
                throw Exception("HTTP $HTTP_RANGE_NOT_SATISFIABLE while resuming update download")
            }
        }
    }

    /**
     * Verifies the APK against the release's SHA-256 checksum asset.
     *
     * A release that PUBLISHES a checksum asset must produce a parseable,
     * matching digest for this APK — a declared-but-unusable checksum
     * (malformed content, no entry for this APK) fails verification instead
     * of silently downgrading to cert-only. Only a release with no checksum
     * asset at all may proceed UNVERIFIED by hash (signing-certificate check
     * still applies; a warning is logged).
     *
     * @throws UpdateVerificationException when a published checksum mismatches
     *   or cannot be parsed for this APK.
     */
    private fun verifyChecksum(release: ReleaseInfo, apkName: String, actualSha256: String): Boolean {
        val checksumUrl = release.checksumUrl ?: run {
            Log.w(TAG, "Release ${release.version} has no checksum asset; update UNVERIFIED by hash")
            return false
        }

        val request = Request.Builder().url(checksumUrl).get().build()
        val content = httpClientProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} while fetching checksum asset")
            }
            response.body.string()
        }

        val expectedSha256 = UpdateArtifactVerifier.parseExpectedSha256(content, apkName) ?: run {
            // Fail closed: the release declared a checksum asset, so an
            // unparseable one must never degrade to "unverified by hash".
            Log.e(TAG, "Checksum asset has no parseable entry for $apkName")
            throw UpdateVerificationException(ERR_CHECKSUM_UNPARSEABLE)
        }
        if (!expectedSha256.equals(actualSha256, ignoreCase = true)) {
            throw UpdateVerificationException(ERR_CHECKSUM_MISMATCH)
        }
        return true
    }

    /**
     * Deletes everything except a partial file that still belongs to the
     * current release, so stale/restored files in filesDir/update are never
     * trusted and interrupted downloads can resume.
     */
    private fun cleanOrphanFiles() {
        val allowed = setOf(apkFile().name, partialApkFile().name, metadataFile().name)
        updateDir.listFiles()?.forEach { file ->
            if (file.name !in allowed && !file.delete()) {
                Log.w(TAG, "Failed to delete orphan update file: ${file.name}")
            }
        }
    }

    /**
     * Re-verifies a previously downloaded APK before offering CAN_INSTALL:
     * metadata receipt present, version matches, SHA-256 recomputes to the
     * recorded digest, and the signing certificate still matches the
     * installed app.
     */
    private fun verifyStoredUpdate(): Boolean {
        val release = releaseInfo ?: return false
        val apk = apkFile()
        val meta = metadataFile()
        if (!apk.exists() || apk.length() == 0L) {
            meta.delete()
            return false
        }

        val metadata = readMetadata(meta)
        if (metadata == null || metadata.getProperty(KEY_VERSION) != release.version) {
            apk.delete()
            meta.delete()
            return false
        }

        val recordedSha = metadata.getProperty(KEY_SHA256)
        if (recordedSha.isNullOrBlank() ||
            !recordedSha.equals(UpdateArtifactVerifier.sha256Hex(apk), ignoreCase = true)
        ) {
            apk.delete()
            meta.delete()
            return false
        }

        if (!UpdateArtifactVerifier.isSignedByInstalledAppSigner(appContext, apk)) {
            apk.delete()
            meta.delete()
            return false
        }
        return true
    }

    private suspend fun deleteAllUpdateFiles() {
        withContext(ioDispatcher) {
            apkFile().delete()
            partialApkFile().delete()
            metadataFile().delete()
        }
    }

    private fun readMetadata(file: File): Properties? =
        if (!file.exists()) null
        else runCatching {
            file.inputStream().use { input -> Properties().apply { load(input) } }
        }.getOrNull()

    private fun writeMetadata(file: File, version: String, sha256: String, verifiedByChecksum: Boolean) {
        val properties = Properties().apply {
            setProperty(KEY_VERSION, version)
            setProperty(KEY_SHA256, sha256)
            setProperty(KEY_VERIFIED_BY_CHECKSUM, verifiedByChecksum.toString())
        }
        file.outputStream().use { output -> properties.store(output, null) }
    }

    /** Parses the total size from a `Content-Range: bytes a-b/total` header. */
    private fun contentRangeTotal(header: String?): Long? {
        val total = header?.substringAfterLast('/', "")?.trim()
        return total?.takeIf { it.isNotEmpty() && it != "*" }?.toLongOrNull()
    }

    private fun apkFile(): File =
        File(updateDir, "Nexa_V${releaseInfo?.version ?: "update"}.apk")

    private fun partialApkFile(): File =
        File(updateDir, "Nexa_V${releaseInfo?.version ?: "update"}.apk.part")

    private fun metadataFile(): File =
        File(updateDir, "Nexa_V${releaseInfo?.version ?: "update"}.apk.meta")

    override fun onCleared() {
        downloadJob?.cancel()
        activeCall?.cancel()
        // Partial file intentionally kept: the next visit resumes the download.
    }

    enum class State(@param:StringRes val title: Int) {
        CAN_DOWNLOAD(R.string.update_available),
        DOWNLOADING(R.string.downloading_update),
        CAN_INSTALL(R.string.ready_to_install_update),
        FAILED(R.string.update_failed),
    }

    private companion object {
        const val TAG = "UpdateViewModel"
        const val DEFAULT_BUFFER_SIZE = 8192
        const val HTTP_PARTIAL_CONTENT = 206
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        const val KEY_VERSION = "version"
        const val KEY_SHA256 = "sha256"
        const val KEY_VERIFIED_BY_CHECKSUM = "verifiedByChecksum"
    }
}
