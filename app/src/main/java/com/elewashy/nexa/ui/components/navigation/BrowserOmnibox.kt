package com.elewashy.nexa.ui.components.navigation

import androidx.core.net.toUri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.core.text.contentDirectionScaleX
import com.elewashy.nexa.feature.browser.presentation.BrowserOmniboxMode
import com.elewashy.nexa.feature.browser.presentation.BrowserOmniboxState
import com.elewashy.nexa.feature.history.domain.model.HistorySuggestion
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.SiteFavicon
import com.elewashy.nexa.ui.icons.History
import com.elewashy.nexa.ui.icons.NorthWest
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.ContentCopy
import com.elewashy.nexa.ui.icons.Edit
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.Share
import kotlinx.coroutines.flow.distinctUntilChanged

/** Full-height, keyboard-aware layer used after the in-bar address control is activated. */
@Composable
fun BrowserOmniboxOverlay(
    state: BrowserOmniboxState,
    currentUrl: String,
    currentTitle: String,
    isPrivate: Boolean,
    onQueryChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onEditCurrentUrl: () -> Unit,
    onShareCurrentUrl: (String) -> Unit,
    onCopyCurrentUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = state.mode.isOverlayVisible
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically(
            animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
            initialOffsetY = { it },
        ),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 3 }),
        modifier = modifier,
    ) {
        val adaptive = rememberAdaptiveLayoutInfo()
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    // Insets are consumed by Compose, so navigation bars and the IME never double-pad.
                    .navigationBarsPadding()
                    .imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = adaptive.contentMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                ) {
                    OmniboxInput(
                        state = state,
                        onQueryChange = onQueryChange,
                        onCommit = onCommit,
                        onDismiss = onDismiss,
                    )
                    OmniboxResults(
                        state = state,
                        currentUrl = currentUrl,
                        currentTitle = currentTitle,
                        isPrivate = isPrivate,
                        onCommit = onCommit,
                        onPopulateQuery = onQueryChange,
                        onEditCurrentUrl = onEditCurrentUrl,
                        onShareCurrentUrl = onShareCurrentUrl,
                        onCopyCurrentUrl = onCopyCurrentUrl,
                    )
                }
            }
        }
    }
}

