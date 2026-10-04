package com.elewashy.nexa.feature.adblock.data

import android.util.Log
import com.elewashy.nexa.core.common.ApplicationScope
import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.feature.adblock.data.engine.CachingRegistrableDomainResolver
import com.elewashy.nexa.feature.adblock.data.engine.RequestType
import com.elewashy.nexa.feature.adblock.data.persistence.AdBlockStatsDao
import com.elewashy.nexa.feature.adblock.data.persistence.DailyStatsEntity
import com.elewashy.nexa.feature.adblock.data.persistence.StatsBatch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Collects blocking statistics off the request hot path.
 *
 * Recording is a handful of atomic increments (no allocation for counters,
 * one map entry per distinct blocked host). Counters are written to Room in
 * one transaction at most every [FLUSH_DELAY_MS] while blocking happens,
 * and immediately when the app goes to the background ([flushNow]) — never
 * once per request. A process killed in between loses at most that window
 * of statistics; settings and filters are unaffected.
 */
@Singleton
class AdBlockStatsRecorder internal constructor(
    private val dao: AdBlockStatsDao,
    private val appScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        dao: AdBlockStatsDao,
        @ApplicationScope appScope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(dao, appScope, ioDispatcher, System::currentTimeMillis, ZoneId::systemDefault)

    /** Counters of one local day; replaced at midnight. */
    private class Bucket(val day: Long, val endsAtMillis: Long) {
        val blockedRequests = AtomicLong()
        val blockedPopups = AtomicLong()
        val blockedPages = AtomicLong()
        val removedParams = AtomicLong()
        val bytesSaved = AtomicLong()
        val pagesFiltered = AtomicLong()

        fun drain(): DailyStatsEntity? {
            val entity = DailyStatsEntity(
                day = day,
                blockedRequests = blockedRequests.getAndSet(0),
                blockedPopups = blockedPopups.getAndSet(0),
                blockedPages = blockedPages.getAndSet(0),
                removedParams = removedParams.getAndSet(0),
                bytesSaved = bytesSaved.getAndSet(0),
                pagesFiltered = pagesFiltered.getAndSet(0),
            )
            val empty = entity.blockedRequests == 0L && entity.blockedPopups == 0L && entity.blockedPages == 0L &&
                entity.removedParams == 0L && entity.bytesSaved == 0L && entity.pagesFiltered == 0L
            return if (empty) null else entity
        }
    }

    @Volatile
    private var bucket: Bucket = newBucket(clock())

    /** Buckets of past days not flushed yet (normally empty or one at midnight). */
    private val retired = ConcurrentHashMap<Long, Bucket>()

    /** Blocked-request counts per request host since the last flush (bounded). */
    private val hosts = ConcurrentHashMap<String, AtomicLong>()

    private val flushScheduled = AtomicBoolean(false)
    private val flushMutex = Mutex()
    private val resolver = CachingRegistrableDomainResolver(PublicSuffixDomainResolver)

    /**
     * A subresource request was blocked or redirected. [host] is the blocked
     * request's host, attributed only when [attributeHost] (never for
     * private tabs).
     */
    fun recordBlockedRequest(host: String?, type: Int, attributeHost: Boolean) {
        val current = currentBucket()
        current.blockedRequests.incrementAndGet()
        current.bytesSaved.addAndGet(estimatedBytes(type))
        if (attributeHost && !host.isNullOrEmpty() && (hosts.size < MAX_PENDING_HOSTS || hosts.containsKey(host))) {
            hosts.getOrPut(host) { AtomicLong() }.incrementAndGet()
        }
        scheduleFlush()
    }

    fun recordBlockedPopup() {
        currentBucket().blockedPopups.incrementAndGet()
        scheduleFlush()
    }

    fun recordBlockedPage() {
        currentBucket().blockedPages.incrementAndGet()
        scheduleFlush()
    }

    fun recordRemovedParams() {
        currentBucket().removedParams.incrementAndGet()
        scheduleFlush()
    }

    /** A page loaded with filtering active (the denominator for per-page figures). */
    fun recordPageFiltered() {
        currentBucket().pagesFiltered.incrementAndGet()
        scheduleFlush()
    }

    /** Writes pending counters now (app backgrounded, statistics page opened). */
    fun flushNow() {
        appScope.launch(ioDispatcher) { flush() }
    }

    /** Writes every pending counter in one transaction. */
    suspend fun flush() = flushMutex.withLock {
        val days = buildList {
            for (key in retired.keys.toList()) retired.remove(key)?.drain()?.let(::add)
            currentBucket().drain()?.let(::add)
        }
        val domains = HashMap<String, Long>()
        for (host in hosts.keys.toList()) {
            val count = hosts.remove(host)?.get() ?: continue
            if (count <= 0L) continue
            val domain = resolver.registrableDomain(host) ?: host
            domains[domain] = (domains[domain] ?: 0L) + count
        }
        val batch = StatsBatch(days, domains, clock())
        if (batch.isEmpty) return@withLock
        try {
            withContext(ioDispatcher) { dao.apply(batch) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Statistics are best-effort; never let them affect browsing.
            Log.w(TAG, "Statistics not saved: ${e.message}")
        }
    }

    private fun scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) return
        appScope.launch(ioDispatcher) {
            try {
                delay(FLUSH_DELAY_MS)
            } finally {
                flushScheduled.set(false)
            }
            flush()
        }
    }

    private fun currentBucket(): Bucket {
        val current = bucket
        val now = clock()
        if (now < current.endsAtMillis) return current
        synchronized(this) {
            val latest = bucket
            if (now < latest.endsAtMillis) return latest
            retired[latest.day] = latest
            return newBucket(now).also { bucket = it }
        }
    }

    private fun newBucket(now: Long): Bucket {
        val zoneId = zone()
        val date = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        return Bucket(date.toEpochDay(), end)
    }

    companion object {
        private const val TAG = "AdBlockStats"
        internal const val FLUSH_DELAY_MS = 30_000L
        private const val MAX_PENDING_HOSTS = 512

        /**
         * Typical transfer size of a blocked resource, by type. Ad and
         * tracker payloads vary widely, so "data saved" is an estimate:
         * these are median response sizes per resource type (HTTP Archive,
         * mobile pages), rounded down to stay conservative.
         */
        fun estimatedBytes(type: Int): Long = when {
            type and RequestType.MEDIA != 0 -> 200_000L
            type and RequestType.SUBDOCUMENT != 0 -> 30_000L
            type and RequestType.SCRIPT != 0 -> 15_000L
            type and RequestType.FONT != 0 -> 20_000L
            type and RequestType.IMAGE != 0 -> 8_000L
            type and RequestType.STYLESHEET != 0 -> 6_000L
            type and RequestType.OBJECT != 0 -> 10_000L
            type and RequestType.XHR != 0 -> 2_000L
            type and (RequestType.PING or RequestType.WEBSOCKET) != 0 -> 500L
            else -> 1_000L
        }

        /** Local date of [epochDay]. */
        fun dateOf(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)
    }
}
