package com.elewashy.nexa.feature.onboarding.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.permissions.AppPermission
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.browser.domain.model.SearchEngine
import com.elewashy.nexa.feature.browser.presentation.iconRes
import com.elewashy.nexa.feature.browser.presentation.labelRes
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.FramedAppIcon
import com.elewashy.nexa.ui.components.settings.ExpressiveListIcon
import com.elewashy.nexa.ui.components.settings.SettingsListItem
import com.elewashy.nexa.ui.icons.ArrowForward
import com.elewashy.nexa.ui.icons.ArrowForwardFilled
import com.elewashy.nexa.ui.icons.CheckCircle
import com.elewashy.nexa.ui.icons.Download
import com.elewashy.nexa.ui.icons.FolderOpen
import com.elewashy.nexa.ui.icons.History
import com.elewashy.nexa.ui.icons.InstallMobile
import com.elewashy.nexa.ui.icons.Notifications
import com.elewashy.nexa.ui.icons.Palette
import com.elewashy.nexa.ui.icons.Search
import com.elewashy.nexa.ui.icons.Security
import com.elewashy.nexa.ui.icons.Shield
import com.elewashy.nexa.ui.icons.Toolbar
import com.elewashy.nexa.ui.permissions.PermissionsRequestState
import com.elewashy.nexa.ui.permissions.rememberPermissionsRequestState
import com.elewashy.nexa.ui.theme.AppTheme

/** The onboarding pages, in order. */
enum class OnboardingStep {
    Welcome,
    Permissions,
    SearchEngine,
    Appearance,
}

/** Stateful steps page of the onboarding flow, backed by [OnboardingViewModel]. */
@Composable
fun OnboardingStepsRoute(
    onOpenTheme: () -> Unit,
    onOpenNavigationBarPosition: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    OnboardingScreen(
        state = state,
        permissions = rememberPermissionsRequestState(),
        onSearchEngineSelected = viewModel::selectSearchEngine,
        onOpenTheme = onOpenTheme,
        onOpenNavigationBarPosition = onOpenNavigationBarPosition,
        onFinish = viewModel::complete,
        modifier = modifier,
    )
}

/**
 * Four short pages — welcome, permissions, search engine, appearance — with a persistent Skip.
 * Every choice already has a sensible default, so nothing blocks the user:
 *
 * - Permissions can be granted one by one or skipped ("Skip for now"); whatever is still
 *   missing is requested again in context when a feature needs it (a download, an update).
 * - Appearance opens the app's real Theme and navigation-bar pages ([onOpenTheme],
 *   [onOpenNavigationBarPosition]), so the user configures them exactly as in Settings.
 *
 * The open page is saved state (configuration changes and process death keep it). Back steps
 * to the previous page; on the first page the system handles Back (predictive back to home),
 * and onboarding simply appears again on the next launch.
 */
