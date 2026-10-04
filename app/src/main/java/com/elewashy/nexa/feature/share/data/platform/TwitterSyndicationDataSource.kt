package com.elewashy.nexa.feature.share.data.platform

import com.elewashy.nexa.core.common.KeyedLoadingCache
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches tweets from X's syndication endpoint ([TwitterSyndication]) with a
 * small in-memory cache.
 *
 * The browser asks about a tweet when it is opened (to decide whether to
 * show the download button) and the share sheet asks again when the user
 * taps it; the cache makes the second lookup free. Lookups of the same tweet
 * are deduplicated (a tap during an in-flight check waits for it instead of
 * issuing a duplicate request); lookups of different tweets run in parallel.
 */
@Singleton
internal class TwitterSyndicationDataSource @Inject constructor(
    private val support: ShareExtractionSupport,
) {
    private val cache = KeyedLoadingCache<String, SyndicatedTweet?>(maxEntries = MAX_ENTRIES, ttlMs = TTL_MS)

    /**
     * The tweet's media, or null when X reports the tweet unavailable
     * (deleted, protected, age-restricted). Throws [IOException] when X
     * cannot be reached; failures are not cached.
     */
    suspend fun tweet(tweetId: String): SyndicatedTweet? = cache.get(tweetId, ::fetch)

    private suspend fun fetch(tweetId: String): SyndicatedTweet? {
        val request = Request.Builder()
            .url(TwitterSyndication.requestUrl(tweetId))
            .header("User-Agent", ShareExtractionSupport.USER_AGENT_DESKTOP)
            .header("Accept", "application/json")
            .build()
        return support.execute(request).use { response ->
            when {
                response.isSuccessful -> TwitterSyndication.parse(response.body.string())
                // The tweet does not exist or is not embeddable. Anything else
                // (429, 5xx) is transient and must not be cached as "unavailable".
                response.code == 404 || response.code == 410 -> null
                else -> throw IOException("Syndication HTTP ${response.code}")
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 32

        /** Long enough to span "open tweet → tap download", short enough to notice edits and deletions. */
        const val TTL_MS = 10 * 60 * 1000L
    }
}
