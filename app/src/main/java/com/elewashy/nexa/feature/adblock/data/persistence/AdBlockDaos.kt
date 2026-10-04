package com.elewashy.nexa.feature.adblock.data.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterListSettingsDao {

    @Query("SELECT * FROM adblock_filter_lists")
    fun observeAll(): Flow<List<FilterListSettingEntity>>

    @Query("SELECT * FROM adblock_filter_lists WHERE list_key = :key")
    suspend fun byKey(key: String): FilterListSettingEntity?

    @Upsert
    suspend fun upsert(entity: FilterListSettingEntity)

    @Query("UPDATE adblock_filter_lists SET enabled = :enabled WHERE list_key = :key")
    suspend fun setEnabled(key: String, enabled: Boolean): Int

    @Query("UPDATE adblock_filter_lists SET title = :title WHERE list_key = :key AND url IS NOT NULL")
    suspend fun setImportedTitle(key: String, title: String)

    @Query("DELETE FROM adblock_filter_lists WHERE list_key = :key")
    suspend fun delete(key: String)

    /** Sets [enabled] on [key], creating its row (a built-in list's first override) when needed. */
    @Transaction
    suspend fun setEnabledOrInsert(key: String, enabled: Boolean, now: Long) {
        if (setEnabled(key, enabled) == 0) {
            upsert(FilterListSettingEntity(listKey = key, enabled = enabled, addedAt = now))
        }
    }
}

@Dao
interface CustomRulesDao {

    /** Newest first: the rules the user is most likely to revisit. */
    @Query("SELECT * FROM adblock_custom_rules ORDER BY id DESC")
    fun observeAll(): Flow<List<CustomRuleEntity>>

    @Query("SELECT * FROM adblock_custom_rules WHERE rule = :rule")
    suspend fun byRule(rule: String): CustomRuleEntity?

    /** Rows whose rule already exists are skipped (returned id -1). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<CustomRuleEntity>): List<Long>

    @Query("UPDATE adblock_custom_rules SET rule = :rule, updated_at = :now WHERE id = :id")
    suspend fun updateRule(id: Long, rule: String, now: Long)

    @Query("UPDATE adblock_custom_rules SET enabled = :enabled, updated_at = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    @Query("SELECT * FROM adblock_custom_rules WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<CustomRuleEntity>

    @Query("DELETE FROM adblock_custom_rules WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Re-inserts rows removed by [deleteAndReturn] with their original ids (undo). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restore(entities: List<CustomRuleEntity>)

    @Transaction
    suspend fun deleteAndReturn(ids: List<Long>): List<CustomRuleEntity> {
        val chunks = ids.chunked(MAX_BIND_IDS)
        val removed = chunks.flatMap { byIds(it) }
        chunks.forEach { deleteByIds(it) }
        return removed
    }

    companion object {
        /** Below SQLite's bind-variable limit on every supported Android version. */
        const val MAX_BIND_IDS = 900
    }
}

@Dao
interface SiteSettingsDao {

    @Query("SELECT * FROM adblock_site_settings ORDER BY host")
    fun observeAll(): Flow<List<SiteSettingsEntity>>

    @Query("SELECT * FROM adblock_site_settings WHERE host = :host")
    suspend fun byHost(host: String): SiteSettingsEntity?

    @Upsert
    suspend fun upsert(entity: SiteSettingsEntity)

    @Query("DELETE FROM adblock_site_settings WHERE host = :host")
    suspend fun delete(host: String)
}

/** Lifetime totals over every recorded day; [since] is the first recorded day (null when empty). */
data class StatsTotalsRow(
    val blockedRequests: Long,
    val blockedPopups: Long,
    val blockedPages: Long,
    val removedParams: Long,
    val bytesSaved: Long,
    val pagesFiltered: Long,
    val since: Long?,
)

@Dao
interface AdBlockStatsDao {

    @Query(
        "SELECT COALESCE(SUM(blocked_requests), 0) AS blockedRequests, " +
            "COALESCE(SUM(blocked_popups), 0) AS blockedPopups, " +
            "COALESCE(SUM(blocked_pages), 0) AS blockedPages, " +
            "COALESCE(SUM(removed_params), 0) AS removedParams, " +
            "COALESCE(SUM(bytes_saved), 0) AS bytesSaved, " +
            "COALESCE(SUM(pages_filtered), 0) AS pagesFiltered, " +
            "MIN(day) AS since FROM adblock_daily_stats"
    )
    fun observeTotals(): Flow<StatsTotalsRow>

