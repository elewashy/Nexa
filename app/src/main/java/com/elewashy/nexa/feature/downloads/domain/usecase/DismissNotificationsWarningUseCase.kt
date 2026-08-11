package com.elewashy.nexa.feature.downloads.domain.usecase

import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import javax.inject.Inject

/** Dismisses the one-time "notifications are disabled" warning. */
class DismissNotificationsWarningUseCase @Inject constructor(
    private val repository: DownloadRepository
) {
    operator fun invoke() = repository.dismissNotificationsWarning()
}
