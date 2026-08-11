package com.elewashy.nexa.feature.browser.presentation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.elewashy.nexa.R
import com.elewashy.nexa.core.display.RefreshRateManager
import com.elewashy.nexa.core.localization.AppLanguageManager
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.browser.data.adblock.AdBlockRepository
import com.elewashy.nexa.feature.browser.data.links.ValidLinkRepository
import com.elewashy.nexa.feature.browser.data.scripts.ScriptRepository
import com.elewashy.nexa.feature.browser.presentation.webview.ContextMenuHandler
import com.elewashy.nexa.feature.browser.presentation.webview.DownloadHandler
import com.elewashy.nexa.feature.browser.presentation.webview.NexaWebChromeClient
import com.elewashy.nexa.feature.browser.presentation.webview.NexaWebViewClient
import com.elewashy.nexa.feature.browser.presentation.webview.WebViewConfigurator
import com.elewashy.nexa.feature.browser.presentation.webview.ContextMenuResult
import com.elewashy.nexa.feature.browser.presentation.screen.ContextMenuAction
import com.elewashy.nexa.feature.browser.presentation.screen.Base64ImageDialog
import com.elewashy.nexa.feature.browser.presentation.screen.BrowserDownloadSnackbarDefaults
import com.elewashy.nexa.feature.browser.presentation.screen.BrowserSnackbarHost
import com.elewashy.nexa.feature.browser.presentation.screen.ContextMenuScreen
import com.elewashy.nexa.feature.downloads.presentation.screen.DownloadsRoute
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.update.presentation.UpdateScreen
import com.elewashy.nexa.feature.update.presentation.UpdateViewModel
import com.elewashy.nexa.feature.update.presentation.UpdateCheckViewModel
import com.elewashy.nexa.feature.update.presentation.components.AvailableUpdateDialog
import com.elewashy.nexa.feature.onboarding.OnboardingScreen
import com.elewashy.nexa.feature.onboarding.OnboardingViewModel
import com.elewashy.nexa.feature.settings.presentation.settings.SettingsNavigation
import com.elewashy.nexa.feature.settings.presentation.settings.SettingsViewModel
import com.elewashy.nexa.feature.share.data.SharePlatform
import com.elewashy.nexa.feature.share.data.SharePlatformDetector
import com.elewashy.nexa.feature.share.presentation.ShareActivity
import com.elewashy.nexa.feature.splash.presentation.SplashUiState
import com.elewashy.nexa.feature.splash.presentation.SplashViewModel
import com.elewashy.nexa.feature.splash.presentation.screen.LoadingScreen
import com.elewashy.nexa.feature.splash.presentation.screen.NoInternetScreen
import com.elewashy.nexa.ui.components.navigation.BrowserNavBar
import com.elewashy.nexa.ui.components.navigation.BrowserNavigationProgress
import com.elewashy.nexa.ui.components.navigation.BrowserNavigationRail
import com.elewashy.nexa.ui.components.navigation.BrowserUrlBar
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.theme.NexaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MainActivity — single Compose host for the app flow.
 *
 * Architecture:
 *  - Pure Compose host: the entire UI tree is rendered via `setContent`.
 *    The WebView (a native View) is embedded through `AndroidView`, and
 *    the fullscreen overlay container is a sibling `AndroidView`.
 *  - The Compose nav bar subscribes to [BrowserViewModel.uiState] via
 *    `collectAsStateWithLifecycle()`; the only non-Compose render path
 *    is the `FLAG_KEEP_SCREEN_ON` window flag, which is a `Window`
 *    side-effect rather than a UI node.
 *  - No XML layouts are used.
 *
 * Lifecycle:
 *  - onCreate: Initialize UI, permissions, and the VM observer.
 *  - onNewIntent: Route new intents (deep-link, download page).
 *  - onDestroy: Tear down WebView and clear KEEP_SCREEN_ON defensively.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // ========== Browser state ==========

    private val browserViewModel: BrowserViewModel by viewModels()
    private val splashViewModel: SplashViewModel by viewModels()
    private val onboardingViewModel: OnboardingViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val updateCheckViewModel: UpdateCheckViewModel by viewModels()

    // ========== WebView state (managed outside Compose to survive recomposition) ==========

    private var webView: WebView? = null
    private var chromeClient: NexaWebChromeClient? = null
    private lateinit var customViewContainer: FrameLayout
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private var lastKnownUrl: String? = null
    private var restoredFromProcessDeath = false
    private var requestedRoute by mutableStateOf<String?>(null)
    private val updateViewModel: UpdateViewModel by viewModels()

    // ========== Context menu Compose state ==========

    private val contextMenuActions = mutableStateOf<List<ContextMenuAction>>(emptyList())

    // ========== Injected ==========

    @Inject lateinit var adBlockRepository: AdBlockRepository
    @Inject lateinit var validLinkRepository: ValidLinkRepository
    @Inject lateinit var scriptRepository: ScriptRepository
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var refreshRateManager: RefreshRateManager

    // ========== Constants ==========

    companion object {
        private const val TAG = "MainActivity"

        // URLs
        private const val HOME_URL = "https://www.google.com/"

        private const val ROUTE_SPLASH = "splash"
        private const val ROUTE_BROWSER = "browser"
        private const val ROUTE_DOWNLOADS = "downloads"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_UPDATE = "update"

        private const val STATE_LAST_KNOWN_URL = "last_known_url"
        private const val STATE_WEB_VIEW = "web_view_state"
        private const val PAGE_TRANSITION_DURATION_MS = 300
        private const val BROWSER_REFRESH_TRIGGER_DP = 80f
        private const val BROWSER_REFRESH_MIN_VISIBLE_MS = 300L
        private val SNACKBAR_CLEARANCE = 116.dp

        /**
         * In-memory marker: survives activity recreation but is cleared when
         * the process is killed. Distinguishes a process-death restore (full
         * WebView state restore) from a plain recreation (reload the URL —
         * restoring the full WebView state after a recreation can leave a
         * black renderer surface).
         */
        private var processInstanceAlive = false
    }

    // ========== Lifecycle ==========

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lastKnownUrl = savedInstanceState?.getString(STATE_LAST_KNOWN_URL)
        restoredFromProcessDeath = savedInstanceState != null && !processInstanceAlive
        processInstanceAlive = true

        enableEdgeToEdge()

        // File uploads (<input type=file>): the picker result is routed back
        // to the chrome client, which answers the pending WebView callback.
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            chromeClient?.onFileChooserResult(result.data, result.resultCode)
        }

        // Create the fullscreen overlay container eagerly so the same
        // instance is shared between the Compose tree and NexaWebChromeClient.
        customViewContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        observeHighRefreshRate()
        observeAppLanguage()
        observeKeepScreenOn()
        observeNavigationEvents()

        setContent {
            NexaTheme {
                val navController = rememberNavController()
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route
                val appSnackbarHostState = remember { SnackbarHostState() }
                val composableScope = rememberCoroutineScope()

                LaunchedEffect(requestedRoute, currentRoute) {
                    val route = requestedRoute ?: return@LaunchedEffect
                    // Deep links arriving while splash is on the stack must not
                    // push here: the splash→browser transition pops everything
                    // above splash. The effect restarts once the browser route is
                    // active and delivers the pending route then.
                    if (currentRoute == null || currentRoute == ROUTE_SPLASH) return@LaunchedEffect
                    navController.navigate(route) { launchSingleTop = true }
                    requestedRoute = null
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_SPLASH,
                        enterTransition = {
                            slideInHorizontally(
                                animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = EaseOutQuart),
                                initialOffsetX = { it },
                            )
                        },
                        exitTransition = {
                            slideOutHorizontally(
                                animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = EaseOutQuart),
                                targetOffsetX = { -it / 3 },
                            )
                        },
                        popEnterTransition = {
                            slideInHorizontally(
                                animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = EaseOutQuart),
                                initialOffsetX = { -it / 3 },
                            )
                        },
                        popExitTransition = {
                            slideOutHorizontally(
                                animationSpec = tween(PAGE_TRANSITION_DURATION_MS, easing = EaseOutQuart),
                                targetOffsetX = { it },
                            )
                        },
                    ) {
                        composable(ROUTE_SPLASH) {
                            SplashRoute(
                                onReady = {
                                    navController.navigate(ROUTE_BROWSER) {
                                        popUpTo(ROUTE_SPLASH) { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                        composable(ROUTE_BROWSER) {
                            BrowserRoute(
                                savedInstanceState = savedInstanceState,
                                backEnabled = currentRoute == ROUTE_BROWSER,
                            )
                        }

                        composable(ROUTE_DOWNLOADS) {
                            DownloadsRoute(
                                onBackClick = { navController.popBackStack() },
                            )
                        }

                        composable(ROUTE_UPDATE) {
                            UpdateScreen(
                                viewModel = updateViewModel,
                                onBackClick = { navController.popBackStack() },
                            )
                        }

                        composable(ROUTE_SETTINGS) {
                            SettingsNavigation(
                                onRootBackClick = { navController.popBackStack() },
                                onUpdateClick = {
                                    navController.navigate(ROUTE_UPDATE) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }

                    }

                    SnackbarHost(
                        hostState = appSnackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    // Available update dialog — shown on browser screen
                    val showUpdateDialog by updateCheckViewModel.showUpdateDialog.collectAsStateWithLifecycle()
                    val updateVersion by updateCheckViewModel.version.collectAsStateWithLifecycle()

                    if (currentRoute == ROUTE_BROWSER && showUpdateDialog && updateVersion != null) {
                        AvailableUpdateDialog(
                            onDismiss = { updateCheckViewModel.dismissDialog() },
                            onConfirm = {
                                updateCheckViewModel.dismissDialog()
                                requestedRoute = ROUTE_UPDATE
                            },
                            setShowUpdateDialogOnLaunch = {
                                updateCheckViewModel.setShowUpdateDialogOnLaunch(it)
                            },
                            newVersion = updateVersion!!,
                        )
                    }
                }
            }
        }

        // Run on every onCreate: intents must survive recreation after
        // process death, not just the first launch.
        handleIntent(intent)
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun BrowserRoute(
        savedInstanceState: Bundle?,
        backEnabled: Boolean,
        modifier: Modifier = Modifier,
    ) {
                val state by browserViewModel.uiState.collectAsStateWithLifecycle()
                var isRefreshing by remember { mutableStateOf(false) }
                var pullDistancePx by remember { mutableFloatStateOf(0f) }
                var imageDialogDataUrl by remember { mutableStateOf<String?>(null) }
                val snackbarHostState = remember { SnackbarHostState() }
                val composableScope = rememberCoroutineScope()
                val downloadSnackbarTitle = stringResource(R.string.browser_download_snackbar_title)
                val downloadSnackbarAction = stringResource(R.string.details)
                val adaptiveInfo = rememberAdaptiveLayoutInfo()
                val useSideNavigation = adaptiveInfo.useSideNavigation
                val navigationState = state.toNavBarState()

                // Video download sniffer: appears on pages of supported video
                // platforms until dismissed for the current page load. A new
                // page load (navigation or refresh) changes the key, so a
                // dismissal never survives a refresh or navigation.
                val videoDownloadButtonEnabled by appPreferences.videoDownloadButton
                    .collectAsStateWithLifecycle(initialValue = null as Boolean?)
                var snifferDismissMode by rememberSaveable { mutableStateOf(false) }
                var dismissedSnifferKey by rememberSaveable { mutableStateOf<String?>(null) }
                val snifferUrl = state.topSearchBarText
                val snifferKey = "${state.pageLoadId}:$snifferUrl"
                val snifferVisible = videoDownloadButtonEnabled == true &&
                    state.toolbarVisible &&
                    snifferUrl.isNotBlank() &&
                    SharePlatformDetector.detect(snifferUrl) != SharePlatform.VIDEO &&
                    dismissedSnifferKey != snifferKey

                LaunchedEffect(state.pageLoadId, snifferUrl) {
                    snifferDismissMode = false
                }
                // NOT keyed on urlText: a redirect/SPA URL update must not
                // replace text the user is typing. Sync happens below while
                // the bar is hidden.
                var urlFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
                    mutableStateOf(
                        TextFieldValue(
                            text = navigationState.urlText,
                            selection = TextRange(0, navigationState.urlText.length),
                        )
                    )
                }
                // Re-sync the field with the page URL only while the bar is
                // not visible-for-editing; while it is open the typed text wins.
                LaunchedEffect(navigationState.urlText) {
                    if (!state.urlBarVisible) {
                        urlFieldValue = TextFieldValue(
                            text = navigationState.urlText,
                            selection = TextRange(0, navigationState.urlText.length),
                        )
                    }
                }

                fun showDownloadSnackbar() {
                    composableScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = downloadSnackbarTitle,
                            actionLabel = downloadSnackbarAction,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            launchDownloadsPage()
                        }
                    }
                }

                fun startBrowserRefresh() {
                    if (!isRefreshing) isRefreshing = true
                    pullDistancePx = 0f
                    refreshCurrentPage()
                }

                BackHandler(enabled = backEnabled) {
                    // Fullscreen video: back exits fullscreen first, never
                    // navigates history or finishes the activity.
                    if (chromeClient?.isFullscreen == true) {
                        chromeClient?.onHideCustomView()
                        return@BackHandler
                    }
                    var canGoBack = false
                    safeWebViewOperation { wv ->
                        canGoBack = wv.canGoBack()
                        if (canGoBack) wv.goBack()
                    }
                    if (!canGoBack) finish()
                }

                Box(modifier = modifier.fillMaxSize()) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        if (useSideNavigation && state.toolbarVisible) {
                            BrowserNavigationRail(
                                state = navigationState,
                                onRefreshClick = ::startBrowserRefresh,
                                onLinkClick = browserViewModel::toggleUrlContainer,
                                onHomeClick = ::navigateToHome,
                                onMenuBackClick = ::goBack,
                                onMenuForwardClick = ::goForward,
                                onMenuShareClick = ::shareCurrentPage,
                                onDownloadsClick = ::launchDownloadsPage,
                                onSettingsClick = ::launchSettingsPage,
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize()
                                .windowInsetsPadding(WindowInsets.statusBars)
                        ) {
                            if (useSideNavigation && state.toolbarVisible) {
                                if (navigationState.urlBarVisible) {
                                    BrowserUrlBar(
                                        value = urlFieldValue,
                                        onValueChange = { urlFieldValue = it },
                                        onCommit = browserViewModel::onUrlCommitted,
                                    )
                                }
                                BrowserNavigationProgress(progressPercent = navigationState.progressPercent)
                            }

                        // WebView consumes native touch events, so Compose nested scroll
                        // cannot drive PullToRefreshBox. The gesture is bridged from
                        // WebView while the official M3 Expressive LoadingIndicator is
                        // rendered as the refresh affordance.
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // WebView via AndroidView
                                WebViewContent(
                                    savedInstanceState = savedInstanceState,
                                    isRefreshing = isRefreshing,
                                    onPullDistanceChange = { pullDistancePx = it },
                                    onPullRefresh = ::startBrowserRefresh,
                                    onRefreshComplete = {
                                        composableScope.launch {
                                            delay(BROWSER_REFRESH_MIN_VISIBLE_MS)
                                            isRefreshing = false
                                            pullDistancePx = 0f
                                        }
                                    },
                                    onShowMessage = { message ->
                                        composableScope.launch { snackbarHostState.showSnackbar(message) }
                                    },
                                    onDownloadStarted = ::showDownloadSnackbar,
                                    onShowBase64Image = { imageDialogDataUrl = it },
                                )

                                // Fullscreen video overlay (above WebView)
                                AndroidView(
                                    factory = {
                                        (customViewContainer.parent as? ViewGroup)?.removeView(customViewContainer)
                                        customViewContainer
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )

                                BrowserRefreshIndicator(
                                    isRefreshing = isRefreshing,
                                    pullDistancePx = pullDistancePx,
                                )

                                // Simple error state over the page area.
                                if (state.pageLoadError) {
                                    BrowserLoadErrorOverlay(
                                        onRetry = ::startBrowserRefresh,
                                        onBackToHome = ::navigateToHome,
                                    )
                                }
                            }
                        }

                            // Compact windows retain the established bottom action bar.
                            if (!useSideNavigation && state.toolbarVisible) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    BrowserNavBar(
                                        state = navigationState,
                                        onRefreshClick = ::startBrowserRefresh,
                                        onLinkClick = browserViewModel::toggleUrlContainer,
                                        onHomeClick = ::navigateToHome,
                                        onMenuBackClick = ::goBack,
                                        onMenuForwardClick = ::goForward,
                                        onMenuShareClick = ::shareCurrentPage,
                                        onDownloadsClick = ::launchDownloadsPage,
                                        onSettingsClick = ::launchSettingsPage,
                                        urlFieldValue = urlFieldValue,
                                        onUrlFieldValueChange = { urlFieldValue = it },
                                        onUrlCommit = browserViewModel::onUrlCommitted,
                                    )
                                }
                            }
                        }
                    }

                    BrowserSnackbarHost(
                        hostState = snackbarHostState,
                        downloadMessage = downloadSnackbarTitle,
                        bottomOffset = if (!useSideNavigation && state.toolbarVisible) {
                            BrowserDownloadSnackbarDefaults.BottomOffsetWithNavBar
                        } else {
                            BrowserDownloadSnackbarDefaults.EdgeMargin
                        },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    AnimatedVisibility(
                        visible = snifferVisible,
                        enter = fadeIn() + slideInVertically { it },
                        exit = fadeOut() + slideOutVertically { it },
                        modifier = Modifier.align(Alignment.BottomEnd),
                    ) {
                        BrowserDownloadSnifferButton(
                            dismissMode = snifferDismissMode,
                            onClick = {
                                if (snifferDismissMode) {
                                    dismissedSnifferKey = snifferKey
                                    snifferDismissMode = false
                                } else {
                                    launchVideoDownloadSheet(snifferUrl)
                                }
                            },
                            onLongClick = { snifferDismissMode = !snifferDismissMode },
                            modifier = Modifier.padding(
                                end = 16.dp,
                                bottom = (if (!useSideNavigation && state.toolbarVisible) 76.dp else 16.dp) +
                                    if (snackbarHostState.currentSnackbarData != null) SNACKBAR_CLEARANCE else 0.dp,
                            ),
                        )
                    }

                    if (state.toolbarVisible) {
                        BrowserStatusBarScrim()
                    }

                    imageDialogDataUrl?.let { dataUrl ->
                        Base64ImageDialog(
                            dataUrl = dataUrl,
                            onDismiss = { imageDialogDataUrl = null },
                        )
                    }
                }
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun BoxScope.BrowserRefreshIndicator(
        isRefreshing: Boolean,
        pullDistancePx: Float,
    ) {
        val state = rememberPullToRefreshState()
        val density = LocalDensity.current
        val triggerPx = with(density) { BROWSER_REFRESH_TRIGGER_DP.dp.toPx() }

        LaunchedEffect(isRefreshing) {
            if (isRefreshing) {
                state.animateToThreshold()
            } else {
                state.animateToHidden()
            }
        }

        LaunchedEffect(isRefreshing, pullDistancePx, triggerPx) {
            if (!isRefreshing) {
                state.snapTo((pullDistancePx / triggerPx).coerceAtLeast(0f))
            }
        }

        PullToRefreshDefaults.LoadingIndicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier
                .align(Alignment.TopCenter),
        )
    }

    @Composable
    private fun BoxScope.BrowserLoadErrorOverlay(
        onRetry: () -> Unit,
        onBackToHome: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.page_load_error_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.page_load_error_message),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.retry))
                }
                TextButton(onClick = onBackToHome) {
                    Text(text = stringResource(R.string.page_load_error_home))
                }
            }
        }
    }

    @Composable
    private fun BoxScope.BrowserStatusBarScrim() {
        Spacer(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
        )
    }

    @Composable
    private fun SplashRoute(
        onReady: () -> Unit,
    ) {
        val state by splashViewModel.uiState.collectAsStateWithLifecycle()

        when (val s = state) {
            SplashUiState.Loading -> LoadingScreen()
            SplashUiState.NoInternet -> NoInternetScreen(
                onRetry = splashViewModel::onRetryClicked,
                onProceedAnyway = splashViewModel::onProceedAnywayClicked,
            )
            SplashUiState.Onboarding -> OnboardingScreen(
                onFinish = splashViewModel::onOnboardingFinished,
                vm = onboardingViewModel,
                settingsViewModel = settingsViewModel,
            )
            SplashUiState.Ready -> LaunchedEffect(s) { onReady() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        setIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanUpWebView()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.d(TAG, "MainActivity destroyed")
    }

    // ========== Compose WebView ==========

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebViewContent(
        savedInstanceState: Bundle?,
        isRefreshing: Boolean,
        onPullDistanceChange: (Float) -> Unit,
        onPullRefresh: () -> Unit,
        onRefreshComplete: () -> Unit,
        onShowMessage: (String) -> Unit,
        onDownloadStarted: () -> Unit,
        onShowBase64Image: (String) -> Unit,
    ) {
        val currentIsRefreshing by rememberUpdatedState(isRefreshing)
        val currentOnPullDistanceChange by rememberUpdatedState(onPullDistanceChange)
        val currentOnPullRefresh by rememberUpdatedState(onPullRefresh)
        val currentOnRefreshComplete by rememberUpdatedState(onRefreshComplete)
        val currentOnDownloadStarted by rememberUpdatedState(onDownloadStarted)

        // Pull-to-refresh bridge: mutable gesture state + latest callbacks in
        // one remembered holder that survives recomposition. The touch
        // listener is installed once in the factory and reads this holder, so
        // recomposition never resets an in-progress gesture.
        val pullBridge = remember { PullToRefreshTouchBridge() }

        AndroidView(
            factory = { ctx ->
                webView?.let { existingWebView ->
                    (existingWebView.parent as? ViewGroup)?.removeView(existingWebView)
                    // A new composition entry owns a new bridge holder; attach
                    // the retained WebView's listener to it (installed once,
                    // never mid-gesture).
                    existingWebView.installPullToRefreshTouchBridge(pullBridge)
                    return@AndroidView existingWebView
                }

                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    webView = this

                    // ── WebView configuration ─────────────────────────
                    WebViewConfigurator.configure(this)

                    installPullToRefreshTouchBridge(pullBridge)

                    // ── Long-press → Compose context menu via state ──
                    setOnLongClickListener {
                        val actions = ContextMenuHandler.getContextMenuActions(this)
                        if (actions.isNotEmpty()) {
                            contextMenuActions.value = actions
                            true
                        } else {
                            false
                        }
                    }

                    bindDownloadListener(
                        onDownloadStarted = { currentOnDownloadStarted() },
                    )

                    // ── WebViewClient ──────────────────────────────────
                    webViewClient = NexaWebViewClient(
                        appContext = ctx.applicationContext,
                        adBlockRepository = adBlockRepository,
                        validLinkRepository = validLinkRepository,
                        scriptRepository = scriptRepository,
                        onPageStartedEvent = { url, isImmersiveHost ->
                            browserViewModel.onPageStarted(url, isImmersiveHost)
                        },
                        onPageFinishedEvent = {
                            browserViewModel.onPageFinished(canGoBack(), canGoForward())
                        },
                        onNavigationConsumedEvent = {
                            browserViewModel.onNavigationConsumed(canGoBack(), canGoForward())
                        },
                        onUrlUpdatedEvent = { browserViewModel.onUrlUpdated(it) },
                        pageStartedCallback = { _, url -> updateLastKnownUrl(url) },
                        pageFinishedCallback = { _, url -> updateLastKnownUrl(url) },
                        urlUpdatedCallback = ::updateLastKnownUrl,
                        onPageLoadErrorEvent = { browserViewModel.onPageLoadError() },
                    )

                    // ── WebChromeClient ───────────────────────────────
                    chromeClient = NexaWebChromeClient(
                        activity = this@MainActivity,
                        webView = this,
                        customViewContainer = customViewContainer,
                        rootView = this,
                        onProgressChangedEvent = { browserViewModel.onProgressChanged(it) },
                        onFullscreenEnter = { browserViewModel.onFullscreenEnter() },
                        onFullscreenExit = { browserViewModel.onFullscreenExit() },
                        onProgressComplete = { currentOnRefreshComplete() },
                        fileChooserLauncher = ::launchFileChooser,
                    )
                    webChromeClient = chromeClient

                    // ── Initial load / state restore ──────────────────
                    // Process death: restore the full history stack. Plain
                    // recreation (e.g. locale change): reload the URL, since
                    // restoring the full WebView state after that can leave a
                    // black renderer surface.
                    val webViewState = savedInstanceState?.getBundle(STATE_WEB_VIEW)
                    val restoredHistory = if (restoredFromProcessDeath && webViewState != null) {
                        try {
                            restoreState(webViewState)
                        } catch (e: Exception) {
                            Log.w(TAG, "WebView state restore failed: ${e.message}", e)
                            null
                        }
                    } else {
                        null
                    }
                    if (restoredHistory != null) {
                        // The restored entry must pass the same safe-URL check.
                        if (isSafeLoadableUrl(url)) {
                            updateLastKnownUrl(url)
                        } else {
                            clearHistory()
                            updateLastKnownUrl(HOME_URL)
                            loadUrl(HOME_URL)
                        }
                    } else {
                        val initialUrl = lastKnownUrl ?: HOME_URL
                        if (isSafeLoadableUrl(initialUrl)) {
                            updateLastKnownUrl(initialUrl)
                            loadUrl(initialUrl)
                        } else {
                            // Never start blank: fall back to home.
                            updateLastKnownUrl(HOME_URL)
                            loadUrl(HOME_URL)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Only refresh callbacks here: the touch listener stays
                // installed once, so an in-progress pull gesture is never
                // reset by recomposition.
                pullBridge.isRefreshing = { currentIsRefreshing }
                pullBridge.onPullDistanceChange = { currentOnPullDistanceChange(it) }
                pullBridge.onPullRefresh = { currentOnPullRefresh() }
                view.bindDownloadListener(
                    onDownloadStarted = { currentOnDownloadStarted() },
                )
                (view.webChromeClient as? NexaWebChromeClient)?.let { client ->
                    client.updateCallbacks(
                        onProgressChangedEvent = { browserViewModel.onProgressChanged(it) },
                        onFullscreenEnter = { browserViewModel.onFullscreenEnter() },
                        onFullscreenExit = { browserViewModel.onFullscreenExit() },
                        onProgressComplete = { currentOnRefreshComplete() },
                    )
                    client.updateFileChooserLauncher(::launchFileChooser)
                }
            },
        )

        // ── Context menu as a pure Compose ModalBottomSheet ──────
        val currentActions by contextMenuActions
        if (currentActions.isNotEmpty()) {
            ContextMenuScreen(
                actions = currentActions,
                onAction = { action ->
                    contextMenuActions.value = emptyList()
                    val wv = webView ?: return@ContextMenuScreen
                    ContextMenuHandler.onActionSelected(
                        action = action,
                        webView = wv,
                        context = this@MainActivity,
                        onDownloadStarted = { currentOnDownloadStarted() },
                    ).let { result ->
                        when (result) {
                            ContextMenuResult.None -> Unit
                            is ContextMenuResult.Message -> onShowMessage(result.text)
                            is ContextMenuResult.Base64Image -> onShowBase64Image(result.dataUrl)
                        }
                    }
                },
                onDismiss = { contextMenuActions.value = emptyList() }
            )
        }

        // ── WebView pause/resume tied to lifecycle ────────────────
        // Note: pauseTimers()/resumeTimers() are intentionally NOT used —
        // they are process-global and would freeze every WebView in the
        // process, not just this one.
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            webView?.onResume()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
            webView?.onPause()
        }
    }

    private fun WebView.bindDownloadListener(onDownloadStarted: () -> Unit) {
        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            DownloadHandler.startDownload(
                context = context,
                url = url,
                mimeType = mimeType,
                contentDisposition = contentDisposition,
                userAgent = settings.userAgentString,
                currentPageUrl = this.url,
                onDownloadStarted = onDownloadStarted,
            )
        }
    }

    /**
     * Holder for the pull-to-refresh gesture: the mutable mid-gesture state
     * plus the latest callbacks. Lives in a `remember { }` outside the
     * AndroidView update lambda, so reinstalling/recomposing never resets a
     * gesture in progress and callbacks always point at the current
     * composition.
     */
    private class PullToRefreshTouchBridge {
        var isRefreshing: () -> Boolean = { false }
        var onPullDistanceChange: (Float) -> Unit = {}
        var onPullRefresh: () -> Unit = {}

        var downY = 0f
        var adjustedPullDistance = 0f
        var isPulling = false
        var webViewGestureCancelled = false
    }

    private fun WebView.installPullToRefreshTouchBridge(bridge: PullToRefreshTouchBridge) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val density = resources.displayMetrics.density
        val triggerDistance = BROWSER_REFRESH_TRIGGER_DP * density

        setOnTouchListener { _, event ->
            if (bridge.isRefreshing()) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    bridge.downY = event.rawY
                    bridge.adjustedPullDistance = 0f
                    bridge.isPulling = false
                    bridge.webViewGestureCancelled = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dragDistance = event.rawY - bridge.downY
                    val canPull = !canScrollVertically(-1) && dragDistance > touchSlop
                    if (bridge.isPulling || canPull) {
                        if (!bridge.webViewGestureCancelled) {
                            cancelActiveWebViewGesture(event)
                            bridge.webViewGestureCancelled = true
                        }
                        bridge.isPulling = true
                        bridge.adjustedPullDistance =
                            (dragDistance - touchSlop).coerceAtLeast(0f) * 0.5f
                        bridge.onPullDistanceChange(
                            calculatePullToRefreshOffset(
                                adjustedDistancePulled = bridge.adjustedPullDistance,
                                thresholdPx = triggerDistance,
                            )
                        )
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (bridge.isPulling) {
                        val shouldRefresh = bridge.adjustedPullDistance > triggerDistance
                        bridge.isPulling = false
                        bridge.adjustedPullDistance = 0f
                        bridge.onPullDistanceChange(0f)
                        if (shouldRefresh) bridge.onPullRefresh()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    private fun WebView.cancelActiveWebViewGesture(event: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        try {
            onTouchEvent(cancelEvent)
        } finally {
            cancelEvent.recycle()
        }
    }

    private fun calculatePullToRefreshOffset(adjustedDistancePulled: Float, thresholdPx: Float): Float {
        if (adjustedDistancePulled <= thresholdPx) return adjustedDistancePulled

        val progress = adjustedDistancePulled / thresholdPx
        val overshootPercent = progress - 1f
        val linearTension = overshootPercent.coerceIn(0f, 2f)
        val tensionPercent = linearTension - linearTension * linearTension / 4f
        return thresholdPx + thresholdPx * tensionPercent
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentUrl = webView?.url
        updateLastKnownUrl(currentUrl)
        lastKnownUrl?.let { outState.putString(STATE_LAST_KNOWN_URL, it) }
        // Full history stack for process-death restoration. Nested bundle so
        // WebView's own keys can't collide with Compose/NavHost state.
        webView?.let { wv ->
            try {
                val webViewState = Bundle()
                wv.saveState(webViewState)
                outState.putBundle(STATE_WEB_VIEW, webViewState)
            } catch (e: Exception) {
                Log.w(TAG, "WebView saveState failed: ${e.message}", e)
            }
        }
    }

    // ========== WebView helpers ==========

    private fun updateLastKnownUrl(url: String?) {
        val normalizedUrl = url
            ?.trim()
            ?.takeUnless { it.isBlank() || it.equals("about:blank", ignoreCase = true) }
            ?: return
        lastKnownUrl = normalizedUrl
    }

    /**
     * Safe-URL check for startup/restore loads. Only plain http(s) pages are
     * allowed — same policy as the URL bar — so a persisted javascript:,
     * data: or file: URL is never loaded automatically.
     */
    private fun isSafeLoadableUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (!URLUtil.isValidUrl(url)) return false
        val scheme = Uri.parse(url).scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https"
    }

    /** Launches the system picker for `<input type=file>` uploads. */
    private fun launchFileChooser(chooserIntent: Intent): Boolean {
        return try {
            fileChooserLauncher.launch(chooserIntent)
            true
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No app available to pick files", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch file chooser: ${e.message}", e)
            false
        }
    }

    /** Opens the normal download sheet for [url] via the share flow. */
    private fun launchVideoDownloadSheet(url: String) {
        startActivity(
            Intent(this, ShareActivity::class.java)
                .setAction(Intent.ACTION_SEND)
                .setTypeAndNormalize("text/plain")
                .putExtra(Intent.EXTRA_TEXT, url)
        )
    }

    private fun cleanUpWebView() {
        chromeClient?.cleanUpFullscreen()
        chromeClient = null
        try {
            webView?.apply {
                stopLoading()
                webViewClient = WebViewClient()
                webChromeClient = WebChromeClient()
                clearHistory()
                onPause()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            customViewContainer.removeAllViews()
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebView cleanup: ${e.message}", e)
        }
        webView = null
    }

    // ========== WebView Operations ==========

    /**
     * Safely executes WebView operations with proper lifecycle checks.
     */
    private fun safeWebViewOperation(operation: (WebView) -> Unit) {
        try {
            val wv = webView
            if (wv == null) {
                Log.w(TAG, "WebView is null")
                return
            }
            operation(wv)
        } catch (e: Exception) {
            Log.e(TAG, "WebView operation failed: ${e.message}", e)
        }
    }

    // ========== UI Actions ==========

    /**
     * Observes one-shot URL navigation events emitted by [BrowserViewModel.onUrlCommitted].
     */
    private fun observeNavigationEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.navigationEvent.collect { url ->
                    navigateToUrl(url)
                }
            }
        }
    }

    private fun navigateToUrl(url: String) {
        safeWebViewOperation { wv ->
            updateLastKnownUrl(url)
            wv.post { wv.loadUrl(url) }
        }
    }

    private fun navigateToHome() {
        val homeUrl = HOME_URL
        safeWebViewOperation { wv ->
            updateLastKnownUrl(homeUrl)
            wv.post { wv.loadUrl(homeUrl) }
        }
    }

    private fun refreshCurrentPage() {
        safeWebViewOperation { wv ->
            val currentUrl = wv.url
            val hasLoadedPage = !currentUrl.isNullOrBlank() &&
                !currentUrl.equals("about:blank", ignoreCase = true)
            if (hasLoadedPage) {
                // A page is already loaded: reload() preserves proper reload
                // semantics (cache validation, POST handling).
                wv.reload()
            } else {
                // Initial/about:blank state: fall back to an explicit load.
                val targetUrl = lastKnownUrl ?: HOME_URL
                if (URLUtil.isValidUrl(targetUrl)) {
                    updateLastKnownUrl(targetUrl)
                    wv.post { wv.loadUrl(targetUrl) }
                }
            }
        }
    }

    // ========== Public Methods ==========

    /**
     * Handles back navigation.
     */
    fun goBack() {
        onBackPressedDispatcher.onBackPressed()
    }

    /**
     * Navigates forward in WebView history.
     */
    fun goForward() {
        safeWebViewOperation { wv ->
            if (wv.canGoForward()) {
                wv.goForward()
            }
        }
    }

    /**
     * Shares the current page URL via system share sheet.
     */
    fun shareCurrentPage(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(shareIntent, null))
    }

    // ========== Window policies ==========

    private fun observeHighRefreshRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appPreferences.highRefreshRate
                    .distinctUntilChanged()
                    .collect { enabled -> refreshRateManager.apply(window, enabled) }
            }
        }
    }

    private fun observeAppLanguage() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Single locale observer for this activity. AppCompatDelegate
                // auto-restores stored locales (autoStoreLocales), so only
                // apply a tag that genuinely differs from the one already
                // applied — avoids a redundant setApplicationLocales and the
                // duplicate activity recreation it triggers.
                appPreferences.languageTag
                    .distinctUntilChanged()
                    .collect { tag ->
                        if (AppLanguageManager.currentLanguage() != AppLanguageManager.fromTag(tag)) {
                            AppLanguageManager.setLanguageTag(tag)
                        }
                    }
            }
        }
    }

    private fun observeKeepScreenOn() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                browserViewModel.uiState
                    .map { it.keepScreenOn }
                    .distinctUntilChanged()
                    .collect { keepScreenOn ->
                        if (keepScreenOn) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
            }
        }
    }

    // ========== Intent Handling ==========

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        if (isDownloadIntent(intent)) launchDownloadsPage()
    }

    private fun isDownloadIntent(intent: Intent): Boolean =
        intent.action == DownloadService.ACTION_OPEN_DOWNLOADS

    private fun launchDownloadsPage() {
        requestedRoute = ROUTE_DOWNLOADS
    }

    private fun launchSettingsPage() {
        requestedRoute = ROUTE_SETTINGS
    }
}
