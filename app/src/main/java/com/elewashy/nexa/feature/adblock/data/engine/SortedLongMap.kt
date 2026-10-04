package com.elewashy.nexa.feature.adblock.data.engine

/**
 * Immutable map from 64-bit hash to a bucket of values, stored as a sorted
 * key array plus a parallel bucket array. Lookups are a binary search with no
 * boxing or allocation; memory is two arrays instead of one node per entry.
 */
internal class SortedLongMap<T : Any> private constructor(
    private val keys: LongArray,
    private val buckets: Array<List<T>>,
) {
    val size: Int get() = keys.size

    operator fun get(key: Long): List<T>? {
        val idx = keys.binarySearch(key)
        return if (idx >= 0) buckets[idx] else null
    }

    fun values(): Sequence<T> = buckets.asSequence().flatMap { it.asSequence() }

    internal fun forEachEntry(action: (key: Long, bucket: List<T>) -> Unit) {
        for (i in keys.indices) action(keys[i], buckets[i])
    }

    companion object {
        /** Wraps already sorted, distinct [keys] and their parallel [buckets]. */
        internal fun <T : Any> ofSorted(keys: LongArray, buckets: Array<List<T>>): SortedLongMap<T> {
            require(keys.size == buckets.size)
            return SortedLongMap(keys, buckets)
        }
    }

    /** Collects values per key during compilation, then freezes into a [SortedLongMap]. */
    class Builder<T : Any> {
        private val pending = HashMap<Long, ArrayList<T>>()

        fun add(key: Long, value: T) {
            pending.getOrPut(key) { ArrayList(2) }.add(value)
        }

        fun build(): SortedLongMap<T> {
            val keys = pending.keys.toLongArray().apply { sort() }
            val buckets = Array<List<T>>(keys.size) { i ->
                val list = pending.getValue(keys[i])
                // Exact-size immutable buckets; most hold one or two filters.
                if (list.size == 1) listOf(list[0]) else list.toList()
            }
            return SortedLongMap(keys, buckets)
        }
    }
}

/** Immutable set of 64-bit hashes (sorted array, binary search). */
internal class SortedLongSet private constructor(private val keys: LongArray) {
    val size: Int get() = keys.size

    /** The sorted, distinct values (shared; do not modify). */
    internal fun sortedValues(): LongArray = keys

    operator fun contains(key: Long): Boolean = keys.binarySearch(key) >= 0

    companion object {
        /** Wraps values that are already sorted and distinct. */
        internal fun ofSorted(values: LongArray): SortedLongSet = SortedLongSet(values)

        fun of(values: LongArray): SortedLongSet {
            val arr = values.copyOf()
            arr.sort()
            // Deduplicate in place.
            var n = 0
            for (i in arr.indices) if (n == 0 || arr[n - 1] != arr[i]) arr[n++] = arr[i]
            return SortedLongSet(if (n == arr.size) arr else arr.copyOf(n))
        }
    }
}
