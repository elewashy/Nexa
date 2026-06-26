package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.domain.model.DownloadRequest
import javax.inject.Inject

/**
 * Enqueues a new download.
 *
 * The repository is responsible for duplicate-URL detection and asynchronous
 * filename resolution before the download actually begins. This use case is a
 * thin adapter so the ViewModel does not depend on the repository directly.
 */
class StartDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(request: DownloadRequest) = repository.start(request)
}
