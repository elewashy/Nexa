package com.elewashy.nexa.feature.browser.data.favicon

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import androidx.core.net.toUri
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface FaviconSource {
    data object Loading : FaviconSource
    data object Unavailable : FaviconSource
    data class Local(val file: File, val cacheKey: String) : FaviconSource
    data class Remote(val url: String, val cacheKey: String) : FaviconSource
}

/**
 * Origin-keyed favicon source of truth.
 *
 * Chromium-discovered icons are persisted in the app cache from `WebChromeClient.onReceivedIcon`.
 * Unvisited origins fall back once to the conventional same-origin `/favicon.ico`; Coil owns
 * decoded-memory and HTTP disk caching. Failed fallbacks are remembered for this process to avoid
 * repeating requests while lists scroll and recompose.
 */
@Singleton
class FaviconRepository @Inject constructor(
    @ApplicationContext context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    private val directory = File(context.cacheDir, CACHE_DIRECTORY)
    private val sources = ConcurrentHashMap<String, MutableStateFlow<FaviconSource>>()
    private val sourceInsertionLock = Any()
    private val storesSincePrune = AtomicInteger()

    init {
        applicationScope.launch(ioDispatcher) { pruneCache() }
    }

    fun observe(pageUrl: String): StateFlow<FaviconSource> {
        val origin = origin(pageUrl)
            ?: return MutableStateFlow<FaviconSource>(FaviconSource.Unavailable).asStateFlow()
        sources[origin]?.let { return it.asStateFlow() }

        val candidate = MutableStateFlow<FaviconSource>(FaviconSource.Loading)
        val state = synchronized(sourceInsertionLock) {
            sources[origin] ?: if (sources.size < MAX_SOURCE_STATES) {
                sources.putIfAbsent(origin, candidate) ?: candidate
            } else {
                // History can contain an arbitrary number of origins. A transient state remains
                // observable by its caller without turning this process cache into an unbounded map.
                candidate
            }
        }
        if (state === candidate) {
            applicationScope.launch(ioDispatcher) { state.value = resolve(origin) }
        }
        return state.asStateFlow()
    }

    fun store(pageUrl: String?, bitmap: Bitmap?) {
        val origin = origin(pageUrl.orEmpty()) ?: return
        bitmap ?: return
        applicationScope.launch(ioDispatcher) {
            runCatching {
                directory.mkdirs()
                val key = cacheKey(origin)
                val target = File(directory, "$key.png")
                val temporary = File(directory, "$key.tmp")
                val normalized = bitmap.downscale(MAX_ICON_SIZE_PX)
                temporary.outputStream().buffered().use { output ->
                    check(normalized.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, output))
                }
                if (!temporary.renameTo(target)) {
                    temporary.copyTo(target, overwrite = true)
                    temporary.delete()
                }
                sources[origin]?.value =
                    FaviconSource.Local(target, "$key-${target.lastModified()}")
                if (storesSincePrune.incrementAndGet() >= PRUNE_INTERVAL_STORES) {
                    storesSincePrune.set(0)
                    pruneCache()
                }
            }
        }
    }

    fun markRemoteFailed(pageUrl: String) {
        val origin = origin(pageUrl) ?: return
        sources[origin]?.let { state ->
            if (state.replaceRemoteWith(FaviconSource.Unavailable)) {
                // Keep active collectors alive while allowing future requests to retry and ensuring
                // failed origins cannot permanently consume the bounded process cache.
                sources.remove(origin, state)
            }
        }
    }

    private suspend fun resolve(origin: String): FaviconSource = withContext(ioDispatcher) {
        val key = cacheKey(origin)
        val local = File(directory, "$key.png")
        if (local.isFile && local.length() in 1..MAX_CACHE_FILE_BYTES) {
            local.setLastModified(System.currentTimeMillis())
            FaviconSource.Local(local, "$key-${local.lastModified()}")
        } else {
            local.delete()
            FaviconSource.Remote("$origin/favicon.ico", key)
        }
    }

    private fun MutableStateFlow<FaviconSource>.replaceRemoteWith(replacement: FaviconSource): Boolean {
        val current = value as? FaviconSource.Remote ?: return false
        return compareAndSet(current, replacement)
    }

    private fun pruneCache() {
        if (!directory.isDirectory) return
        val files = directory.listFiles { file -> file.extension == "png" }.orEmpty()
            .sortedByDescending(File::lastModified)
        files.drop(MAX_CACHE_FILES).forEach(File::delete)
        val cutoff = System.currentTimeMillis() - MAX_CACHE_AGE_MS
        files.take(MAX_CACHE_FILES).filter { it.lastModified() < cutoff }.forEach(File::delete)
        directory.listFiles { file -> file.extension == "tmp" }.orEmpty().forEach(File::delete)
    }

    private fun Bitmap.downscale(maxSize: Int): Bitmap {
        if (width <= maxSize && height <= maxSize) return this
        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        return scale((width * ratio).toInt().coerceAtLeast(1), (height * ratio).toInt().coerceAtLeast(1))
    }

    private fun origin(url: String): String? = runCatching {
        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" }
            ?: return@runCatching null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val safeHost = if (':' in host) "[$host]" else host
        val authority = if (uri.port >= 0) "$safeHost:${uri.port}" else safeHost
        "$scheme://$authority"
    }.getOrNull()

    private fun cacheKey(origin: String): String = MessageDigest.getInstance("SHA-256")
        .digest(origin.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val CACHE_DIRECTORY = "favicons"
        const val MAX_ICON_SIZE_PX = 128
        const val PNG_QUALITY = 100
        const val MAX_CACHE_FILES = 256
        const val MAX_SOURCE_STATES = 512
        const val PRUNE_INTERVAL_STORES = 32
        const val MAX_CACHE_FILE_BYTES = 512L * 1024
        const val MAX_CACHE_AGE_MS = 30L * 24 * 60 * 60 * 1000
    }
}