@Composable
private fun OmniboxInput(
    state: BrowserOmniboxState,
    onQueryChange: (String) -> Unit,
    onCommit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val initialSelection = if (state.mode == BrowserOmniboxMode.EditUrl) {
        TextRange(0, state.query.length)
    } else {
        TextRange(state.query.length)
    }
    val textFieldState = remember(state.mode) {
        TextFieldState(
            initialText = state.query,
            initialSelection = initialSelection,
        )
    }
    LaunchedEffect(state.mode) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect(onQueryChange)
    }
    LaunchedEffect(state.query) {
        if (state.query != textFieldState.text.toString()) {
            textFieldState.edit {
                replace(0, length, state.query)
                selection = TextRange(state.query.length)
            }
        }
    }

    TextField(
        state = textFieldState,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 52.dp)
            .focusRequester(focusRequester),
        lineLimits = TextFieldLineLimits.SingleLine,
        shape = MaterialTheme.shapes.extraLarge,
        leadingIcon = { Icon(Search, contentDescription = null) },
        trailingIcon = {
            IconButton(
                onClick = {
                    if (textFieldState.text.isEmpty()) {
                        onDismiss()
                    } else {
                        textFieldState.edit { replace(0, length, "") }
                    }
                },
            ) {
                Icon(
                    Close,
                    contentDescription = stringResource(
                        if (textFieldState.text.isEmpty()) R.string.close_search else R.string.clear_search
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        placeholder = { Text(stringResource(R.string.search_or_enter_address)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        onKeyboardAction = { onCommit(textFieldState.text.toString()) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun OmniboxResults(
    state: BrowserOmniboxState,
    currentUrl: String,
    currentTitle: String,
    isPrivate: Boolean,
    onCommit: (String) -> Unit,
    onPopulateQuery: (String) -> Unit,
    onEditCurrentUrl: () -> Unit,
    onShareCurrentUrl: (String) -> Unit,
    onCopyCurrentUrl: (String) -> Unit,
) {
    val localUrls = remember(state.localResults) {
        state.localResults.mapTo(hashSetOf()) { it.url.lowercase() }
    }
    val previousQueries = remember(state.matchingSearchHistory) {
        state.matchingSearchHistory.mapTo(hashSetOf()) { it.lowercase() }
    }
    val filteredRemoteResults = remember(state.remoteResults, localUrls, previousQueries) {
        state.remoteResults.filterNot { suggestion ->
            suggestion.lowercase() in localUrls || suggestion.lowercase() in previousQueries
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (state.query.isBlank()) {
            if (state.mode == BrowserOmniboxMode.Search && currentUrl.isNotBlank()) {
                item(key = "current-page", contentType = "current-page") {
                    CurrentPageItem(
                        url = currentUrl,
                        title = currentTitle,
                        allowPersistentFaviconLookup = !isPrivate,
                        onOpen = onCommit,
                        onEdit = onEditCurrentUrl,
                        onShare = { onShareCurrentUrl(currentUrl) },
                        onCopy = { onCopyCurrentUrl(currentUrl) },
                    )
                }
            }
            if (state.frequentSites.isNotEmpty()) {
                item(key = "frequent-sites", contentType = "frequent-sites") {
                    FrequentSites(items = state.frequentSites, onCommit = onCommit)
                }
            }
            if (state.searchHistory.isNotEmpty()) {
                item(key = "search-history-title", contentType = "heading") {
                    SectionHeading(stringResource(R.string.recent_searches))
                }
                items(
                    items = state.searchHistory,
                    key = { "search-history-$it" },
                    contentType = { "search-history" },
                ) { query ->
                    SearchHistoryResult(
                        query = query,
                        onCommit = onCommit,
                        onPopulate = onPopulateQuery,
                    )
                }
            }
        } else {
            items(
                items = state.matchingSearchHistory,
                key = { "matching-search-history-$it" },
                contentType = { "search-history" },
            ) { query ->
                SearchHistoryResult(
                    query = query,
                    onCommit = onCommit,
                    onPopulate = onPopulateQuery,
                )
            }
            items(
                items = state.localResults,
                key = { "local-${it.url}" },
                contentType = { "history" },
            ) { suggestion ->
                HistoryResult(suggestion, onCommit, onPopulateQuery)
            }
            items(
                items = filteredRemoteResults,
                key = { "google-$it" },
                contentType = { "google" },
            ) { suggestion ->
                SearchResult(suggestion, onCommit, onPopulateQuery)
            }
        }
    }
}

@Composable
private fun CurrentPageItem(
    url: String,
    title: String,
    allowPersistentFaviconLookup: Boolean,
    onOpen: (String) -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
) {
    val pageLabel = title.takeUnless {
        it.isBlank() || it.equals(url, ignoreCase = true) || it.startsWith("http://") || it.startsWith("https://")
    } ?: hostLabel(url)
    ListItem(
        modifier = Modifier.clickable { onOpen(url) },
        supportingContent = {
            Text(hostLabel(url), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = {
            SiteFavicon(
                pageUrl = url,
                allowPersistentLookup = allowPersistentFaviconLookup,
                size = 28.dp,
            )
        },
        trailingContent = {
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Edit, contentDescription = stringResource(R.string.edit_url))
                }
                IconButton(onClick = onCopy) {
                    Icon(ContentCopy, contentDescription = stringResource(R.string.copy_url))
                }
                IconButton(onClick = onShare) {
                    Icon(Share, contentDescription = stringResource(R.string.share))
                }
            }
        },
    ) {
        Text(pageLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}

@Composable
private fun FrequentSites(items: List<HistorySuggestion>, onCommit: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 12.dp,
            end = 16.dp,
            bottom = 4.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(items = items, key = { it.url }, contentType = { "frequent-site" }) { item ->
            Column(
                modifier = Modifier
                    .size(width = 76.dp, height = 96.dp)
                    .clickable { onCommit(item.url) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(60.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        SiteFavicon(pageUrl = item.url, size = 40.dp)
                    }
                }
                Text(
                    text = hostLabel(item.url),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun HistoryResult(
    item: HistorySuggestion,
    onCommit: (String) -> Unit,
    onPopulate: (String) -> Unit,
) {
    val label = historyLabel(item)
    ListItem(
        modifier = Modifier.clickable { onCommit(item.url) },
        supportingContent = {
            Text(hostLabel(item.url), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        leadingContent = { SiteFavicon(pageUrl = item.url, size = 28.dp) },
        trailingContent = {
            PopulateQueryAction(text = item.url, onClick = { onPopulate(item.url) })
        },
    ) {
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SearchHistoryResult(
    query: String,
    onCommit: (String) -> Unit,
    onPopulate: (String) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCommit(query) },
        leadingContent = {
            Icon(
                imageVector = History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            PopulateQueryAction(text = query, onClick = { onPopulate(query) })
        },
    ) {
        Text(query, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun SearchResult(
    query: String,
    onCommit: (String) -> Unit,
    onPopulate: (String) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCommit(query) },
        leadingContent = { Icon(Search, contentDescription = null) },
        trailingContent = {
            PopulateQueryAction(text = query, onClick = { onPopulate(query) })
        },
    ) {
        Text(query, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun PopulateQueryAction(text: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = NorthWest,
            contentDescription = stringResource(R.string.fill_search_field),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer { scaleX = populateIconScaleX(text) },
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp)
            .semantics { heading() },
    )
}

internal fun populateIconScaleX(text: String): Float = text.contentDirectionScaleX()

private fun hostLabel(url: String): String = runCatching {
    url.toUri().host?.removePrefix("www.")
}.getOrNull().orEmpty().ifBlank { url }

private fun historyLabel(item: HistorySuggestion): String {
    if (item.title.isNotBlank()) return item.title
    val uri = runCatching { item.url.toUri() }.getOrNull()
    if (uri?.host?.contains("google.") == true && uri.path == "/search") {
        return uri.getQueryParameter("q").orEmpty().ifBlank { hostLabel(item.url) }
    }
    return hostLabel(item.url)
}
