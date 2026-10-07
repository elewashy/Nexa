package com.elewashy.nexa.feature.browser.presentation.error

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.browser.domain.model.CertificateDetails
import com.elewashy.nexa.feature.browser.domain.model.PageLoadError
import com.elewashy.nexa.ui.icons.Error
import com.elewashy.nexa.ui.icons.Globe
import com.elewashy.nexa.ui.icons.RemoveModerator
import com.elewashy.nexa.ui.icons.Security
import com.elewashy.nexa.ui.icons.WifiOff
import java.text.DateFormat
import java.util.Date

/**
 * Browser error page shown over a tab whose main-frame navigation failed — the native
 * counterpart of Chrome's net-error and certificate interstitials.
 *
 * It says what failed and for which site, what the user can try, the exact error code, and
 * (under Details) the address and certificate, and offers the next step that actually helps:
 * reload, go back or home, open the connectivity settings, resend a form, or leave a dangerous site.
 *
 * The page is opaque to touch: it covers the WebView's own error document, which must never
 * receive the taps meant for this page.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun BrowserErrorPage(
    error: PageLoadError,
    canGoBack: Boolean,
    isReloading: Boolean,
    onAction: (ErrorPageAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val model = remember(error, canGoBack) { error.toErrorPageUiModel(canGoBack) }
    // Details stay open across configuration changes, but collapse for a different failure.
    var showDetails by rememberSaveable(error.url, error.errorCode) { mutableStateOf(false) }
    val pageTitle = stringResource(model.title)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            // A hit target of its own, so touches never fall through to the WebView below.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { paneTitle = pageTitle },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
        ) {
            ErrorIllustration(model)
            Spacer(Modifier.height(24.dp))
            Text(
                text = pageTitle,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = if (model.messageNamesHost) {
                    stringResource(model.message, error.host)
                } else {
                    stringResource(model.message)
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            model.certificateReason?.let { reason ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(reason),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (model.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Suggestions(model.suggestions)
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = error.errorCode,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AnimatedVisibility(
                visible = showDetails,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                ErrorDetails(error = error, modifier = Modifier.padding(top = 20.dp))
            }

            Spacer(Modifier.height(32.dp))
            ErrorActions(
                model = model,
                showDetails = showDetails,
                isReloading = isReloading,
                onToggleDetails = { showDetails = !showDetails },
                onAction = onAction,
            )
        }
    }
}

/**
 * Miniature of [BrowserErrorPage] for the tab overview: the same illustration, title and site, so
 * a tab whose navigation failed is recognisable there exactly as it looks on screen (a screenshot
 * of the tab would show WebView's own error document, which the error page covers).
 */
@Composable
fun BrowserErrorPagePreview(error: PageLoadError, modifier: Modifier = Modifier) {
    // The way out offered does not affect the preview; any value yields the same title and art.
    val model = remember(error) { error.toErrorPageUiModel(canGoBack = false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 14.dp, vertical = 18.dp),
    ) {
        ErrorIllustration(model, size = PreviewIllustrationSize, iconSize = PreviewIllustrationIconSize)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(model.title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = error.host,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ErrorIllustration(
    model: ErrorPageUiModel,
    size: Dp = IllustrationSize,
    iconSize: Dp = IllustrationIconSize,
) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = if (model.isSecurityWarning) {
        colors.errorContainer to colors.onErrorContainer
    } else {
        colors.secondaryContainer to colors.onSecondaryContainer
    }
    Surface(
        modifier = Modifier.size(size),
        shape = MaterialShapes.Cookie9Sided.toShape(),
        color = container,
        contentColor = content,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = model.illustration.icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun Suggestions(suggestions: List<Int>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = stringResource(R.string.error_page_try),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        suggestions.forEach { suggestion ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(suggestion),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ErrorDetails(error: PageLoadError, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // Selectable, so the address or code can be copied into a search or a bug report.
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DetailRow(stringResource(R.string.error_page_detail_address), error.url, monospace = true)
                DetailRow(stringResource(R.string.error_page_detail_error_code), error.errorCode, monospace = true)
                error.certificate?.let { CertificateRows(it) }
            }
        }
    }
}

@Composable
private fun CertificateRows(certificate: CertificateDetails) {
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0]
    val dateFormat = remember(locale) {
        if (locale != null) {
            DateFormat.getDateInstance(DateFormat.MEDIUM, locale)
        } else {
            DateFormat.getDateInstance(DateFormat.MEDIUM)
        }
    }
    certificate.issuedTo?.let { DetailRow(stringResource(R.string.error_page_detail_issued_to), it) }
    certificate.issuedBy?.let { DetailRow(stringResource(R.string.error_page_detail_issued_by), it) }
    certificate.validFromMillis?.let {
        DetailRow(stringResource(R.string.error_page_detail_valid_from), dateFormat.format(Date(it)))
    }
    certificate.validUntilMillis?.let {
        DetailRow(stringResource(R.string.error_page_detail_valid_until), dateFormat.format(Date(it)))
    }
}

@Composable
private fun DetailRow(label: String, value: String, monospace: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium.let {
                if (monospace) it.copy(fontFamily = FontFamily.Monospace) else it
            },
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Chrome's arrangement: the Details toggle leads, the actions trail with the most useful one
 * last as the filled button. Wraps onto several lines for large fonts or narrow windows.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ErrorActions(
    model: ErrorPageUiModel,
    showDetails: Boolean,
    isReloading: Boolean,
    onToggleDetails: () -> Unit,
    onAction: (ErrorPageAction) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onToggleDetails, shapes = ButtonDefaults.shapes()) {
            Text(
                stringResource(
                    if (showDetails) R.string.error_page_hide_details else R.string.error_page_show_details
                )
            )
        }
        Spacer(Modifier.weight(1f))
        model.secondaryAction?.let { action ->
            OutlinedButton(onClick = { onAction(action) }, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(action.label))
            }
        }
        val reloads = model.primaryAction == ErrorPageAction.Reload ||
            model.primaryAction == ErrorPageAction.Resend
        val busy = reloads && isReloading
        val reloadingLabel = stringResource(R.string.error_page_reloading)
        Button(
            onClick = { onAction(model.primaryAction) },
            enabled = !busy,
            shapes = ButtonDefaults.shapes(),
            modifier = if (busy) Modifier.semantics { stateDescription = reloadingLabel } else Modifier,
        ) {
            if (busy) {
                LoadingIndicator(modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            }
            Text(stringResource(model.primaryAction.label))
        }
    }
}

private val ErrorPageAction.label: Int
    get() = when (this) {
        ErrorPageAction.Reload -> R.string.error_page_reload
        ErrorPageAction.Resend -> R.string.error_page_resend
        ErrorPageAction.GoBack -> R.string.error_page_go_back
        ErrorPageAction.GoHome -> R.string.error_page_go_home
        ErrorPageAction.BackToSafety -> R.string.error_page_back_to_safety
        ErrorPageAction.NetworkSettings -> R.string.error_page_network_settings
    }

private val ErrorPageIllustration.icon: ImageVector
    get() = when (this) {
        ErrorPageIllustration.Offline -> WifiOff
        ErrorPageIllustration.Unreachable -> Globe
        ErrorPageIllustration.Broken -> Error
        ErrorPageIllustration.Security -> Security
        ErrorPageIllustration.Blocked -> RemoveModerator
    }

private val ContentMaxWidth = 600.dp
private val IllustrationSize = 72.dp
private val IllustrationIconSize = 36.dp
private val PreviewIllustrationSize = 40.dp
private val PreviewIllustrationIconSize = 20.dp
