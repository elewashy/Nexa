package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.core.common.DefaultDispatcher
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.engine.FilterRuleSyntax
import com.elewashy.nexa.feature.adblock.data.persistence.CustomRuleEntity
import com.elewashy.nexa.feature.adblock.data.persistence.CustomRulesDao
import com.elewashy.nexa.feature.adblock.domain.model.CustomRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own filter rules (uBO "My filters"), persisted one per row.
 * Rules are compiled into the same engine as the filter lists, so they
 * follow the same precedence (see [AdBlockRepository]).
 */
@Singleton
class CustomRuleRepository @Inject constructor(
    private val dao: CustomRulesDao,
    appPreferences: AppPreferences,
    @param:DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) {
    /** Whether custom rules may use trusted-only scriptlets. */
    val trusted: Flow<Boolean> = appPreferences.adBlockTrustCustomRules

    /** Every rule, newest first, classified with the current trust setting. */
    val rules: Flow<List<CustomRule>> = combine(dao.observeAll(), trusted) { rows, trusted ->
        rows.map { row ->
            CustomRule(
                id = row.id,
                text = row.rule,
                enabled = row.enabled,
                kind = FilterRuleSyntax.classify(row.rule, trusted),
                updatedAt = row.updatedAt,
            )
        }
    }.flowOn(defaultDispatcher)

    /** Enabled rule texts in creation order: the input of the engine. */
    val enabledRuleTexts: Flow<List<String>> = dao.observeAll()
        .map { rows -> rows.asReversed().filter { it.enabled }.map { it.rule } }
        .distinctUntilChanged()

    data class AddResult(val added: Int, val duplicates: Int)

    /** Adds [rules] (already normalized), skipping blanks and rules that already exist. */
    suspend fun add(rules: List<String>): AddResult {
        val unique = rules.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (unique.isEmpty()) return AddResult(0, rules.size)
        val now = System.currentTimeMillis()
        val ids = dao.insertAll(unique.map { CustomRuleEntity(rule = it, enabled = true, createdAt = now, updatedAt = now) })
        val added = ids.count { it != -1L }
        return AddResult(added = added, duplicates = rules.size - added)
    }

    enum class UpdateResult { Updated, Duplicate, NotFound }

    suspend fun update(id: Long, rule: String): UpdateResult {
        val text = rule.trim()
        val existing = dao.byRule(text)
        if (existing != null) return if (existing.id == id) UpdateResult.Updated else UpdateResult.Duplicate
        if (dao.byIds(listOf(id)).isEmpty()) return UpdateResult.NotFound
        dao.updateRule(id, text, System.currentTimeMillis())
        return UpdateResult.Updated
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
    }

    /** Opaque handle for undoing a deletion. */
    class DeletedRules internal constructor(internal val entities: List<CustomRuleEntity>) {
        val count: Int get() = entities.size
    }

    suspend fun delete(ids: Collection<Long>): DeletedRules = DeletedRules(dao.deleteAndReturn(ids.toList()))

    suspend fun restore(deleted: DeletedRules) {
        dao.restore(deleted.entities)
    }
}
