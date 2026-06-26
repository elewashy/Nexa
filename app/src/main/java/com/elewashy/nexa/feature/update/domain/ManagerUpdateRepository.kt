package com.elewashy.nexa.feature.update.domain

import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManagerUpdateRepository @Inject constructor(
    private val updateRepository: UpdateRepository
) {
    private val _releasedAt = MutableStateFlow<Instant?>(null)
    private val _version = MutableStateFlow<String?>(null)
    private val _hasUpdate = MutableStateFlow(false)
    private var cachedRelease: ReleaseInfo? = null

    val releasedAt: StateFlow<Instant?> = _releasedAt.asStateFlow()
    val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()
    val version: StateFlow<String?> = _version.asStateFlow()

    suspend fun refresh(includePrereleases: Boolean = false): ReleaseInfo {
        val release = updateRepository.getLatestRelease(includePrereleases)
        _releasedAt.value = release.createdAt
        _version.value = release.version
        _hasUpdate.value = compareVersions(release.version, currentVersion()) > 0
        cachedRelease = release
        return release
    }

    suspend fun getUpdateOrNull(refetch: Boolean = false): ReleaseInfo? {
        if (refetch || cachedRelease == null) refresh()
        return cachedRelease?.takeIf { _hasUpdate.value }
    }

    fun clearState() {
        _releasedAt.value = null
        _version.value = null
        _hasUpdate.value = false
        cachedRelease = null
    }

    private fun currentVersion(): String =
        BuildConfig.VERSION_NAME.replace(Regex("-.*$"), "")

    private fun compareVersions(latestVersion: String, currentVersion: String): Int {
        val latest = latestVersion.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val current = currentVersion.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(latest.size, current.size)
        for (index in 0 until maxLength) {
            val latestPart = latest.getOrElse(index) { 0 }
            val currentPart = current.getOrElse(index) { 0 }
            if (latestPart != currentPart) return latestPart.compareTo(currentPart)
        }
        return 0
    }
}
