package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import javax.inject.Inject

/** Removes a download from the list while keeping the on-disk file. */
class RemoveDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.remove(id)
}
