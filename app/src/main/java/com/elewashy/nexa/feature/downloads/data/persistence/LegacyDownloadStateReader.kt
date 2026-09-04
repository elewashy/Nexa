package com.elewashy.nexa.feature.downloads.data.persistence

import android.util.Log
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Parsed content of a legacy `download_state.json` document. */
data class LegacyDownloadState(
    val lastId: Long,
    val items: List<DownloadItem>,
    val segments: Map<Long, List<PersistedSegment>>,
)

/**
 * Stateless reader for the retired download-state formats, used only by the
 * one-time Room import. Kept separate from the Room backend so the proven
 * R8-safe parse outlives the writer it replaces.
 *
 * Two historical formats exist:
 *  - Object document (`download_state.json`, 1.2.0+): `{lastId, items, segments}`.
 *  - Bare JSON array of items in SharedPreferences (`DownloadPrefs`, pre-1.2.0),
 *    with the id sequence in a separate prefs key — passed in as
 *    [fallbackLastId], since the array carries no sequence of its own.
 *
 * Manual JsonObject parsing (never reflective [SavedState]-style reads): R8
 * strips generic Signature attributes from private classes in minified
 * builds, so reflective deserialization loses List/Map element types and the
 * first element cast aborts the whole load.
 *
 * @throws com.google.gson.JsonSyntaxException on malformed JSON — the caller
 *         quarantines the file exactly like the old backend did.
 */
object LegacyDownloadStateReader {

    private const val TAG = "LegacyDownloadReader"

    private val gson = Gson()

    fun read(json: String, fallbackLastId: Long = 0L): LegacyDownloadState? {
        if (json.isBlank()) return null
        val root = gson.fromJson(json, JsonElement::class.java) ?: return null
        // Pre-1.2.0 SharedPreferences format: a bare array of items.
        if (root.isJsonArray) {
            return LegacyDownloadState(
                lastId = fallbackLastId.coerceAtLeast(0L),
                items = parseItems(root) ?: emptyList(),
                segments = emptyMap(),
            )
        }
        val obj = root.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return LegacyDownloadState(
            lastId = parseLastId(obj),
            items = parseItems(obj.get("items")) ?: emptyList(),
            segments = parseSegments(obj.get("segments")) ?: emptyMap(),
        )
    }

    private fun parseLastId(root: JsonObject): Long {
        // A malformed primitive must degrade to 0, never throw — throwing
        // here would quarantine the whole document for one bad field.
        val element = root.get("lastId")
        if (element == null || !element.isJsonPrimitive) return 0L
        return try {
            element.asLong.coerceAtLeast(0L)
        } catch (e: Exception) {
            0L
        }
    }

    private fun parseItems(element: JsonElement?): List<DownloadItem>? {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        return array.mapNotNull { el ->
            try {
                gson.fromJson(el, DownloadItem::class.java)
            } catch (e: Exception) {
                Log.w(TAG, "Skipping unparseable download item: ${e.message}")
                null
            }
        }
    }

    private fun parseSegments(element: JsonElement?): Map<Long, List<PersistedSegment>>? {
        val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val map = mutableMapOf<Long, List<PersistedSegment>>()
        for ((key, value) in obj.entrySet()) {
            val id = key.toLongOrNull() ?: continue
            val array = value.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            map[id] = array.mapNotNull { el ->
                try {
                    gson.fromJson(el, PersistedSegment::class.java)
                } catch (e: Exception) {
                    null
                }
            }
        }
        return map
    }
}
