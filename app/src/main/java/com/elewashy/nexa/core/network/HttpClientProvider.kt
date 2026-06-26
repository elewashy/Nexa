package com.elewashy.nexa.core.network

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides the shared, general-purpose [OkHttpClient] used by ordinary repositories.
 *
 * **Scope:** this client is intended for ordinary repository network traffic
 * (GitHub update checks, browser resources, downloads, etc.).
 *
 * A single [OkHttpClient] instance is reused across the app so that the
 * underlying connection pool, dispatcher, and thread pool are shared — per
 * OkHttp's own guidance. Per-call tuning (different timeouts, interceptors) is
 * performed with [OkHttpClient.newBuilder] at the call site.
 *
 * Sensible production defaults:
 *  - 15 s connect / 30 s read / 30 s write timeouts
 *  - `retryOnConnectionFailure = true` (OkHttp default, made explicit)
 *
 * Feature repositories should inject this provider instead of creating their
 * own long-lived HTTP clients.
 */
@Singleton
class HttpClientProvider @Inject constructor() {

    val client: OkHttpClient by lazy { buildDefaultClient() }

    /**
     * Returns a derived client that shares the underlying connection pool and
     * dispatcher with the default [client]. Use this when a call-site needs
     * different timeouts or interceptors but wants to preserve pooling.
     */
    fun newBuilder(): OkHttpClient.Builder = client.newBuilder()

    private fun buildDefaultClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 15L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 30L
    }
}
