package com.elewashy.nexa.feature.browser.presentation.screen

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset

internal data class TabGridItemLayout(
    val id: Long,
    val center: Offset,
    val offset: Offset,
)

internal data class TabReorderCommit(
    val tabId: Long,
    val targetIndex: Int,
)

/**
 * Pick-up-and-move state for a lazy grid.
 *
 * Only an optimistic stable-id order is retained. [items] always resolves ids against the latest
 * repository list, so membership and metadata remain authoritative and a closed tab cannot survive
 * in a private UI copy while an order acknowledgement is pending.
 */
@Stable
internal class TabGridReorderState<T>(
    private val idOf: (T) -> Long,
    private val canCross: (T, T) -> Boolean,
) {
    private val orderedIds = mutableStateListOf<Long>()
    private var latestItemsById: Map<Long, T> = emptyMap()

    /** Current optimistic order for state-holder tests and drag calculations. */
    val items: List<T>
        get() = orderedIds.mapNotNull(latestItemsById::get)

    var draggedId: Long? by mutableStateOf(null)
        private set
    var dragOffset: Offset by mutableStateOf(Offset.Zero)
        private set
    var pointerCenter: Offset by mutableStateOf(Offset.Unspecified)
        private set

    private var startIndex = -1
    private var pendingOrderIds: List<Long>? = null

    /**
     * Returns the latest source items in the temporary drag order. Source membership wins
     * immediately; ids not yet seen by [sync] are appended in authoritative order.
     */
    fun items(source: List<T>): List<T> {
        val sourceById = source.associateBy(idOf)
        val result = ArrayList<T>(source.size)
        val emitted = HashSet<Long>(source.size)
        orderedIds.forEach { id ->
            sourceById[id]?.let { item ->
                result += item
                emitted += id
            }
        }
        source.forEach { item ->
            if (emitted.add(idOf(item))) result += item
        }
        return result
    }

    fun sync(source: List<T>) {
        latestItemsById = source.associateBy(idOf)
        val sourceIds = source.map(idOf)
        val currentIds = orderedIds.toList()
        val dragged = draggedId

        if (dragged != null && (dragged !in sourceIds || currentIds.toSet() != sourceIds.toSet())) {
            cancel(source)
            return
        }
        if (dragged != null) return

        val expected = pendingOrderIds
        when {
            expected != null && sourceIds == expected -> {
                pendingOrderIds = null
                replaceWith(sourceIds)
            }
            expected != null && sourceIds.toSet() == expected.toSet() && currentIds == expected -> {
                // Ignore an older repository order while the serialized commit catches up.
            }
            else -> {
                pendingOrderIds = null
                replaceWith(sourceIds)
            }
        }
    }

    fun start(id: Long, center: Offset): Boolean {
        val index = orderedIds.indexOf(id)
        if (index < 0 || !center.isFinite()) return false
        draggedId = id
        startIndex = index
        dragOffset = Offset.Zero
        pointerCenter = center
        return true
    }

    fun isDragging(id: Long): Boolean = draggedId == id

    fun dragBy(delta: Offset, layouts: List<TabGridItemLayout>) {
        if (draggedId == null || !delta.isFinite()) return
        dragOffset += delta
        pointerCenter += delta
        moveToNearestEligibleItem(layouts)
    }

    fun reevaluate(layouts: List<TabGridItemLayout>) {
        if (draggedId != null) moveToNearestEligibleItem(layouts)
    }

    fun compensateForScroll(consumedY: Float) {
        if (draggedId != null && consumedY.isFinite()) {
            dragOffset += Offset(0f, consumedY)
        }
    }

    /** Returns one durable commit while retaining the final visual order until acknowledgement. */
    fun finish(): TabReorderCommit? {
        val id = draggedId ?: return null
        val targetIndex = orderedIds.indexOf(id)
        val commit = if (targetIndex >= 0 && targetIndex != startIndex) {
            pendingOrderIds = orderedIds.toList()
            TabReorderCommit(id, targetIndex)
        } else {
            null
        }
        clearGesture()
        return commit
    }

    fun cancel(source: List<T>) {
        latestItemsById = source.associateBy(idOf)
        pendingOrderIds = null
        clearGesture()
        replaceWith(source.map(idOf))
    }

    private fun moveToNearestEligibleItem(layouts: List<TabGridItemLayout>) {
        val id = draggedId ?: return
        if (!pointerCenter.isFinite()) return
        val draggedItem = latestItemsById[id] ?: return
        val currentIndex = orderedIds.indexOf(id)
        val draggedLayout = layouts.firstOrNull { it.id == id } ?: return

        val targetLayout = layouts
            .asSequence()
            .filter { layout ->
                latestItemsById[layout.id]?.let { canCross(draggedItem, it) } == true
            }
            .minByOrNull { layout ->
                val delta = layout.center - pointerCenter
                delta.x * delta.x + delta.y * delta.y
            }
            ?: return

        val targetIndex = orderedIds.indexOf(targetLayout.id)
        if (targetIndex < 0 || targetIndex == currentIndex) return

        orderedIds.add(targetIndex, orderedIds.removeAt(currentIndex))
        // The lazy item receives the target's layout slot after the list mutation. Counteract that
        // slot jump so the picked-up card remains under the pointer.
        dragOffset += draggedLayout.offset - targetLayout.offset
    }

    private fun replaceWith(sourceIds: List<Long>) {
        orderedIds.clear()
        orderedIds.addAll(sourceIds)
    }

    private fun clearGesture() {
        draggedId = null
        dragOffset = Offset.Zero
        pointerCenter = Offset.Unspecified
        startIndex = -1
    }

    private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()
}
