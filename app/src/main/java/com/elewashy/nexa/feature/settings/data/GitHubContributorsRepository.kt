package com.elewashy.nexa.feature.settings.data

import com.elewashy.nexa.core.network.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** Thrown when GitHub rejects the contributors request with an exhausted rate limit. */
class ContributorsRateLimitedException :
    Exception("GitHub rate limit reached, please try again later")

/**
 * Fetches the repository contributors from the GitHub API.
 * Results are cached for the process lifetime; pass `refresh = true` to
 * bypass the cache (there is no TTL — retries after a failure or a manual
 * refresh use it).
 */
@Singleton
class GitHubContributorsRepository @Inject constructor(
    httpClientProvider: HttpClientProvider,
) {

    data class Contributor(
        val username: String,
        val avatarUrl: String,
        val profileUrl: String,
    )

    private val client = httpClientProvider.newBuilder().build()

    @Volatile
    private var cache: List<Contributor>? = null

    suspend fun getContributors(refresh: Boolean = false): List<Contributor> {
        if (!refresh) cache?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!refresh) cache?.let { return@withContext it }

            val request = Request.Builder()
                .url(CONTRIBUTORS_API_URL)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .build()

            val contributors = client.newCall(request).execute().use { response ->
                when {
                    // 429 is always rate limiting; a 403 only when GitHub marks
                    // the rate budget exhausted — callers can surface a retryable,
                    // distinguishable failure for these.
                    response.code == 429 ||
                        (response.code == 403 && response.header("x-ratelimit-remaining") == "0") ->
                        throw ContributorsRateLimitedException()
                    !response.isSuccessful ->
                        throw IllegalStateException("GitHub request failed: ${response.code}")
                    else -> parseContributors(response.body.string())
                }
            }

            contributors.also { cache = it }
        }
    }

    private fun parseContributors(json: String): List<Contributor> {
        val array = org.json.JSONArray(json)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                val entry = array.getJSONObject(i)
                val username = entry.optString("login")
                if (username.isBlank()) continue
                add(
                    Contributor(
                        username = username,
                        avatarUrl = entry.optString("avatar_url"),
                        profileUrl = entry.optString("html_url").ifBlank { "https://github.com/$username" },
                    )
                )
            }
        }
    }

    private companion object {
        const val REPO = "elewashy/Nexa"
        const val CONTRIBUTOR_API_BASE = "https://api.github.com/repos/"
        const val USER_AGENT = "Nexa"
        val CONTRIBUTORS_API_URL = "$CONTRIBUTOR_API_BASE$REPO/contributors?per_page=30"
    }
}
