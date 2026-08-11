package com.elewashy.nexa.feature.update.data

import android.content.Context
import com.elewashy.nexa.BuildConfig
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.feature.update.data.dto.GitHubReleaseDto
import com.elewashy.nexa.feature.update.domain.model.ReleaseHistoryEntry
import com.elewashy.nexa.feature.update.domain.model.ReleaseInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when GitHub rate-limits the request: any 429, or a 403 with x-ratelimit-remaining: 0. */
class GitHubRateLimitedException :
    Exception("GitHub rate limit reached, please try again later")

@Singleton
class GitHubUpdateRepository @Inject constructor(
    @param:ApplicationContext context: Context,
    private val httpClientProvider: HttpClientProvider,
    private val gson: Gson,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : UpdateRepository {

    private val etagCache = EtagCache(File(context.cacheDir, "github-api-cache"))

    @Volatile
    private var releasesCache: List<GitHubReleaseDto>? = null

    override suspend fun getLatestRelease(includePrereleases: Boolean): ReleaseInfo =
        withContext(ioDispatcher) {
            if (includePrereleases) {
                val releases = fetchReleasesFromNetwork()
                val release = releases.firstOrNull { !it.draft }
                    ?: throw Exception("No releases found")
                release.toReleaseInfo()
            } else {
                val body = executeGithubGet("$API_BASE/releases/latest")
                gson.fromJson(body, GitHubReleaseDto::class.java)
                    ?.toReleaseInfo()
                    ?: throw Exception("Failed to parse release")
            }
        }

    override suspend fun getReleases(includePrereleases: Boolean): List<ReleaseHistoryEntry> =
        withContext(ioDispatcher) {
            val releases = releasesCache ?: fetchReleasesFromNetwork().also { releasesCache = it }
            releases
                .filter { !it.draft && (includePrereleases || !it.prerelease) }
                // One malformed release must not fail the whole changelog list.
                .mapNotNull { release ->
                    runCatching { release.toReleaseHistoryEntry() }.getOrNull()
                }
        }

    private fun fetchReleasesFromNetwork(): List<GitHubReleaseDto> {
        val body = executeGithubGet("$API_BASE/releases?per_page=$PER_PAGE")
        val type = object : TypeToken<List<GitHubReleaseDto>>() {}.type
        return gson.fromJson<List<GitHubReleaseDto>>(body, type) ?: emptyList()
    }

    /**
     * GET with ETag revalidation. Conditional requests returning 304 are free
     * against GitHub's 60 req/h unauthenticated budget, so every endpoint is
     * cached on disk (etag + body) and revalidated instead of refetched.
     */
    private fun executeGithubGet(url: String): String {
        val cached = etagCache.read(url)
        val request = Request.Builder()
            .url(url)
            .githubHeaders()
            .apply { cached?.let { header("If-None-Match", it.etag) } }
            .get()
            .build()

        httpClientProvider.client.newCall(request).execute().use { response ->
            when {
                response.code == 304 -> {
                    return cached?.body ?: throw Exception("ETag cache lost for $url")
                }
                // 429 is always rate limiting. A 403 only is when GitHub marks
                // the rate budget exhausted (x-ratelimit-remaining: 0); other
                // 403s (abuse detection, IP blocks, SSO) are generic failures.
                response.code == 429 ||
                    (response.code == 403 && response.header("x-ratelimit-remaining") == "0") -> {
                    throw GitHubRateLimitedException()
                }
                !response.isSuccessful -> throw Exception("HTTP ${response.code}")
            }
            val body = response.body.string()
            response.header("ETag")?.let { etag -> etagCache.write(url, etag, body) }
            return body
        }
    }

    private fun GitHubReleaseDto.toReleaseInfo(): ReleaseInfo {
        val apkAsset = assets.firstOrNull { asset ->
            asset.name.equals("Nexa_V${tagName.removePrefix("v")}.apk", ignoreCase = true)
        } ?: assets.singleOrNull { asset ->
            asset.name.endsWith(".apk", ignoreCase = true)
        }
            ?: throw Exception("No APK asset found in release")
        // Prefer the per-APK checksum file, then the generic multi-entry files.
        val checksumAsset = assets.firstOrNull { asset ->
            asset.name.equals("${apkAsset.name}.sha256", ignoreCase = true)
        } ?: assets.firstOrNull { asset ->
            asset.name.equals("checksums.txt", ignoreCase = true) ||
                asset.name.equals("SHA256SUMS", ignoreCase = true)
        }
        return ReleaseInfo(
            version = tagName.removePrefix("v"),
            downloadUrl = apkAsset.browserDownloadUrl,
            releaseNotes = body.orEmpty(),
            createdAt = parseInstant(publishedAt, createdAt),
            fileSize = apkAsset.size,
            checksumUrl = checksumAsset?.browserDownloadUrl,
        )
    }

    private fun GitHubReleaseDto.toReleaseHistoryEntry(): ReleaseHistoryEntry =
        ReleaseHistoryEntry(
            version = tagName.removePrefix("v"),
            description = body.orEmpty(),
            createdAt = parseInstant(publishedAt, createdAt)
        )

    /**
     * Malformed timestamps fall back to null (UI hides the date) instead of
     * failing the whole check or showing a bogus epoch-relative time.
     */
    private fun parseInstant(publishedAt: String?, createdAt: String): Instant? =
        runCatching { Instant.parse(publishedAt) }.getOrNull()
            ?: runCatching { Instant.parse(createdAt) }.getOrNull()

    private fun Request.Builder.githubHeaders(): Request.Builder =
        header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Nexa/${BuildConfig.VERSION_NAME}")

    /** Per-endpoint ETag + body cache, keyed by a hash of the URL. */
    private class EtagCache(dir: File) {

        private val dir = dir.apply { mkdirs() }

        class Entry(val etag: String, val body: String)

        fun read(url: String): Entry? {
            val key = key(url)
            val etag = runCatching { File(dir, "$key.etag").readText() }.getOrNull()
            if (etag.isNullOrBlank()) return null
            val body = runCatching { File(dir, "$key.body").readText() }.getOrNull()
                ?: return null
            return Entry(etag, body)
        }

        fun write(url: String, etag: String, body: String) {
            runCatching {
                val key = key(url)
                File(dir, "$key.body").writeText(body)
                File(dir, "$key.etag").writeText(etag)
            }
        }

        private fun key(url: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(url.toByteArray())
                .joinToString(separator = "") { "%02x".format(it) }
    }

    private companion object {
        const val API_BASE = "https://api.github.com/repos/elewashy/Nexa"
        const val PER_PAGE = 30
    }
}
