package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import javax.inject.Inject

/** Retry is semantically identical to resume — re-enqueued with failureCount reset. */
class RetryDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.retry(id)
}
