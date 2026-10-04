package com.elewashy.nexa.feature.adblock.presentation

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.domain.model.SiteAdBlockSettings
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockEmptyState
import com.elewashy.nexa.feature.adblock.presentation.components.AdBlockPageScaffold
import com.elewashy.nexa.feature.adblock.presentation.components.AddSiteDialog
import com.elewashy.nexa.feature.adblock.presentation.components.HostText
import com.elewashy.nexa.feature.adblock.presentation.components.SectionFooter
import com.elewashy.nexa.feature.adblock.presentation.components.SiteSettingsDialog
import com.elewashy.nexa.feature.adblock.presentation.components.adBlockListWidth
import com.elewashy.nexa.ui.icons.Add
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.WebAssetOff

/**
 * Every site with its own ad-blocking settings (uBO's trusted sites and
 * per-site switches), including those changed from the browser menu, so a
 * site can be re-enabled without visiting it again.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SitesScreen(
    onBackClick: () -> Unit,
    viewModel: SitesViewModel = hiltViewModel(),
) {
    val resources = LocalResources.current
    val sites by viewModel.sites.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var adding by rememberSaveable { mutableStateOf(false) }
    var openHost by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        viewModel.onMessageShown()
        when (current) {
            is SitesMessage.Reset -> snackbarHostState.showSnackbar(resources.getString(R.string.adblock_site_reset_done, current.host))
        }
    }

    val loaded = sites
    AdBlockPageScaffold(
        title = stringResource(R.string.adblock_sites),
        onBackClick = onBackClick,
        snackbarHostState = snackbarHostState,
        itemSpacing = ListItemDefaults.SegmentedGap,
        bottomPadding = 96.dp,
        floatingActionButton = {
            if (loaded != null) {
                ExtendedFloatingActionButton(
                    onClick = { adding = true },
                    icon = { Icon(Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.adblock_add_site)) },
                )
            }
        },
        content = loaded?.let { list ->
            {
                item(key = "intro") {
                    SectionFooter(
                        stringResource(R.string.adblock_sites_intro),
                        Modifier.adBlockListWidth().padding(top = 8.dp, bottom = 8.dp),
                    )
                }
                if (list.isEmpty()) {
                    item(key = "empty") {
                        AdBlockEmptyState(
                            icon = WebAssetOff,
                            title = stringResource(R.string.adblock_sites_empty_title),
                            description = stringResource(R.string.adblock_sites_empty_description),
                            modifier = Modifier.adBlockListWidth(),
                        )
                    }
                }
                itemsIndexed(list, key = { _, site -> site.host }) { index, site ->
                    SiteRow(
                        site = site,
                        index = index,
                        count = list.size,
                        onClick = { openHost = site.host },
                        onBlockingChange = { viewModel.save(site.copy(blockingEnabled = it)) },
                        modifier = Modifier
                            .adBlockListWidth()
                            .padding(horizontal = 16.dp)
                            .animateItem(),
                    )
                }
            }
        },
    )

    if (adding) {
        AddSiteDialog(onAdd = viewModel::add, onDismiss = { adding = false })
    }
    val host = openHost
    if (host != null && loaded != null) {
        // A site whose switches are all back to default has no row any more; keep showing it as such.
        val settings = loaded.firstOrNull { it.host == host } ?: SiteAdBlockSettings(host)
        SiteSettingsDialog(
            settings = settings,
            onChange = viewModel::save,
            onReset = {
                viewModel.reset(host)
                openHost = null
            },
            onDismiss = { openHost = null },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SiteRow(
    site: SiteAdBlockSettings,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    onBlockingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier,
        supportingContent = { Text(siteSummary(site)) },
        trailingContent = {
            Switch(
                checked = site.blockingEnabled,
                onCheckedChange = onBlockingChange,
                thumbContent = {
                    Icon(
                        imageVector = if (site.blockingEnabled) Check else Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HostText(site.host)
    }
}

/** "Ad blocking off", or the individual switches that differ from the defaults. */
@Composable
private fun siteSummary(site: SiteAdBlockSettings): String {
    if (!site.blockingEnabled) return stringResource(R.string.adblock_site_summary_off)
    return buildList {
        if (!site.cosmeticFilteringEnabled) add(stringResource(R.string.adblock_site_summary_cosmetic_off))
        if (!site.popupBlockingEnabled) add(stringResource(R.string.adblock_site_summary_popups_allowed))
    }.joinToString(" · ")
}
