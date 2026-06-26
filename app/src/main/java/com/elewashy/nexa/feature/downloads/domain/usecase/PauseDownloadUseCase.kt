package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import javax.inject.Inject

/** Pauses a DOWNLOADING or PENDING item. No-op for any other status. */
class PauseDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    suspend operator fun invoke(id: Long) = repository.pause(id)
}
