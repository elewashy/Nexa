package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.HomePage
import com.elewashy.nexa.feature.browser.domain.model.HomePageUrls
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.browser.presentation.iconRes
import com.elewashy.nexa.feature.browser.presentation.labelRes
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.SettingsLoadingContent
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.Link

/** Stateful entry point: reads and writes the home page through [SettingsViewModel]. */
@Composable
fun HomePageSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val homePage by viewModel.homePage.collectAsStateWithLifecycle()
    val searchEngine by viewModel.selectedSearchEngine.collectAsStateWithLifecycle()
    HomePageSettingsContent(
        homePage = homePage,
        searchEngine = searchEngine,
        onHomePageChange = viewModel::setHomePage,
        onBackClick = onBackClick,
    )
}

/**
 * Two choices, as a radio group: the search engine's home page (applied at once), or a custom
 * address (applied when a valid address is saved). Invalid input is explained in the field
 * instead of being silently rewritten.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomePageSettingsContent(
    homePage: HomePage?,
    searchEngine: SearchEngine,
    onHomePageChange: (HomePage) -> Unit,
    onBackClick: () -> Unit,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { scrollState.canScrollBackward || scrollState.canScrollForward }
    )

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.home_page)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(ArrowBackFilled, contentDescription = stringResource(R.string.back))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        if (homePage == null) {
            SettingsLoadingContent(modifier = Modifier.padding(paddingValues))
            return@Scaffold
        }
        val storedCustomUrl = (homePage as? HomePage.Custom)?.url
        var customSelected by rememberSaveable { mutableStateOf(homePage is HomePage.Custom) }
        var input by rememberSaveable { mutableStateOf(storedCustomUrl.orEmpty()) }
        var showInvalid by rememberSaveable { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current

        fun saveCustom() {
            val normalized = HomePageUrls.normalize(input)
            if (normalized == null) {
                showInvalid = true
                return
            }
            input = normalized
            showInvalid = false
            focusManager.clearFocus()
            onHomePageChange(HomePage.Custom(normalized))
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = adaptiveInfo.listMaxWidth)
                    .fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.home_page_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
                Column(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clip(MaterialTheme.shapes.large)
                        .selectableGroup(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    HomePageOption(
                        selected = !customSelected,
                        headline = stringResource(R.string.home_page_search_engine),
                        supporting = stringResource(searchEngine.labelRes),
                        leading = {
                            ExpressiveListIcon(painter = painterResource(searchEngine.iconRes))
                        },
                        onClick = {
                            customSelected = false
                            showInvalid = false
                            focusManager.clearFocus()
                            onHomePageChange(HomePage.SearchEngineHome)
                        },
                    )
                    HomePageOption(
                        selected = customSelected,
                        headline = stringResource(R.string.home_page_custom),
                        supporting = storedCustomUrl,
                        leading = { ExpressiveListIcon(icon = Link) },
                        onClick = { customSelected = true },
                    )
                }

                AnimatedVisibility(
                    visible = customSelected,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val errorText = stringResource(R.string.home_page_invalid_url)
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedTextField(
                            value = input,
                            onValueChange = {
                                input = it
                                showInvalid = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { if (showInvalid) error(errorText) },
                            label = { Text(stringResource(R.string.home_page_custom_label)) },
                            placeholder = { Text(stringResource(R.string.home_page_custom_placeholder)) },
                            singleLine = true,
                            isError = showInvalid,
                            supportingText = if (showInvalid) {
                                { Text(errorText) }
                            } else {
                                null
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Done,
                                autoCorrectEnabled = false,
                            ),
                            keyboardActions = KeyboardActions(onDone = { saveCustom() }),
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(
                                onClick = ::saveCustom,
                                enabled = input.isNotBlank() && input.trim() != storedCustomUrl,
                                shapes = ButtonDefaults.shapes(),
                            ) {
                                Text(stringResource(R.string.save))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomePageOption(
    selected: Boolean,
    headline: String,
    supporting: String?,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    ListItem(
        // The whole row is the radio target, so TalkBack announces one radio button per option.
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        leadingContent = leading,
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { RadioButton(selected = selected, onClick = null) },
    ) { Text(headline) }
}
