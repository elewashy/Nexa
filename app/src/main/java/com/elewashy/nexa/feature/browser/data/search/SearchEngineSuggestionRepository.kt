package com.elewashy.nexa.feature.browser.data.search

import com.elewashy.nexa.core.network.HttpClientProvider
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume

/**
 * Autocomplete client that delegates to the currently selected search engine.
 * Calls are cancellation-aware and failures intentionally degrade to local
 * history rather than surfacing an error in the typing flow.
 */
@Singleton
class SearchEngineSuggestionRepository @Inject constructor(
    clientProvider: HttpClientProvider,
    private val appPreferences: AppPreferences,
) : SearchSuggestionRepository {
    private val client = clientProvider.newBuilder()
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun suggestions(query: String, limit: Int): List<String> {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.length < MIN_QUERY_LENGTH) return emptyList()

        val endpoint = SearchEngine.fromStoredValue(appPreferences.selectedSearchEngine.first()).suggestionEndpoint
        val request = Request.Builder()
            .url(endpoint.url(normalized))
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).awaitBody()?.let { body ->
                SearchSuggestionParser.parse(endpoint.format, body, normalized, limit)
            }.orEmpty()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun Call.awaitBody(): String? = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = if (it.isSuccessful) {
                        val source = it.body.source()
                        source.request(MAX_RESPONSE_BYTES + 1L)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) null else source.buffer.clone().readUtf8()
                    } else {
                        null
                    }
                    if (continuation.isActive) continuation.resume(body)
                }
            }
        })
    }

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 3L
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 256
        const val MAX_RESPONSE_BYTES = 64 * 1024L
    }
}
