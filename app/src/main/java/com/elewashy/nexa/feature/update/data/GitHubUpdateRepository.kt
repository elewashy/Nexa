package com.elewashy.nexa.feature.update.data

import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.update.data.dto.GitHubReleaseDto
import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitHubUpdateRepository @Inject constructor(
    private val httpClientProvider: HttpClientProvider,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UpdateRepository {

    private val gson = Gson()

    override suspend fun getLatestRelease(includePrereleases: Boolean): ReleaseInfo =
        withContext(ioDispatcher) {
            if (includePrereleases) {
                val releases = fetchReleases()
                val release = releases.firstOrNull { !it.draft }
                    ?: throw Exception("No releases found")
                release.toReleaseInfo()
            } else {
                val request = Request.Builder()
                    .url("$API_BASE/releases/latest")
                    .githubHeaders()
                    .get()
                    .build()

                httpClientProvider.client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("HTTP ${response.code}")
                    }
                    val body = response.body.string()
                    gson.fromJson(body, GitHubReleaseDto::class.java)
                        ?.toReleaseInfo()
                        ?: throw Exception("Failed to parse release")
                }
            }
        }

    override suspend fun getReleases(includePrereleases: Boolean): List<ReleaseHistoryEntry> =
        withContext(ioDispatcher) {
            val releases = fetchReleases()
            releases
                .filter { !it.draft && (includePrereleases || !it.prerelease) }
                .map { it.toReleaseHistoryEntry() }
        }

    private suspend fun fetchReleases(): List<GitHubReleaseDto> = withContext(ioDispatcher) {
        val request = Request.Builder()
            .url("$API_BASE/releases?per_page=$PER_PAGE")
            .githubHeaders()
            .get()
            .build()

        httpClientProvider.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }
            val body = response.body.string()
            val type = object : TypeToken<List<GitHubReleaseDto>>() {}.type
            gson.fromJson<List<GitHubReleaseDto>>(body, type) ?: emptyList()
        }
    }

    private fun GitHubReleaseDto.toReleaseInfo(): ReleaseInfo {
        val apkAsset = assets.firstOrNull { asset ->
            asset.name.equals("Nexa_V${tagName.removePrefix("v")}.apk", ignoreCase = true)
        } ?: assets.singleOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true)
        }
            ?: throw Exception("No APK asset found in release")
        return ReleaseInfo(
            version = tagName.removePrefix("v"),
            downloadUrl = apkAsset.browserDownloadUrl,
            releaseNotes = body.orEmpty(),
            createdAt = Instant.parse(publishedAt ?: createdAt),
            fileSize = apkAsset.size
        )
    }

    private fun GitHubReleaseDto.toReleaseHistoryEntry(): ReleaseHistoryEntry =
        ReleaseHistoryEntry(
            version = tagName.removePrefix("v"),
            description = body.orEmpty(),
            createdAt = Instant.parse(publishedAt ?: createdAt)
        )

    private fun Request.Builder.githubHeaders(): Request.Builder =
        header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Nexa/${BuildConfig.VERSION_NAME}")

    private companion object {
        const val API_BASE = "https://api.github.com/repos/elewashy/Nexa"
        const val PER_PAGE = 100
    }
}