@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    permissions: PermissionsRequestState,
    onSearchEngineSelected: (SearchEngine) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenNavigationBarPosition: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Welcome) }
    val steps = OnboardingStep.entries
    val isLastStep = step.ordinal == steps.lastIndex

    BackHandler(enabled = step.ordinal > 0) { step = steps[step.ordinal - 1] }

    val adaptive = rememberAdaptiveLayoutInfo()
    // Landscape on medium and larger windows: header and options side by side.
    val twoPane = adaptive.isLandscape && !adaptive.isCompact
    val layoutDirection = LocalLayoutDirection.current

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            OnboardingTopBar(
                step = step,
                showSkip = !isLastStep,
                onSkip = onFinish,
                modifier = Modifier.padding(horizontal = adaptive.horizontalPadding),
            )

            AnimatedContent(
                targetState = step,
                transitionSpec = { stepTransition(layoutDirection) },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                label = "onboardingStep",
            ) { page ->
                val padding = adaptive.horizontalPadding
                when (page) {
                    OnboardingStep.Welcome -> OnboardingPage(
                        twoPane = twoPane,
                        horizontalPadding = padding,
                        contentMaxWidth = adaptive.contentMaxWidth,
                        header = { WelcomeHeader() },
                        body = { WelcomeFeatures() },
                    )

                    OnboardingStep.Permissions -> OnboardingPage(
                        twoPane = twoPane,
                        horizontalPadding = padding,
                        contentMaxWidth = adaptive.contentMaxWidth,
                        header = {
                            StepHeader(
                                icon = Security,
                                title = stringResource(R.string.onboarding_permissions_title),
                                message = stringResource(R.string.onboarding_permissions_message),
                            )
                        },
                        body = { PermissionOptions(permissions) },
                    )

                    OnboardingStep.SearchEngine -> OnboardingPage(
                        twoPane = twoPane,
                        horizontalPadding = padding,
                        contentMaxWidth = adaptive.contentMaxWidth,
                        header = {
                            StepHeader(
                                icon = Search,
                                title = stringResource(R.string.onboarding_search_title),
                                message = stringResource(R.string.onboarding_search_message),
                            )
                        },
                        body = {
                            SearchEngineOptions(
                                selected = state.searchEngine,
                                onSelected = onSearchEngineSelected,
                            )
                        },
                    )

                    OnboardingStep.Appearance -> OnboardingPage(
                        twoPane = twoPane,
                        horizontalPadding = padding,
                        contentMaxWidth = adaptive.contentMaxWidth,
                        header = {
                            StepHeader(
                                icon = Palette,
                                title = stringResource(R.string.onboarding_appearance_title),
                                message = stringResource(R.string.onboarding_appearance_message),
                            )
                        },
                        body = {
                            AppearanceOptions(
                                theme = state.theme,
                                onOpenTheme = onOpenTheme,
                                // Medium and larger windows use a navigation rail instead.
                                navigationBarPosition = state.navigationBarPosition.takeUnless {
                                    adaptive.useSideNavigation
                                },
                                onOpenNavigationBarPosition = onOpenNavigationBarPosition,
                            )
                        },
                    )
                }
            }

            OnboardingBottomBar(
                showBack = step.ordinal > 0,
                isLastStep = isLastStep,
                // Moving on with permissions missing is an explicit, low-emphasis skip.
                skipsPermissions = step == OnboardingStep.Permissions && !permissions.allGranted,
                onBack = { step = steps[step.ordinal - 1] },
                onNext = { if (isLastStep) onFinish() else step = steps[step.ordinal + 1] },
                modifier = Modifier.padding(horizontal = adaptive.horizontalPadding),
            )
        }
    }
}

// ========== Structure ==========

@Composable
private fun OnboardingTopBar(
    step: OnboardingStep,
    showSkip: Boolean,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepIndicator(current = step)
        Spacer(modifier = Modifier.weight(1f))
        if (showSkip) {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
        }
    }
}

/** Compact page dots; the current one stretches into a pill. Announced as "Step x of y". */
@Composable
private fun StepIndicator(current: OnboardingStep, modifier: Modifier = Modifier) {
    val steps = OnboardingStep.entries
    val description = stringResource(R.string.onboarding_step_progress, current.ordinal + 1, steps.size)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        steps.forEach { step ->
            val active = step == current
            val width by animateDpAsState(
                targetValue = if (active) 24.dp else 8.dp,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "stepIndicatorWidth",
            )
            val color by animateColorAsState(
                targetValue = if (step.ordinal <= current.ordinal) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
                label = "stepIndicatorColor",
            )
            Box(
                modifier = Modifier
                    .size(width = width, height = 8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    showBack: Boolean,
    isLastStep: Boolean,
    skipsPermissions: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showBack) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        }
        Spacer(modifier = Modifier.weight(1f))
        if (skipsPermissions) {
            FilledTonalButton(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.onboarding_skip_for_now))
            }
            return@Row
        }
        Button(onClick = onNext, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                stringResource(if (isLastStep) R.string.onboarding_get_started else R.string.onboarding_next)
            )
            if (!isLastStep) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = ArrowForwardFilled,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * One scrollable page. Single pane: header above the options, centered with a readable width.
 * Two panes: the header on the start side, the options scroll independently on the end side.
 */
