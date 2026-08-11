package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Exposes the repository's one-time "notifications are disabled" warning to the
 * presentation layer. Non-null while a download was started with notifications
 * turned off — the user would otherwise get no feedback at all.
 */
class ObserveNotificationsWarningUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    operator fun invoke(): StateFlow<String?> = repository.notificationsWarning
}
