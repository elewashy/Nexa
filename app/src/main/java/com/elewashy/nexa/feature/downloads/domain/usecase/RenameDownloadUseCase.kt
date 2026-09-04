package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.data.RenameDownloadResult
import javax.inject.Inject

class RenameDownloadUseCase @Inject constructor(
    private val repository: DownloadRepository,
) {
    suspend operator fun invoke(id: Long, name: String): RenameDownloadResult =
        repository.renameCompleted(id, name)
}
