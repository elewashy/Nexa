package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.browser.presentation.iconRes
import com.elewashy.nexa.feature.browser.presentation.labelRes
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.icons.ArrowBackFilled

/** Stateful entry point: reads the selection from [SettingsViewModel]. */
@Composable
fun SearchEngineSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val selectedEngine by viewModel.selectedSearchEngine.collectAsStateWithLifecycle()
    SearchEngineSettingsContent(
        selectedEngine = selectedEngine,
        onEngineSelected = viewModel::setSelectedSearchEngine,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SearchEngineSettingsContent(
    selectedEngine: SearchEngine,
    onEngineSelected: (SearchEngine) -> Unit,
    onBackClick: () -> Unit,
) {
    val engines = SearchEngine.entries
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { listState.canScrollBackward || listState.canScrollForward }
    )

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.search_engine)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .selectableGroup(),
            state = listState,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            itemsIndexed(engines, key = { _, engine -> engine.storedValue }) { index, engine ->
                SearchEngineListItem(
                    engine = engine,
                    selected = selectedEngine == engine,
                    onClick = { onEngineSelected(engine) },
                )
                if (index < engines.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchEngineListItem(
    engine: SearchEngine,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        // The whole row is the radio target so TalkBack announces one
        // "selected/not selected" radio button per engine.
        modifier = Modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.RadioButton,
        ),
        leadingContent = {
            ExpressiveListIcon(
                painter = painterResource(engine.iconRes),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                iconColor = MaterialTheme.colorScheme.onSurface,
            )
        },
        trailingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
    ) { Text(stringResource(engine.labelRes)) }
}
