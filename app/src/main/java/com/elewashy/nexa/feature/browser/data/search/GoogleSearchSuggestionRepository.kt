package com.elewashy.nexa.feature.browser.data.search

import com.elewashy.nexa.core.network.HttpClientProvider
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import kotlin.coroutines.resume

/**
 * Lightweight Google autocomplete client. Calls are cancellation-aware and failures intentionally
 * degrade to local history rather than surfacing an error in the typing flow.
 */
@Singleton
class GoogleSearchSuggestionRepository @Inject constructor(
    clientProvider: HttpClientProvider,
) : SearchSuggestionRepository {
    private val client = clientProvider.newBuilder()
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    override suspend fun suggestions(query: String, limit: Int): List<String> {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.length < MIN_QUERY_LENGTH) return emptyList()

        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("client", "firefox")
            .addQueryParameter("q", normalized)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).awaitBody()?.let {
                GoogleSuggestionParser.parse(it, normalized, limit)
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
        const val ENDPOINT = "https://suggestqueries.google.com/complete/search"
        const val REQUEST_TIMEOUT_SECONDS = 3L
        const val MIN_QUERY_LENGTH = 2
        const val MAX_QUERY_LENGTH = 256
        const val MAX_RESPONSE_BYTES = 64 * 1024L
    }
}

internal object GoogleSuggestionParser {
    const val MAX_RESULTS = 8

    fun parse(body: String, query: String, limit: Int): List<String> {
        val root = JSONArray(body)
        val values = root.optJSONArray(1) ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) {
                val value = values.optString(index).trim()
                if (value.isNotEmpty() && !value.equals(query, ignoreCase = true)) add(value)
                if (size >= limit.coerceIn(1, MAX_RESULTS)) break
            }
        }.distinctBy(String::lowercase)
    }
}