@Composable
private fun OnboardingPage(
    twoPane: Boolean,
    horizontalPadding: Dp,
    contentMaxWidth: Dp,
    header: @Composable ColumnScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    if (twoPane) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = contentMaxWidth)
                    .padding(horizontal = horizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    content = header,
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = body,
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().widthIn(max = PAGE_MAX_WIDTH),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                header()
                Spacer(modifier = Modifier.height(8.dp))
                body()
            }
        }
    }
}

// ========== Pages ==========

@Composable
private fun ColumnScope.WelcomeHeader() {
    // A single, short entrance for the app icon; the rest of the page is static.
    val iconVisible = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = iconVisible,
        enter = fadeIn(tween(ICON_ENTER_MS, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(ICON_ENTER_MS, easing = FastOutSlowInEasing), initialScale = 0.8f),
    ) {
        FramedAppIcon(size = 88.dp)
    }
    Text(
        text = stringResource(R.string.onboarding_welcome_title, stringResource(R.string.app_name)),
        style = MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = stringResource(R.string.onboarding_welcome_message),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WelcomeFeatures() {
    OptionGroup {
        FeatureRow(
            icon = Shield,
            title = stringResource(R.string.onboarding_feature_adblock_title),
            message = stringResource(R.string.onboarding_feature_adblock_message),
        )
        FeatureRow(
            icon = Download,
            title = stringResource(R.string.onboarding_feature_downloads_title),
            message = stringResource(R.string.onboarding_feature_downloads_message),
        )
        FeatureRow(
            icon = History,
            title = stringResource(R.string.onboarding_feature_tabs_title),
            message = stringResource(R.string.onboarding_feature_tabs_message),
        )
    }
}

@Composable
private fun StepHeader(icon: ImageVector, title: String, message: String) {
    ExpressiveListIcon(
        icon = icon,
        size = 56.dp,
        iconSize = 28.dp,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    )
    Text(
        text = title,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SearchEngineOptions(
    selected: SearchEngine,
    onSelected: (SearchEngine) -> Unit,
) {
    OptionGroup(modifier = Modifier.selectableGroup()) {
        SearchEngine.entries.forEach { engine ->
            OptionRow(
                selected = engine == selected,
                onClick = { onSelected(engine) },
                title = stringResource(engine.labelRes),
                leadingContent = {
                    ExpressiveListIcon(
                        painter = painterResource(engine.iconRes),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        iconColor = MaterialTheme.colorScheme.onSurface,
                        size = 40.dp,
                        iconSize = 22.dp,
                    )
                },
            )
        }
    }
}

/**
 * Entry points to the app's real Theme and navigation-bar pages, showing the current choice
 * (the same rows as General settings).
 */
@Composable
private fun AppearanceOptions(
    theme: AppTheme,
    onOpenTheme: () -> Unit,
    navigationBarPosition: BrowserNavigationBarPosition?,
    onOpenNavigationBarPosition: () -> Unit,
) {
    OptionGroup {
        SettingsListItem(
            headlineContent = stringResource(R.string.onboarding_appearance_theme_title),
            supportingContent = stringResource(
                when (theme) {
                    AppTheme.SYSTEM -> R.string.theme_system
                    AppTheme.LIGHT -> R.string.theme_light
                    AppTheme.DARK -> R.string.theme_dark
                }
            ),
            leadingContent = { ExpressiveListIcon(icon = Palette, size = 40.dp, iconSize = 22.dp) },
            trailingContent = { NavigateIcon() },
            onClick = onOpenTheme,
        )
        if (navigationBarPosition != null) {
            SettingsListItem(
                headlineContent = stringResource(R.string.navigation_bar_position),
                supportingContent = stringResource(
                    when (navigationBarPosition) {
                        BrowserNavigationBarPosition.Bottom -> R.string.navigation_bar_position_bottom
                        BrowserNavigationBarPosition.Top -> R.string.navigation_bar_position_top
                    }
                ),
                leadingContent = { ExpressiveListIcon(icon = Toolbar, size = 40.dp, iconSize = 22.dp) },
                trailingContent = { NavigateIcon() },
                onClick = onOpenNavigationBarPosition,
            )
        }
    }

    Text(
        text = stringResource(R.string.onboarding_change_later),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )
}

@Composable
private fun NavigateIcon() {
    Icon(
        imageVector = ArrowForward,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** One row per permission: Grant opens the system dialog or Settings page; granted rows show a check. */
@Composable
private fun PermissionOptions(permissions: PermissionsRequestState) {
    OptionGroup {
        permissions.permissions.forEach { permission ->
            val (icon, titleRes, descriptionRes) = when (permission) {
                AppPermission.Storage ->
                    Triple(FolderOpen, R.string.permission_storage, R.string.permission_storage_description)
                AppPermission.Notifications ->
                    Triple(Notifications, R.string.permission_notifications, R.string.permission_notifications_description)
                AppPermission.InstallPackages ->
                    Triple(InstallMobile, R.string.permission_install_apps, R.string.permission_install_apps_description)
            }
            PermissionRow(
                icon = icon,
                title = stringResource(titleRes),
                description = stringResource(descriptionRes),
                granted = permissions.isGranted(permission),
                onGrant = { permissions.request(permission) },
            )
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    onGrant: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            ExpressiveListIcon(
                icon = icon,
                size = 40.dp,
                iconSize = 22.dp,
                containerColor = if (granted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                iconColor = if (granted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        trailingContent = {
            if (granted) {
                Icon(
                    imageVector = CheckCircle,
                    contentDescription = stringResource(R.string.permission_granted),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                val actionLabel = stringResource(R.string.permission_grant_named, title)
                FilledTonalButton(
                    onClick = onGrant,
                    modifier = Modifier.semantics { contentDescription = actionLabel },
                ) {
                    Text(stringResource(R.string.permission_grant))
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

// ========== Rows ==========

/** Material segmented-list grouping: rows share one rounded container with small gaps. */
@Composable
private fun OptionGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** Informational row; not clickable, so it is not announced as a button. */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, message: String) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(message) },
        leadingContent = { ExpressiveListIcon(icon = icon, size = 40.dp, iconSize = 22.dp) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    )
}

/** One choice of a single-choice group: the whole row is the radio button for TalkBack. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OptionRow(
    selected: Boolean,
    onClick: () -> Unit,
    title: String,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val containerColor by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "optionRowContainer",
    )
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = leadingContent,
        trailingContent = { RadioButton(selected = selected, onClick = null) },
        colors = ListItemDefaults.colors(containerColor = containerColor),
        modifier = Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    )
}

// ========== Motion ==========

/**
 * Material shared-axis X between pages, mirrored for RTL and for going back: a short slide
 * with a fade-through, so two pages never cross-blend at full opacity.
 */
private fun AnimatedContentTransitionScope<OnboardingStep>.stepTransition(
    layoutDirection: LayoutDirection,
): ContentTransform {
    val forward = targetState.ordinal > initialState.ordinal
    val sign = (if (forward) 1 else -1) * (if (layoutDirection == LayoutDirection.Ltr) 1 else -1)
    val slide = tween<IntOffset>(STEP_SLIDE_MS, easing = FastOutSlowInEasing)
    return (
        slideInHorizontally(slide) { width -> sign * width / STEP_SLIDE_DIVISOR } +
            fadeIn(tween(STEP_FADE_IN_MS, delayMillis = STEP_FADE_OUT_MS, easing = LinearOutSlowInEasing))
        ) togetherWith (
        slideOutHorizontally(slide) { width -> -sign * width / STEP_SLIDE_DIVISOR } +
            fadeOut(tween(STEP_FADE_OUT_MS, easing = LinearEasing))
        )
}

private val PAGE_MAX_WIDTH = 560.dp
private const val ICON_ENTER_MS = 400
private const val STEP_SLIDE_MS = 300
private const val STEP_FADE_OUT_MS = 90
private const val STEP_FADE_IN_MS = 210
private const val STEP_SLIDE_DIVISOR = 5
