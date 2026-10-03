package com.elewashy.nexa.feature.tabs.domain.usecase

import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveBackNavigationUseCaseTest {

    private val resolve = ResolveBackNavigationUseCase()

    private fun tab(id: Long, openerTabId: Long? = null) = TabItem(
        id = id,
        url = "https://$id.example/",
        title = "",
        position = id.toInt(),
        isActive = false,
        createdAt = id,
        lastAccessedAt = id,
        openerTabId = openerTabId,
    )

    @Test
    fun `page history always wins`() {
        val workspace = TabWorkspaceState(tabs = listOf(tab(1), tab(2, openerTabId = 1)), activeTabId = 2)

        assertEquals(BackNavigation.PageHistory, resolve(workspace, canGoBackInPage = true))
    }

    @Test
    fun `first page of a popup tab returns to its live opener`() {
        val workspace = TabWorkspaceState(tabs = listOf(tab(1), tab(2, openerTabId = 1)), activeTabId = 2)

        assertEquals(
            BackNavigation.ReturnToOpener(closingTabId = 2, openerTabId = 1),
            resolve(workspace, canGoBackInPage = false),
        )
    }

    @Test
    fun `exits only when there is nowhere left to go back to`() {
        val noOpener = TabWorkspaceState(tabs = listOf(tab(1), tab(2)), activeTabId = 2)
        val closedOpener = TabWorkspaceState(tabs = listOf(tab(2, openerTabId = 1)), activeTabId = 2)
        val empty = TabWorkspaceState()

        assertEquals(BackNavigation.Exit, resolve(noOpener, canGoBackInPage = false))
        assertEquals(BackNavigation.Exit, resolve(closedOpener, canGoBackInPage = false))
        assertEquals(BackNavigation.Exit, resolve(empty, canGoBackInPage = false))
    }
}
