package com.elewashy.nexa.feature.tabs.domain.usecase

import com.elewashy.nexa.feature.tabs.domain.model.TabWorkspaceState
import javax.inject.Inject

/** What a system/toolbar Back press must do in the browser. */
sealed interface BackNavigation {
    /** Step back inside the active tab's own navigation history. */
    data object PageHistory : BackNavigation

    /**
     * The active tab is at the start of its history and was opened by another live tab:
     * close [closingTabId] and return to [openerTabId], which keeps its exact state.
     */
    data class ReturnToOpener(val closingTabId: Long, val openerTabId: Long) : BackNavigation

    /** Nothing left to navigate back to; leave the browser (the session stays persisted). */
    data object Exit : BackNavigation
}

/**
 * Resolves Back for the active tab. Pure and synchronous so the decision is made against one
 * consistent workspace snapshot at the moment of the press.
 */
class ResolveBackNavigationUseCase @Inject constructor() {

    operator fun invoke(workspace: TabWorkspaceState, canGoBackInPage: Boolean): BackNavigation {
        if (canGoBackInPage) return BackNavigation.PageHistory
        val active = workspace.activeTab ?: return BackNavigation.Exit
        val opener = workspace.openerOf(active.id) ?: return BackNavigation.Exit
        return BackNavigation.ReturnToOpener(closingTabId = active.id, openerTabId = opener.id)
    }
}
