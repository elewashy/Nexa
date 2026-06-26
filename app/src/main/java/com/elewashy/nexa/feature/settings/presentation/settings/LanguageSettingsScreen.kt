package com.elewashy.nexa.feature.settings.presentation.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.localization.AppLanguage
import com.elewashy.nexa.core.localization.AppLanguageManager
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.SearchFilled

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LanguageSettingsScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearchActive by rememberSaveable { mutableStateOf(false) }

    val languageListState = rememberLazyListState()
    val isLanguageListScrollable by remember {
        derivedStateOf {
            languageListState.canScrollBackward || languageListState.canScrollForward
        }
    }

    val allLanguages = AppLanguageManager.supportedLanguages

    val filteredLanguages = remember(searchQuery, allLanguages, resources) {
        if (searchQuery.isEmpty()) {
            allLanguages
        } else {
            allLanguages.filter { language ->
                val localizedName = resources.getString(language.labelRes)
                val nativeName = language.nativeName.orEmpty()
                localizedName.contains(searchQuery, ignoreCase = true) ||
                    nativeName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { isLanguageListScrollable }
    )

    fun onLanguagePicked(language: AppLanguage) {
        viewModel.setLanguage(language) {
            context.findActivity()?.recreate()
        }
    }

    if (isSearchActive) {
        LanguageSearchView(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onClose = {
                isSearchActive = false
                searchQuery = ""
            },
            filteredLanguages = filteredLanguages,
            currentLanguage = currentLanguage,
            onLanguagePicked = ::onLanguagePicked,
        )
        return
    }

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.language)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(
                            SearchFilled,
                            contentDescription = stringResource(R.string.search_language),
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
                .padding(paddingValues),
            state = languageListState,
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            // "System default" entry (AppLanguage.System)
            item {
                LanguageListItem(
                    headlineText = stringResource(R.string.language_system_default),
                    supportingText = null,
                    selected = currentLanguage == AppLanguage.System,
                    onClick = {
                        onLanguagePicked(AppLanguage.System)
                    },
                )
            }

            item {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp)
                )
            }

            // Specific languages (EN / AR / FR)
            items(AppLanguageManager.selectableLanguages, key = { it.name }) { language ->
                val localizedName = stringResource(language.labelRes)
                val nativeName = language.nativeName

                LanguageListItem(
                    headlineText = localizedName,
                    supportingText = if (nativeName != null && nativeName != localizedName) nativeName else null,
                    selected = currentLanguage == language,
                    onClick = { onLanguagePicked(language) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSearchView(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    filteredLanguages: List<AppLanguage>,
    currentLanguage: AppLanguage,
    onLanguagePicked: (AppLanguage) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    SearchBar(
        inputField = {
            SearchBarDefaults.InputField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = { keyboardController?.hide() },
                expanded = true,
                onExpandedChange = { if (!it) onClose() },
                placeholder = { Text(stringResource(R.string.search_language)) },
                leadingIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(
                                Close,
                                contentDescription = stringResource(R.string.clear_search),
                            )
                        }
                    }
                },
            )
        },
        expanded = true,
        onExpandedChange = { if (!it) onClose() },
        modifier = Modifier.focusRequester(focusRequester),
    ) {
        if (query.isEmpty()) {
            // Empty state: show a centred search icon + hint
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Search,
                    contentDescription = stringResource(R.string.search_language),
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.search_language_hint),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(filteredLanguages, key = { it.name }) { language ->
                    val localizedName = resources.getString(language.labelRes)
                    val nativeName = language.nativeName

                    LanguageListItem(
                        headlineText = localizedName,
                        supportingText = if (nativeName != null && nativeName != localizedName) nativeName else null,
                        selected = currentLanguage == language,
                        onClick = {
                            onLanguagePicked(language)
                            onClose()
                        },
                    )
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun LanguageListItem(
    headlineText: String,
    supportingText: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            RadioButton(
                selected = selected,
                onClick = null,
            )
        },
        headlineContent = { Text(headlineText) },
        supportingContent = supportingText?.let { { Text(it) } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