    @Query("SELECT * FROM adblock_daily_stats WHERE day >= :fromDay ORDER BY day")
    fun observeDaysFrom(fromDay: Long): Flow<List<DailyStatsEntity>>

    @Query("SELECT * FROM adblock_blocked_domains ORDER BY blocked_count DESC, domain LIMIT :limit")
    fun observeTopDomains(limit: Int): Flow<List<BlockedDomainEntity>>

    @Query(
        "INSERT OR IGNORE INTO adblock_daily_stats (day, blocked_requests, blocked_popups, blocked_pages, " +
            "removed_params, bytes_saved, pages_filtered) VALUES (:day, 0, 0, 0, 0, 0, 0)"
    )
    suspend fun ensureDay(day: Long)

    @Query(
        "UPDATE adblock_daily_stats SET blocked_requests = blocked_requests + :blockedRequests, " +
            "blocked_popups = blocked_popups + :blockedPopups, blocked_pages = blocked_pages + :blockedPages, " +
            "removed_params = removed_params + :removedParams, bytes_saved = bytes_saved + :bytesSaved, " +
            "pages_filtered = pages_filtered + :pagesFiltered WHERE day = :day"
    )
    suspend fun addToDay(
        day: Long,
        blockedRequests: Long,
        blockedPopups: Long,
        blockedPages: Long,
        removedParams: Long,
        bytesSaved: Long,
        pagesFiltered: Long,
    )

    @Query(
        "INSERT OR IGNORE INTO adblock_blocked_domains (domain, blocked_count, last_blocked_at) " +
            "VALUES (:domain, 0, :at)"
    )
    suspend fun ensureDomain(domain: String, at: Long)

    @Query(
        "UPDATE adblock_blocked_domains SET blocked_count = blocked_count + :count, " +
            "last_blocked_at = MAX(last_blocked_at, :at) WHERE domain = :domain"
    )
    suspend fun addToDomain(domain: String, count: Long, at: Long)

    @Query("SELECT COUNT(*) FROM adblock_blocked_domains")
    suspend fun domainCount(): Int

    /** Keeps the [keep] most-blocked domains (the only ones the UI ever shows). */
    @Query(
        "DELETE FROM adblock_blocked_domains WHERE domain NOT IN " +
            "(SELECT domain FROM adblock_blocked_domains ORDER BY blocked_count DESC, last_blocked_at DESC LIMIT :keep)"
    )
    suspend fun pruneDomains(keep: Int)

    @Query("DELETE FROM adblock_daily_stats")
    suspend fun clearDays()

    @Query("DELETE FROM adblock_blocked_domains")
    suspend fun clearDomains()

    /**
     * Adds one recorder batch in a single transaction. SQLite on API 26-29
     * predates UPSERT, so each row is "insert if missing, then increment".
     */
    @Transaction
    suspend fun apply(batch: StatsBatch) {
        for (day in batch.days) {
            ensureDay(day.day)
            addToDay(
                day.day, day.blockedRequests, day.blockedPopups, day.blockedPages,
                day.removedParams, day.bytesSaved, day.pagesFiltered,
            )
        }
        for ((domain, count) in batch.domains) {
            ensureDomain(domain, batch.at)
            addToDomain(domain, count, batch.at)
        }
        if (batch.domains.isNotEmpty() && domainCount() > MAX_DOMAINS + PRUNE_SLACK) pruneDomains(MAX_DOMAINS)
    }

    @Transaction
    suspend fun clear() {
        clearDays()
        clearDomains()
    }

    companion object {
        const val MAX_DOMAINS = 500

        /** Prune in steps, not on every flush once the table is full. */
        const val PRUNE_SLACK = 100
    }
}

/** Counters accumulated in memory between two flushes. */
data class StatsBatch(
    val days: List<DailyStatsEntity>,
    val domains: Map<String, Long>,
    val at: Long,
) {
    val isEmpty: Boolean get() = days.isEmpty() && domains.isEmpty()
}
