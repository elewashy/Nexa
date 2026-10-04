package com.elewashy.nexa.core.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A small in-memory LRU cache whose entries expire after [ttlMs], loaded on
 * demand by a suspending loader.
 *
 * Loads are serialized per key only: concurrent requests for the same key
 * wait for the first load and then read its result instead of loading again,
 * while requests for different keys load in parallel. Each caller keeps its
 * own cancellation; a cancelled caller never cancels another caller's load.
 * Loader exceptions propagate to the caller and are not cached, so transient
 * failures are retried by the next request. Null results are cached.
 */
class KeyedLoadingCache<K : Any, V>(
    private val maxEntries: Int,
    private val ttlMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMs > 0) { "ttlMs must be positive" }
    }

    private class Entry<V>(val value: V, val loadedAtMs: Long)

    /** Per-key load lock, shared by the callers currently waiting on that key. */
    private class KeyLock {
        val mutex = Mutex()
        var users = 0
    }

    /** Access-ordered LRU; guarded by its own monitor. */
    private val entries = object : LinkedHashMap<K, Entry<V>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, Entry<V>>?) = size > maxEntries
    }

    /** Guarded by its own monitor; a key is present only while some caller uses its lock. */
    private val keyLocks = HashMap<K, KeyLock>()

    /** The cached value for [key], or the result of [load] when absent or expired. */
    suspend fun get(key: K, load: suspend (K) -> V): V {
        fresh(key)?.let { return it.value }
        val lock = synchronized(keyLocks) { keyLocks.getOrPut(key, ::KeyLock).also { it.users++ } }
        try {
            return lock.mutex.withLock {
                // Another caller may have loaded the key while this one waited.
                val cached = fresh(key)
                if (cached != null) {
                    cached.value
                } else {
                    load(key).also { value -> synchronized(entries) { entries[key] = Entry(value, nowMs()) } }
                }
            }
        } finally {
            synchronized(keyLocks) {
                if (--lock.users == 0) keyLocks.remove(key)
            }
        }
    }

    private fun fresh(key: K): Entry<V>? = synchronized(entries) {
        entries[key]?.takeIf { nowMs() - it.loadedAtMs < ttlMs }
    }
}
