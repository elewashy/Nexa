package com.elewashy.nexa.core.network

import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

/**
 * Executes an OkHttp call without pinning cancellation behind socket timeouts:
 * cancelling the coroutine cancels the call, and a response that arrives after
 * cancellation is closed instead of leaking its connection.
 */
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, cancelledResponse, _ ->
                cancelledResponse.close()
            }
        }
    })
}
