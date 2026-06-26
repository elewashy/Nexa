package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the repository's sorted snapshot stream to the presentation layer.
 *
 * Consumers (ViewModels) collect this as the single source of truth for the
 * downloads list. The `StateFlow` contract guarantees a cached latest value,
 * so new subscribers receive the current list immediately without waiting
 * for the next repository emission.
 */
class ObserveDownloadsUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    operator fun invoke(): StateFlow<List<DownloadItem>> = repository.downloads
}
