package com.elewashy.nexa.feature.share.data

import android.util.Log
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.feature.share.data.platform.TwitterSyndicationDataSource
import com.elewashy.nexa.feature.share.domain.model.MediaPage
import com.elewashy.nexa.feature.share.domain.model.SharePlatform
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * X answers from its syndication endpoint (the data source tweet
 * extraction uses). Threads and Facebook have no lightweight logged-out
 * source, so they stay unknown and rely on the in-page probe.
 */
@Singleton
internal class DefaultMediaAvailabilityRepository @Inject constructor(
    private val tweets: TwitterSyndicationDataSource,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : MediaAvailabilityRepository {

    override suspend fun hasDownloadableMedia(page: MediaPage): Boolean? = when (page.platform) {
        SharePlatform.TWITTER -> withContext(ioDispatcher) {
            try {
                tweets.tweet(page.contentId)?.hasMedia
            } catch (e: IOException) {
                Log.d(TAG, "Tweet lookup failed: ${e.message}")
                null
            }
        }
        else -> null
    }

    private companion object {
        const val TAG = "MediaAvailability"
    }
}
