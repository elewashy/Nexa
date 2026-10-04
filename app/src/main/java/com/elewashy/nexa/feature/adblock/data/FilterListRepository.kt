package com.elewashy.nexa.feature.adblock.data

import com.elewashy.nexa.core.common.IoDispatcher
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.adblock.data.lists.BuiltInFilterList
import com.elewashy.nexa.feature.adblock.data.lists.FilterListSource
import com.elewashy.nexa.feature.adblock.data.lists.ImportedFilterList
import com.elewashy.nexa.feature.adblock.data.persistence.FilterListSettingEntity
import com.elewashy.nexa.feature.adblock.data.persistence.FilterListSettingsDao
import com.elewashy.nexa.feature.adblock.data.resources.BrowserResourceRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** A catalog or imported list with the user's selection applied. */
data class SelectedFilterList(
    val source: FilterListSource,
    val enabled: Boolean,
    /** Part of the default selection (built-in lists only). */
    val isDefault: Boolean,
)

/**
 * Source of truth for which filter lists are used: the built-in catalog
 * ([BuiltInFilterList]), lists imported by URL, and the user's selection
 * (Room). Built-in lists without a stored choice follow the catalog default
 * for the current UI language, so new default lists reach existing users.
 */
@Singleton
class FilterListRepository @Inject constructor(
    private val dao: FilterListSettingsDao,
    private val resources: BrowserResourceRepository,
    appPreferences: AppPreferences,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    /** Every list, catalog order first, then imported lists by import date. */
    val lists: Flow<List<SelectedFilterList>> = combine(
        dao.observeAll(),
        appPreferences.languageTag.map(::languageOf).distinctUntilChanged(),
    ) { rows, language ->
        val byKey = rows.associateBy { it.listKey }
        val builtIn = BuiltInFilterList.entries.map { list ->
            val isDefault = list.isDefaultFor(language)
            SelectedFilterList(list, byKey[list.key]?.enabled ?: isDefault, isDefault)
        }
        val imported = rows
            .filter { it.url != null && ImportedFilterList.isImportedKey(it.listKey) }
            .sortedBy { it.addedAt }
            .map { row ->
                val url = row.url.orEmpty()
                SelectedFilterList(ImportedFilterList(row.listKey, url, row.title ?: defaultTitle(url)), row.enabled, false)
            }
        builtIn + imported
    }.distinctUntilChanged()

    /** Sources compiled into the engine, in compile order. */
    val enabledLists: Flow<List<FilterListSource>> = lists
        .map { all -> all.filter { it.enabled }.map { it.source } }
        .distinctUntilChanged()

    suspend fun setEnabled(key: String, enabled: Boolean) {
        if (BuiltInFilterList.fromKey(key) == null && dao.byKey(key) == null) return
        dao.setEnabledOrInsert(key, enabled, System.currentTimeMillis())
    }

    sealed interface ImportResult {
        data class Added(val key: String) : ImportResult
        data object AlreadyAdded : ImportResult
        data object InvalidUrl : ImportResult
    }

    /** Imports a list by URL (uBO "Import…"); it is enabled and downloaded on the next update pass. */
    suspend fun import(rawUrl: String): ImportResult {
        val url = normalizeListUrl(rawUrl) ?: return ImportResult.InvalidUrl
        val catalogUrls = BuiltInFilterList.entries.flatMapTo(HashSet()) { it.downloadUrls }
        if (url in catalogUrls) return ImportResult.AlreadyAdded
        val key = ImportedFilterList.keyFor(url)
        if (dao.byKey(key) != null) return ImportResult.AlreadyAdded
        dao.upsert(
            FilterListSettingEntity(listKey = key, enabled = true, url = url, title = null, addedAt = System.currentTimeMillis()),
        )
        return ImportResult.Added(key)
    }

    /** Removes an imported list together with its cached copy. Built-in lists can only be disabled. */
    suspend fun removeImported(key: String) {
        val row = dao.byKey(key) ?: return
        val url = row.url ?: return
        dao.delete(key)
        withContext(ioDispatcher) { resources.delete(ImportedFilterList(key, url, row.title.orEmpty())) }
    }

    suspend fun setImportedTitle(key: String, title: String) {
        val clean = title.trim().take(MAX_TITLE_LENGTH)
        if (clean.isNotEmpty()) dao.setImportedTitle(key, clean)
    }

    companion object {
        private const val MAX_URL_LENGTH = 2048
        private const val MAX_TITLE_LENGTH = 120

        /** An http(s) URL in canonical form, or null when [raw] is not one. */
        fun normalizeListUrl(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.length > MAX_URL_LENGTH) return null
            val url = trimmed.toHttpUrlOrNull() ?: return null
            if (url.scheme != "https" && url.scheme != "http") return null
            return url.newBuilder().fragment(null).build().toString()
        }

        fun defaultTitle(url: String): String = url.toHttpUrlOrNull()?.let { it.host + it.encodedPath } ?: url

        /** ISO 639 language of the app locale ([tag] null = follow the system). */
        fun languageOf(tag: String?): String {
            val locale = tag?.takeIf { it.isNotBlank() }?.let(Locale::forLanguageTag) ?: Locale.getDefault()
            return locale.language
        }
    }
}
