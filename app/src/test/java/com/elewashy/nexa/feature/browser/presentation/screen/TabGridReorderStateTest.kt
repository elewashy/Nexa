package com.elewashy.nexa.feature.browser.presentation.screen

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TabGridReorderStateTest {

    @Test
    fun `repeated drag keeps visual order while repository acknowledgements catch up`() {
        val original = listOf(item(1), item(2), item(3))
        val state = state().apply { sync(original) }

        assertTrue(state.start(1, Offset(0f, 0f)))
        state.dragBy(Offset(80f, 0f), layouts(1, 2, 3))
        assertEquals(TabReorderCommit(1, 1), state.finish())
        assertEquals(listOf(2L, 1L, 3L), state.items.map { it.id })

        // A stale source emission must not snap the first visual move back.
        state.sync(original)
        assertEquals(listOf(2L, 1L, 3L), state.items.map { it.id })

        assertTrue(state.start(1, Offset(100f, 0f)))
        state.dragBy(Offset(80f, 0f), layouts(2, 1, 3))
        assertEquals(TabReorderCommit(1, 2), state.finish())
        assertEquals(listOf(2L, 3L, 1L), state.items.map { it.id })

        // The first commit can arrive after the second gesture has already finished.
        state.sync(listOf(item(2), item(1), item(3)))
        assertEquals(listOf(2L, 3L, 1L), state.items.map { it.id })

        state.sync(listOf(item(2), item(3), item(1)))
        assertEquals(listOf(2L, 3L, 1L), state.items.map { it.id })
    }

    @Test
    fun `drag cannot cross pinned boundary`() {
        val state = state().apply { sync(listOf(item(1, pinned = true), item(2), item(3))) }

        assertTrue(state.start(1, Offset(0f, 0f)))
        state.dragBy(Offset(190f, 0f), layouts(1, 2, 3))

        assertNull(state.finish())
        assertEquals(listOf(1L, 2L, 3L), state.items.map { it.id })
    }

    @Test
    fun `cancel restores authoritative order and clears translation`() {
        val source = listOf(item(1), item(2), item(3))
        val state = state().apply { sync(source) }

        state.start(1, Offset.Zero)
        state.dragBy(Offset(80f, 0f), layouts(1, 2, 3))
        state.cancel(source)

        assertNull(state.draggedId)
        assertEquals(Offset.Zero, state.dragOffset)
        assertEquals(listOf(1L, 2L, 3L), state.items.map { it.id })
    }

    @Test
    fun `metadata refresh preserves pending stable id order`() {
        val state = state().apply { sync(listOf(item(1), item(2))) }
        state.start(1, Offset.Zero)
        state.dragBy(Offset(80f, 0f), layouts(1, 2))
        state.finish()

        state.sync(listOf(item(1, title = "updated"), item(2)))

        assertEquals(listOf(2L, 1L), state.items.map { it.id })
        assertEquals("updated", state.items.last().title)
    }

    @Test
    fun `authoritative removal is visible before reorder acknowledgement`() {
        val original = listOf(item(1), item(2), item(3))
        val state = state().apply { sync(original) }
        state.start(1, Offset.Zero)
        state.dragBy(Offset(80f, 0f), layouts(1, 2, 3))
        state.finish()

        val afterClose = listOf(item(2), item(3))

        assertEquals(listOf(2L, 3L), state.items(afterClose).map { it.id })
    }

    private fun state() = TabGridReorderState<TestItem>(
        idOf = { it.id },
        canCross = { first, second -> first.pinned == second.pinned },
    )

    private fun item(id: Long, pinned: Boolean = false, title: String = "") =
        TestItem(id, pinned, title)

    private fun layouts(vararg ids: Long): List<TabGridItemLayout> = ids.mapIndexed { index, id ->
        val x = index * 100f
        TabGridItemLayout(id, center = Offset(x, 0f), offset = Offset(x, 0f))
    }

    private data class TestItem(val id: Long, val pinned: Boolean, val title: String)
}
