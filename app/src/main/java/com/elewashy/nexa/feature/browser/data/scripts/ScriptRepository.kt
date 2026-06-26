package com.elewashy.nexa.feature.browser.data.scripts

import android.util.Base64
import android.util.Log
import android.webkit.WebView
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceId
import com.elewashy.nexa.feature.browser.data.resources.BrowserResourceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Available Scripts to be injected.
 */
enum class ScriptType(
    val resourceId: BrowserResourceId,
    val jsMarker: String,
) {
    POST_LOAD(BrowserResourceId.PostLoadScript, "__nexaPostLoadInjected"),
    PRE_LOAD(BrowserResourceId.PreLoadScript, "__nexaPreLoadInjected"),
}

/**
 * ScriptRepository — JavaScript injection backed by BrowserResourceRepository.
 * Memory cache serves current pages immediately; disk/network validation runs
 * only once per app session per script unless manually forced.
 */
@Singleton
class ScriptRepository @Inject constructor(
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val resourceRepository: BrowserResourceRepository,
) {

    private companion object {
        const val TAG = "ScriptRepository"
        const val RETRY_COOLDOWN_MS = 60 * 1000L
    }

    private val cachedBase64Map = ConcurrentHashMap<ScriptType, String>()
    private val lastFetchTimeMap = ConcurrentHashMap<ScriptType, Long>()
    private val lastAttemptTimeMap = ConcurrentHashMap<ScriptType, Long>()
    private val sessionRefreshed = ConcurrentHashMap<ScriptType, Boolean>()

    // One mutex per script to prevent blocking each other incorrectly
    private val scriptMutexes = mapOf(
        ScriptType.POST_LOAD to Mutex(),
        ScriptType.PRE_LOAD to Mutex()
    )

    /**
     * Injects the requested script type into the given WebView.
     */
    fun inject(webView: WebView?, type: ScriptType) {
        webView ?: return

        val now = System.currentTimeMillis()
        val currentBase64 = cachedBase64Map[type]
        val lastAttemptTime = lastAttemptTimeMap[type] ?: 0L
        
        if (currentBase64 != null) {
            evaluateInWebView(webView, currentBase64, type.jsMarker)

            if (sessionRefreshed.putIfAbsent(type, true) == null &&
                now - lastAttemptTime > RETRY_COOLDOWN_MS
            ) {
                scope.launch(ioDispatcher) { fetchAndCacheScriptBackground(type) }
            }
            return
        }

        // Suspend and await cache validation or network fetch
        scope.launch {
            val base64 = getOrFetchScript(type)
            if (base64 != null) {
                evaluateInWebView(webView, base64, type.jsMarker)
            }
        }
    }

    /**
     * Forces an immediate synchronous update of all scripts from the network.
     * Throws an Exception if any request fails, allowing calling code to handle the error.
     */
    suspend fun forceUpdateAll() {
        ScriptType.values().forEach { type ->
            val mutex = scriptMutexes[type]!!
            mutex.withLock {
                resourceRepository.refresh(type.resourceId, force = true)
                cacheScriptFromDisk(type) ?: throw Exception("No cached script content for ${type.name}")
            }
        }
    }

    private suspend fun getOrFetchScript(type: ScriptType): String? {
        val now = System.currentTimeMillis()
        val mutex = scriptMutexes[type]!!
        
        mutex.withLock {
            // Double-check memory cache inside lock
            cachedBase64Map[type]?.let { return it }

            // Try reading from Disk Cache
            val lastAttempt = lastAttemptTimeMap[type] ?: 0L

            resourceRepository.readText(type.resourceId)?.let { content ->
                try {
                    val base64 = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    cachedBase64Map[type] = base64
                    lastFetchTimeMap[type] = System.currentTimeMillis()
                    if (sessionRefreshed.putIfAbsent(type, true) == null &&
                        now - lastAttempt > RETRY_COOLDOWN_MS
                    ) {
                        scope.launch(ioDispatcher) { fetchAndCacheScriptBackground(type) }
                    }
                    return base64
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading cached script for ${type.name}", e)
                }
            }

            // No valid cache on disk, perform synchronous fetch from network
            if (now - lastAttempt > RETRY_COOLDOWN_MS) {
                return executeNetworkFetchSync(type)
            }
            return null
        }
    }

    private fun fetchAndCacheScriptBackground(type: ScriptType) {
        val now = System.currentTimeMillis()
        val lastAttempt = lastAttemptTimeMap[type] ?: 0L
        
        if (now - lastAttempt < RETRY_COOLDOWN_MS) return
        
        val mutex = scriptMutexes[type]!!
        if (mutex.tryLock()) {
            try {
                executeNetworkFetchSync(type)
            } finally {
                mutex.unlock()
            }
        }
    }

    private fun executeNetworkFetchSync(type: ScriptType): String? {
        lastAttemptTimeMap[type] = System.currentTimeMillis()
        Log.d(TAG, "Fetching new script from network for ${type.name}...")

        return try {
            resourceRepository.refresh(type.resourceId)
            cacheScriptFromDisk(type)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching script for ${type.name}", e)
            null
        }
    }

    private fun cacheScriptFromDisk(type: ScriptType): String? {
        val scriptContent = resourceRepository.readText(type.resourceId) ?: return null
        val base64 = Base64.encodeToString(scriptContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val fetchTime = System.currentTimeMillis()
        cachedBase64Map[type] = base64
        lastFetchTimeMap[type] = fetchTime
        lastAttemptTimeMap[type] = fetchTime
        sessionRefreshed[type] = true
        Log.d(TAG, "Script cached for ${type.name}.")
        return base64
    }

    private fun evaluateInWebView(webView: WebView, base64Script: String, jsMarker: String) {
        webView.post {
            try {
                val executionCode = """
                    (function() {
                        if (window.$jsMarker) return;
                        
                        function attemptInject() {
                            try {
                                var target = document.head || document.documentElement || document.body;
                                if (!target) return false; // DOM not ready yet
                                
                                function decodeBase64UTF8(base64) {
                                    var binaryString = window.atob(base64);
                                    var bytes = new Uint8Array(binaryString.length);
                                    for (var i = 0; i < binaryString.length; i++) {
                                        bytes[i] = binaryString.charCodeAt(i);
                                    }
                                    return new TextDecoder('utf-8').decode(bytes);
                                }
                                
                                var s = document.createElement('script');
                                s.type = 'text/javascript';
                                s.textContent = decodeBase64UTF8('$base64Script');
                                target.appendChild(s);
                                
                                window.$jsMarker = true; // Mark success only after successful appending
                                return true;
                            } catch (e) {
                                console.error('Failed to inject script:', e);
                                window.$jsMarker = true; // Stop spinning in case of critical evaluation failure
                                return true;
                            }
                        }
                        
                        if (!attemptInject()) {
                            // If it ran too early, poll up to 2 seconds for the DOM to surface
                            var retries = 0;
                            var interval = setInterval(function() {
                                if (attemptInject() || ++retries > 40) {
                                    clearInterval(interval);
                                }
                            }, 50);
                        }
                    })();
                """.trimIndent()
                
                webView.evaluateJavascript(executionCode, null)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject script into WebView", e)
            }
        }
    }
}
