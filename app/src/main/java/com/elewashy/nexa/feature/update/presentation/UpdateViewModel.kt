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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.ChangelogsRepository
import com.elewashy.nexa.feature.update.domain.ManagerUpdateRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import javax.inject.Inject

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

    val changelogs: Flow<PagingData<ReleaseHistoryEntry>> = Pager(
        config = PagingConfig(pageSize = 10, enablePlaceholders = false),
        pagingSourceFactory = { ChangelogsRepository(updateRepository, includePrereleases = false) }
    ).flow.cachedIn(viewModelScope)

    private val updateDir = File(appContext.filesDir, "update").apply { mkdirs() }

    private var downloadJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                releaseInfo = managerUpdateRepository.getUpdateOrNull()
                    ?: throw Exception("No update available")

                val apkFile = apkFile()
                if (apkFile.exists() && apkFile.length() > 0) {
                    state = State.CAN_INSTALL
                } else {
                    state = State.CAN_DOWNLOAD
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch update info", e)
                state = State.FAILED
                errorMessage = e.message
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
                    apkFile.parentFile?.mkdirs()
                    apkFile.delete()
                    partialFile.delete()

                    val request = Request.Builder()
                        .url(release.downloadUrl)
                        .get()
                        .build()

                    httpClientProvider.client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw Exception("HTTP ${response.code}")
                        }
                        val body = response.body
                        val contentLength = body.contentLength()
                        if (contentLength > 0) totalSize = contentLength

                        partialFile.outputStream().use { output ->
                            val input = body.byteStream()
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var bytesRead: Int
                            var totalRead = 0L
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                totalRead += bytesRead
                                downloadedSize = totalRead
                            }
                            output.flush()
                        }

                        if (contentLength > 0 && partialFile.length() != contentLength) {
                            throw Exception("Downloaded size does not match content length")
                        }

                        if (!partialFile.renameTo(apkFile)) {
                            throw Exception("Failed to finalize update APK")
                        }
                    }
                }
                state = State.CAN_INSTALL
            } catch (e: CancellationException) {
                partialApkFile().delete()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to download update", e)
                withContext(ioDispatcher) {
                    partialApkFile().delete()
                    apkFile().delete()
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
        downloadJob?.cancel()
        apkFile().delete()
        partialApkFile().delete()
        downloadedSize = 0L
        totalSize = 0L
        state = State.CAN_DOWNLOAD
    }

    fun getDownloadedApkFile(): File? {
        val file = apkFile()
        return if (file.exists() && file.length() > 0) file else null
    }

    private fun apkFile(): File =
        File(updateDir, "Nexa_V${releaseInfo?.version ?: "update"}.apk")

    private fun partialApkFile(): File =
        File(updateDir, "Nexa_V${releaseInfo?.version ?: "update"}.apk.part")

    override fun onCleared() {
        super.onCleared()
        downloadJob?.cancel()
        partialApkFile().delete()
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
    }
}
