package com.elewashy.nexa.feature.update.domain

import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.feature.update.data.UpdateRepository
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManagerUpdateRepository @Inject constructor(
    private val updateRepository: UpdateRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {
    private val _releasedAt = MutableStateFlow<Instant?>(null)
    private val _version = MutableStateFlow<String?>(null)
    private val _hasUpdate = MutableStateFlow(false)

    // Read from multiple coroutines (splash check, update screen, settings);
    // written only in refresh()/clearState().
    @Volatile
    private var cachedRelease: ReleaseInfo? = null

    // Concurrent refresh() callers (splash + settings) share one in-flight
    // fetch instead of hitting the same endpoint twice.
    private val refreshMutex = Mutex()
    private var inflightRefresh: Deferred<ReleaseInfo>? = null

    val releasedAt: StateFlow<Instant?> = _releasedAt.asStateFlow()
    val hasUpdate: StateFlow<Boolean> = _hasUpdate.asStateFlow()
    val version: StateFlow<String?> = _version.asStateFlow()

    suspend fun refresh(includePrereleases: Boolean = false): ReleaseInfo {
        val deferred = refreshMutex.withLock {
            // A completed deferred (success or failure) always triggers a new
            // fetch; only a genuinely in-flight one is shared.
            inflightRefresh?.takeUnless { it.isCompleted }
                ?: appScope.async { doRefresh(includePrereleases) }
                    .also { inflightRefresh = it }
        }
        return deferred.await()
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

    private suspend fun doRefresh(includePrereleases: Boolean): ReleaseInfo {
        val release = updateRepository.getLatestRelease(includePrereleases)
        _releasedAt.value = release.createdAt
        _version.value = release.version
        _hasUpdate.value = compareVersions(release.version, currentVersion()) > 0
        cachedRelease = release
        return release
    }

    private fun currentVersion(): String =
        BuildConfig.VERSION_NAME.replace(Regex("-.*$"), "")

    companion object {
        /**
         * Compares two dotted version strings numerically segment by segment,
         * ignoring a leading lowercase `v`. Non-numeric segments compare as 0
         * and missing trailing segments compare as 0 (so `1.2` == `1.2.0`).
         *
         * Returns a negative value if [latestVersion] < [currentVersion],
         * zero if equal, and a positive value if it is greater.
         *
         * Pure function — testable without Android.
         */
        internal fun compareVersions(latestVersion: String, currentVersion: String): Int {
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
}
