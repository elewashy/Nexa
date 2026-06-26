package com.elewashy.nexa.feature.browser.presentation

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
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
import com.elewashy.nexa.feature.splash.presentation.SplashUiState
import com.elewashy.nexa.feature.splash.presentation.SplashViewModel
import com.elewashy.nexa.feature.splash.presentation.screen.LoadingScreen
import com.elewashy.nexa.feature.splash.presentation.screen.NoInternetScreen
import com.elewashy.nexa.ui.components.navigation.BrowserNavBar
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
 *  - onNewIntent: Route new intents (deep-link, notifications).
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
    private var lastKnownUrl: String? = null
    private var hasMainFrameLoadError = false
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
        private const val PAGE_TRANSITION_DURATION_MS = 300
        private const val BROWSER_REFRESH_TRIGGER_DP = 80f
        private const val BROWSER_REFRESH_DRAG_RESISTANCE = 0.5f
        private const val BROWSER_REFRESH_MAX_DRAG_MULTIPLIER = 1.6f
        private const val BROWSER_REFRESH_MIN_VISIBLE_MS = 300L
    }

    // ========== Lifecycle ==========

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        lastKnownUrl = savedInstanceState?.getString(STATE_LAST_KNOWN_URL)

        enableEdgeToEdge()

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

                LaunchedEffect(requestedRoute) {
                    requestedRoute?.let { route ->
                        navController.navigate(route) { launchSingleTop = true }
                        requestedRoute = null
                    }
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

        if (savedInstanceState == null) {
            handleIntent(intent)
        }
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

                fun startBrowserRefresh() {
                    if (!isRefreshing) isRefreshing = true
                    pullDistancePx = 0f
                    refreshCurrentPage()
                }

                BackHandler(enabled = backEnabled) {
                    var canGoBack = false
                    safeWebViewOperation { wv ->
                        canGoBack = wv.canGoBack()
                        if (canGoBack) wv.goBack()
                    }
                    if (!canGoBack) finish()
                }

                Box(modifier = modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.statusBars)
                    ) {
                        // WebView consumes native touch events, so Compose nested-scroll
                        // pull-to-refresh cannot observe browser pulls. Gesture bridging
                        // is installed on the WebView and the official M3 LoadingIndicator
                        // is rendered as a Compose overlay here.
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
                            }
                        }

                        // Navigation bar
                        if (state.toolbarVisible) {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                BrowserNavBar(
                                    state = state.toNavBarState(),
                                    onRefreshClick = ::startBrowserRefresh,
                                    onLinkClick = browserViewModel::toggleUrlContainer,
                                    onHomeClick = ::navigateToHome,
                                    onMenuBackClick = ::goBack,
                                    onMenuForwardClick = ::goForward,
                                    onMenuShareClick = ::shareCurrentPage,
                                    onDownloadsClick = ::launchDownloadsPage,
                                    onSettingsClick = ::launchSettingsPage,
                                    onUrlCommit = browserViewModel::onUrlCommitted,
                                )
                            }
                        }
                    }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )

                    BrowserStatusBarScrim()

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
        val density = LocalDensity.current
        val triggerPx = with(density) { BROWSER_REFRESH_TRIGGER_DP.dp.toPx() }
        val dragFraction = (pullDistancePx / triggerPx).coerceIn(0f, 1f)
        val visible = isRefreshing || dragFraction > 0f
        if (!visible) return

        val targetAlpha = if (isRefreshing) 1f else dragFraction
        val targetScale = if (isRefreshing) 1f else 0.65f + (0.35f * dragFraction)
        val alpha by animateFloatAsState(targetValue = targetAlpha, label = "browserRefreshAlpha")
        val scale by animateFloatAsState(targetValue = targetScale, label = "browserRefreshScale")
        val translationY = with(density) {
            if (isRefreshing) {
                16.dp.toPx()
            } else {
                (pullDistancePx * 0.45f).coerceAtMost(32.dp.toPx())
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .zIndex(1f)
                .padding(top = 8.dp)
                .size(48.dp)
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                    this.translationY = translationY
                },
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator(color = MaterialTheme.colorScheme.primary)
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
            SplashUiState.NoInternet -> NoInternetScreen(onRetry = splashViewModel::onRetryClicked)
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
        onShowBase64Image: (String) -> Unit,
    ) {
        val currentIsRefreshing by rememberUpdatedState(isRefreshing)
        val currentOnPullDistanceChange by rememberUpdatedState(onPullDistanceChange)
        val currentOnPullRefresh by rememberUpdatedState(onPullRefresh)

        AndroidView(
            factory = { ctx ->
                webView?.let { existingWebView ->
                    (existingWebView.parent as? ViewGroup)?.removeView(existingWebView)
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

                    installPullToRefreshTouchBridge(
                        isRefreshing = { currentIsRefreshing },
                        onPullDistanceChange = { currentOnPullDistanceChange(it) },
                        onPullRefresh = { currentOnPullRefresh() },
                    )

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

                    // ── Download listener ──────────────────────────────
                    setDownloadListener { url, _, contentDisposition, mimeType, _ ->
                        DownloadHandler.startDownload(
                            context = ctx,
                            url = url,
                            mimeType = mimeType,
                            contentDisposition = contentDisposition,
                            userAgent = settings.userAgentString,
                            currentPageUrl = this.url
                        )
                    }

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
                        mainFrameLoadErrorCallback = { hasMainFrameLoadError = it },
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
                        onProgressComplete = onRefreshComplete
                    )
                    webChromeClient = chromeClient

                    // ── Initial load / state restore ──────────────────
                    // Locale changes recreate the Activity. Restoring the
                    // full WebView state after that can leave a black renderer
                    // surface, so persist the URL explicitly and reload it.
                    val initialUrl = lastKnownUrl ?: HOME_URL
                    if (URLUtil.isValidUrl(initialUrl)) {
                        updateLastKnownUrl(initialUrl)
                        loadUrl(initialUrl)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
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
                        context = this@MainActivity
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
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            webView?.onResume()
            webView?.resumeTimers()
        }
        LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
            webView?.pauseTimers()
            webView?.onPause()
        }
    }

    private fun WebView.installPullToRefreshTouchBridge(
        isRefreshing: () -> Boolean,
        onPullDistanceChange: (Float) -> Unit,
        onPullRefresh: () -> Unit,
    ) {
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        val density = resources.displayMetrics.density
        val triggerDistance = BROWSER_REFRESH_TRIGGER_DP * density
        val maxPullDistance = triggerDistance * BROWSER_REFRESH_MAX_DRAG_MULTIPLIER
        var downY = 0f
        var pullDistance = 0f
        var isPulling = false

        setOnTouchListener { _, event ->
            if (isRefreshing()) return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    pullDistance = 0f
                    isPulling = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dragDistance = event.rawY - downY
                    val canPull = !canScrollVertically(-1) && dragDistance > touchSlop
                    if (isPulling || canPull) {
                        isPulling = true
                        pullDistance = ((dragDistance - touchSlop).coerceAtLeast(0f) * BROWSER_REFRESH_DRAG_RESISTANCE)
                            .coerceAtMost(maxPullDistance)
                        onPullDistanceChange(pullDistance)
                        true
                    } else {
                        false
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (isPulling) {
                        val shouldRefresh = pullDistance >= triggerDistance
                        isPulling = false
                        pullDistance = 0f
                        onPullDistanceChange(0f)
                        if (shouldRefresh) onPullRefresh()
                        true
                    } else {
                        false
                    }
                }

                else -> false
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val currentUrl = webView?.url
        updateLastKnownUrl(currentUrl)
        lastKnownUrl?.let { outState.putString(STATE_LAST_KNOWN_URL, it) }
    }

    // ========== WebView helpers ==========

    private fun updateLastKnownUrl(url: String?) {
        val normalizedUrl = url
            ?.trim()
            ?.takeUnless { it.isBlank() || it.equals("about:blank", ignoreCase = true) }
            ?: return
        lastKnownUrl = normalizedUrl
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
            val targetUrl = wv.url
                ?.takeUnless { it.isBlank() || it.equals("about:blank", ignoreCase = true) }
                ?: lastKnownUrl
                ?: HOME_URL
            if (URLUtil.isValidUrl(targetUrl)) {
                updateLastKnownUrl(targetUrl)
                wv.post { wv.loadUrl(targetUrl) }
            }
            hasMainFrameLoadError = false
        }
    }

    private fun navigateBack() {
        safeWebViewOperation { wv ->
            if (wv.canGoBack()) {
                wv.goBack()
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
                appPreferences.languageTag
                    .distinctUntilChanged()
                    .collect(AppLanguageManager::setLanguageTag)
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
        when {
            isDownloadIntent(intent) -> launchDownloadsPage()
            hasNotificationAction(intent) -> handleNotificationAction(intent)
        }
    }

    private fun isDownloadIntent(intent: Intent): Boolean =
        intent.action == DownloadService.ACTION_OPEN_DOWNLOADS

    private fun hasNotificationAction(intent: Intent): Boolean =
        intent.hasExtra("action")

    private fun launchDownloadsPage() {
        requestedRoute = ROUTE_DOWNLOADS
    }

    private fun launchSettingsPage() {
        requestedRoute = ROUTE_SETTINGS
    }

    private fun handleNotificationAction(intent: Intent) {
        val action = intent.getStringExtra("action")
        Log.d(TAG, "Notification action received: $action")
        navigateToHome()
    }
}
