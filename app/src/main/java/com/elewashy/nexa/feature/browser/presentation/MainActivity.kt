package com.elewashy.nexa.feature.browser.presentation

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.app.UiModeManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.webkit.URLUtil
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawn
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.core.common.BrowserUrls
import com.elewashy.nexa.core.display.RefreshRateManager
import com.elewashy.nexa.core.localization.AppLanguageManager
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.core.util.SafeUrls.isSafeLoadableUrl
import com.elewashy.nexa.feature.bookmarks.presentation.screen.BookmarksRoute
import com.elewashy.nexa.feature.adblock.data.AdBlockAssets
import com.elewashy.nexa.feature.adblock.data.AdBlockPolicyStore
import com.elewashy.nexa.feature.adblock.data.AdBlockStatsRecorder
import com.elewashy.nexa.feature.adblock.data.AdBlockRepository
import com.elewashy.nexa.feature.adblock.data.FilterUpdateScheduler
import com.elewashy.nexa.feature.adblock.presentation.SiteAdBlockMenuViewModel
import com.elewashy.nexa.feature.adblock.presentation.adBlockEntries
import com.elewashy.nexa.ui.components.navigation.BrowserSiteAdBlockState
import com.elewashy.nexa.feature.browser.presentation.webview.BlockedPageRenderer
import com.elewashy.nexa.feature.browser.presentation.webview.ContextMenuHandler
import com.elewashy.nexa.feature.browser.presentation.webview.DownloadHandler
import com.elewashy.nexa.feature.browser.presentation.webview.NexaWebChromeClient
import com.elewashy.nexa.feature.browser.presentation.webview.StartedDownload
import com.elewashy.nexa.feature.browser.presentation.webview.NexaWebViewClient
import com.elewashy.nexa.feature.browser.presentation.webview.PageMediaProbe
import com.elewashy.nexa.feature.browser.presentation.webview.PopupWindowHandler
import com.elewashy.nexa.feature.browser.presentation.webview.WebViewContentBlocker
import com.elewashy.nexa.feature.browser.presentation.webview.PrivateWebViewProfile
import com.elewashy.nexa.feature.browser.domain.model.BrowserNavigationBarPosition
import com.elewashy.nexa.feature.browser.presentation.webview.WebViewConfigurator
import com.elewashy.nexa.feature.browser.presentation.webview.WebViewSessionState
import com.elewashy.nexa.feature.browser.presentation.webview.ContextMenuResult
import com.elewashy.nexa.feature.browser.presentation.screen.ContextMenuAction
import com.elewashy.nexa.feature.browser.presentation.screen.Base64ImageDialog
import com.elewashy.nexa.feature.browser.data.favicon.FaviconRepository
import com.elewashy.nexa.feature.browser.presentation.screen.BrowserDownloadSnackbarDefaults
import com.elewashy.nexa.feature.browser.presentation.screen.BrowserSnackbarHost
import com.elewashy.nexa.feature.browser.presentation.screen.ContextMenuScreen
import com.elewashy.nexa.feature.browser.presentation.screen.TabSwitcherSheet
import com.elewashy.nexa.feature.downloads.data.DownloadRepository
import com.elewashy.nexa.feature.downloads.domain.model.DownloadItem
import com.elewashy.nexa.feature.downloads.domain.model.DownloadStatus
import com.elewashy.nexa.feature.downloads.presentation.downloadsEntries
import com.elewashy.nexa.feature.downloads.presentation.screen.openDownloadedFile
import com.elewashy.nexa.feature.downloads.presentation.service.DownloadService
import com.elewashy.nexa.feature.history.presentation.screen.HistoryRoute
import com.elewashy.nexa.feature.onboarding.presentation.OnboardingFlow
import com.elewashy.nexa.feature.update.presentation.UpdateScreen
import com.elewashy.nexa.feature.update.presentation.UpdateViewModel
import com.elewashy.nexa.feature.update.presentation.UpdateCheckViewModel
import com.elewashy.nexa.feature.update.presentation.components.AvailableUpdateDialog
import com.elewashy.nexa.feature.settings.presentation.settings.settingsEntries
import com.elewashy.nexa.feature.share.presentation.ShareActivity
import com.elewashy.nexa.feature.tabs.domain.model.BrowsingMode
import com.elewashy.nexa.feature.tabs.domain.model.TabItem
import com.elewashy.nexa.feature.tabs.domain.usecase.BackNavigation
import com.elewashy.nexa.ui.components.navigation.BrowserNavBar
import com.elewashy.nexa.ui.components.navigation.BrowserNavBarActions
import com.elewashy.nexa.ui.components.navigation.BrowserTopNavBar
import com.elewashy.nexa.ui.components.navigation.BrowserNavigationProgress
import com.elewashy.nexa.ui.components.navigation.BrowserNavigationRail
import com.elewashy.nexa.ui.components.navigation.BrowserOmniboxOverlay
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.navigation.AppNavDisplay
import com.elewashy.nexa.ui.permissions.DownloadPermissionRationale
import com.elewashy.nexa.ui.permissions.rememberDownloadPermissionGate
import com.elewashy.nexa.ui.navigation.AppRoute
import com.elewashy.nexa.ui.navigation.BrowserEntryMetadata
import com.elewashy.nexa.ui.navigation.rememberAppNavigator
import com.elewashy.nexa.ui.startup.StartupDestination
import com.elewashy.nexa.ui.startup.StartupHost
import com.elewashy.nexa.ui.startup.StartupViewModel
import com.elewashy.nexa.ui.theme.AppTheme
import com.elewashy.nexa.ui.theme.NexaTheme
import com.elewashy.nexa.ui.theme.toUiModeNightMode
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import javax.inject.Inject

/**
 * MainActivity — single Compose host for the app flow.
 *
 * Architecture:
 *  - Pure Compose host: the entire UI tree is rendered via `setContent`.
 *    WebViews (native Views) are embedded through `AndroidView`, and the
 *    fullscreen overlay container is a sibling `AndroidView`.
 *  - The browser is multi-tab: persistent tab rows live in Room (owned by
 *    [TabRepository] via [BrowserViewModel]); this Activity owns the RUNTIME
 *    side only — one WebView per materialized tab, held in [webViews], with
 *    exactly one attached to the Compose tree at a time. WebViews are created
 *    lazily (active tab first after restore, others on first switch) and are
 *    never stored in Room.
 *  - Session restoration: each tab's complete navigation state (back/forward
 *    list, page state) is captured with [WebViewSessionState] and persisted
 *    through the repository — on tab detach, before eviction, shortly after
 *    every committed navigation, and in onStop. A WebView created later (cold
 *    start, process death, eviction, recreation) restores that state, so
 *    Back/Forward work exactly as before the app was closed.
 *  - Navigation: one Navigation 3 back stack ([AppNavigator]) rooted at the browser and saved
 *    in the instance state; every other page is an entry with its own saved UI state and
 *    ViewModels. Back is owned by the navigation host (predictive back) except while the
 *    browser itself has history to go back through.
 *  - Startup: the system splash screen is the only launch screen. It is held just until the
 *    persisted onboarding state (and, for the browser, the tab workspace) is read. The first
 *    launch shows onboarding ([StartupViewModel] decides from DataStore, never from saved
 *    state); every later launch opens straight into the browser. Permissions are requested in
 *    context when a download starts.
 *
 * Lifecycle:
 *  - onCreate: Install the splash policy, window observers, and the Compose tree.
 *  - onNewIntent: Route new intents (deep-link, download page).
 *  - onStop: Capture every tab's navigation state and force all coalesced tab
 *    writes to disk, before the process becomes killable.
 *  - onDestroy: Tear down every WebView and clear KEEP_SCREEN_ON defensively.
 */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    // ========== Browser state ==========

    private val startupViewModel: StartupViewModel by viewModels()
    private val browserViewModel: BrowserViewModel by viewModels()
    private val updateCheckViewModel: UpdateCheckViewModel by viewModels()
    private val siteAdBlockMenuViewModel: SiteAdBlockMenuViewModel by viewModels()

    // ========== WebView state (managed outside Compose to survive recomposition) ==========

    /** Runtime WebViews keyed by tab id. Normal ids are persisted; private ids are ephemeral. */
    private val webViews = LinkedHashMap<Long, WebView>()
    private val tabThumbnails = mutableStateMapOf<Long, Bitmap>()
    /** Private favicons stay in process memory and never enter the shared favicon cache. */
    private val privateTabFavicons = mutableStateMapOf<Long, Bitmap>()
    private val privateWebViewProfile = PrivateWebViewProfile()

    /**
     * Content blocker of every materialized WebView, keyed like [webViews].
     * Snapshot state so the browser menu can follow the active tab's
     * blocked-on-page counter.
     */
    private val contentBlockers = mutableStateMapOf<Long, WebViewContentBlocker>()

    /** Tab id of the WebView currently attached to the Compose tree. */
    private var attachedTabId: Long? = null
    private var tabSwitcherVisible: Boolean = false

    /**
     * Whether the browser page is composed. Another page on top removes it from composition
     * while its WebView stays attached to the (detached) view tree; it must stay paused then,
     * including when the app returns to the foreground on that other page.
     */
    private var browserRouteShown: Boolean = false

    /**
     * Bumped to force the WebView `AndroidView` factory to run again for the
     * same active tab — used after a renderer-process death recreates the
     * tab's WebView.
     */
    private val webViewGeneration = mutableIntStateOf(0)

    private lateinit var customViewContainer: FrameLayout
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>

    /** The chrome client whose file chooser is awaiting a picker result. */
    private var pendingFileChooserClient: NexaWebChromeClient? = null

    /** Debounced post-navigation state captures, keyed by tab id. Main thread only. */
    private val sessionCaptureJobs = HashMap<Long, Job>()

    /**
     * Destination requested from outside the composition (a notification intent). Consumed by
     * the navigation host on its next frame; the back stack itself is saved state.
     */
    private var requestedRoute by mutableStateOf<AppRoute?>(null)
    private val updateViewModel: UpdateViewModel by viewModels()

    // ========== Context menu Compose state ==========

    private val contextMenuActions = mutableStateOf<List<ContextMenuAction>>(emptyList())

    // ========== Injected ==========

    @Inject lateinit var adBlockRepository: AdBlockRepository
    @Inject lateinit var adBlockAssets: AdBlockAssets
    @Inject lateinit var adBlockPolicyStore: AdBlockPolicyStore
    @Inject lateinit var adBlockStatsRecorder: AdBlockStatsRecorder
    @Inject lateinit var pageMediaProbe: PageMediaProbe
    @Inject lateinit var filterUpdateScheduler: FilterUpdateScheduler
    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var refreshRateManager: RefreshRateManager
    // Lazy: neither is needed to draw the first frame. The download engine is built on the
    // first download snackbar, the favicon cache on the first page icon.
    @Inject lateinit var downloadRepository: dagger.Lazy<DownloadRepository>
    @Inject lateinit var faviconRepository: dagger.Lazy<FaviconRepository>

    // ========== Constants ==========

    companion object {
        private const val TAG = "MainActivity"

        /**
         * Upper bound for holding the system splash while the onboarding state (DataStore) and
         * the tab workspace (Room) are read — normally a few milliseconds each. Past it the app
         * draws and the rest appears as soon as the reads complete.
         */
        private const val SPLASH_MAX_HOLD_MS = 500L
        private const val SPLASH_EXIT_DURATION_MS = 180L

        /** Count shown for tabs whose WebView is not materialized yet. */
        private val NO_BLOCKED_REQUESTS: StateFlow<Int> = MutableStateFlow(0)

        /** Coalesces redirect chains and History API bursts into one state capture. */
        private const val SESSION_CAPTURE_DEBOUNCE_MS = 1_500L
        private const val BROWSER_REFRESH_TRIGGER_DP = 80f
        private const val BROWSER_REFRESH_MIN_VISIBLE_MS = 300L
        private const val DOWNLOAD_MATCH_TOLERANCE_MS = 2_000L
        private const val THUMBNAIL_MAX_WIDTH_PX = 240
        private const val THUMBNAIL_MAX_HEIGHT_PX = 360
        private const val PRIVATE_FAVICON_MAX_SIZE_PX = 64
        private const val MAX_MATERIALIZED_WEBVIEWS = 4
        private val SNACKBAR_CLEARANCE = 116.dp

        /** No-op callback sink used only during deterministic WebView teardown. */
        @SuppressLint("MissingOnRenderProcessGone") // Implemented below; AndroidX lint misses this Kotlin object.
        private val DESTROYING_WEB_VIEW_CLIENT = object : WebViewClient() {
            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean = true
        }
    }

    // ========== Lifecycle ==========

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        installSplashPolicy(splashScreen)
        // Once per process; filter lists refresh in the background on the application scope.
        filterUpdateScheduler.start()

        enableEdgeToEdge()

        // File uploads (<input type=file>): the picker result is routed back
        // to the chrome client that asked for it.
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            pendingFileChooserClient?.onFileChooserResult(result.data, result.resultCode)
            pendingFileChooserClient = null
        }

        // Create the fullscreen overlay container eagerly so the same
        // instance is shared between the Compose tree and every chrome client.
        customViewContainer = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        observeHighRefreshRate()
        observeAppLanguage()
        observeThemeModeForSystemWindows()
        observeKeepScreenOn()
        observeNavigationEvents()
        observeTabListForWebViewCleanup()

        setContent {
            NexaTheme {
                val destination by startupViewModel.destination.collectAsStateWithLifecycle()
                StartupHost(
                    destination = destination,
                    modifier = Modifier.fillMaxSize(),
                    onboarding = {
                        // Its first frame is complete: nothing loads after it (TTFD).
                        ReportDrawn()
                        OnboardingFlow()
                    },
                    browser = { BrowserApp() },
                )
            }
        }

        // A recreated Activity (configuration change, process death) restores its back stack
        // from saved state, which already reflects the launch intent; only a fresh launch
        // routes it. Later intents arrive through onNewIntent.
        if (savedInstanceState == null) handleIntent(intent)
    }

    /** The browser and every page above it, in the app's single back stack. */
    @Composable
    private fun BrowserApp() {
        // Fully drawn (TTFD) once the tab workspace is restored and the active tab can attach.
        val workspaceRestored by remember {
            browserViewModel.workspace.map { it.isRestored }.distinctUntilChanged()
        }.collectAsStateWithLifecycle(initialValue = false)
        ReportDrawnWhen { workspaceRestored }

        val navigator = rememberAppNavigator()
        val currentRoute = navigator.currentRoute

        LaunchedEffect(requestedRoute) {
            val route = requestedRoute ?: return@LaunchedEffect
            navigator.navigate(route)
            requestedRoute = null
        }

        AppNavDisplay(
            navigator = navigator,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<AppRoute.Browser>(metadata = BrowserEntryMetadata) {
                BrowserRoute(
                    isTopDestination = currentRoute == AppRoute.Browser,
                    onNavigate = navigator::navigate,
                )
            }

            entry<AppRoute.History> {
                HistoryRoute(
                    onBackClick = navigator::back,
                    onOpenUrl = { url ->
                        loadUrlInActiveTab(url)
                        navigator.back()
                    },
                )
            }

            entry<AppRoute.Bookmarks> {
                BookmarksRoute(
                    onBackClick = navigator::back,
                    onOpenUrl = { url ->
                        loadUrlInActiveTab(url)
                        navigator.back()
                    },
                )
            }

            entry<AppRoute.Update> {
                UpdateScreen(
                    viewModel = updateViewModel,
                    onBackClick = navigator::back,
                )
            }

            downloadsEntries(navigator)
            adBlockEntries(navigator)
            settingsEntries(
                navigator = navigator,
                onUpdateClick = { navigator.navigate(AppRoute.Update) },
            )
        }

        // Available update dialog — shown over the browser only.
        if (currentRoute == AppRoute.Browser) {
            val showUpdateDialog by updateCheckViewModel.showUpdateDialog.collectAsStateWithLifecycle()
            val updateVersion by updateCheckViewModel.version.collectAsStateWithLifecycle()
            val availableVersion = updateVersion
            if (showUpdateDialog && availableVersion != null) {
                AvailableUpdateDialog(
                    onDismiss = updateCheckViewModel::dismissDialog,
                    onConfirm = {
                        updateCheckViewModel.dismissDialog()
                        navigator.navigate(AppRoute.Update)
                    },
                    setShowUpdateDialogOnLaunch = updateCheckViewModel::setShowUpdateDialogOnLaunch,
                    newVersion = availableVersion,
                )
            }
        }
    }

    /**
     * System splash screen policy (androidx core-splashscreen; the platform API on Android 12+).
     *
     * The splash is held only for what the first meaningful frame needs, and never longer than
     * [SPLASH_MAX_HOLD_MS]:
     *  - the persisted onboarding state (one small DataStore read, started in parallel by
     *    [StartupViewModel]);
     *  - for the browser, the tab workspace (one Room read), so the first frame is the restored
     *    browser rather than an empty shell. Onboarding needs nothing else and shows at once.
     * Nothing else gates startup: no network check, no update check, no artificial delay. The
     * exit is a short fade over the already drawn destination.
     */
    private fun installSplashPolicy(splashScreen: SplashScreen) {
        val holdUntil = SystemClock.uptimeMillis() + SPLASH_MAX_HOLD_MS
        // Resolved now, not at the first pre-draw check, so both reads start right away.
        val destination = startupViewModel.destination
        val workspace = browserViewModel.workspace
        splashScreen.setKeepOnScreenCondition {
            SystemClock.uptimeMillis() < holdUntil && when (destination.value) {
                StartupDestination.Loading -> true
                StartupDestination.Onboarding -> false
                StartupDestination.Browser -> !workspace.value.isRestored
            }
        }
        splashScreen.setOnExitAnimationListener { provider ->
            // Only the container is animated: the icon view is absent for icon-less splash
            // styles on some Android versions.
            provider.view.animate()
                .alpha(0f)
                .setDuration(SPLASH_EXIT_DURATION_MS)
                .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(provider::remove)
                .start()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun BrowserRoute(
        isTopDestination: Boolean,
        onNavigate: (AppRoute) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        DisposableEffect(Unit) {
            browserRouteShown = true
            attachedWebView()?.onResume()
            onDispose {
                browserRouteShown = false
                // The navigation host keeps the Activity resumed while another destination is on
                // screen. Pause the attached renderer at the route boundary so hidden pages cannot
                // continue media, JavaScript, rendering, or network-driven UI work.
                attachedWebView()?.onPause()
                contextMenuActions.value = emptyList()
            }
        }
        val state by browserViewModel.uiState.collectAsStateWithLifecycle()
        val omniboxState by browserViewModel.omniboxState.collectAsStateWithLifecycle()
        val workspace by browserViewModel.workspace.collectAsStateWithLifecycle()
        val bookmarkedUrls by browserViewModel.bookmarkedUrls.collectAsStateWithLifecycle()
        val tabs = workspace.tabs
        val activeTabId = workspace.activeTabId
        val tabsRestored = workspace.isRestored
        var isRefreshing by remember { mutableStateOf(false) }
        var pullDistancePx by remember { mutableFloatStateOf(0f) }
        var imageDialogDataUrl by remember { mutableStateOf<String?>(null) }
        var showTabSwitcher by rememberSaveable { mutableStateOf(false) }
        // Single writer for the Activity-level flag so onResume never revives a covered
        // renderer, including after configuration changes restore the overview.
        LaunchedEffect(showTabSwitcher) {
            tabSwitcherVisible = showTabSwitcher
            if (showTabSwitcher) attachedWebView()?.onPause()
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val composableScope = rememberCoroutineScope()
        val downloadPermissionGate = rememberDownloadPermissionGate()
        val downloadSnackbarTitle = stringResource(R.string.browser_download_snackbar_title)
        val downloadSnackbarAction = stringResource(R.string.details)
        val downloadCompleteSnackbarTitle =
            stringResource(R.string.browser_download_complete_snackbar_title)
        val openDownloadAction = stringResource(R.string.open_file)
        val bookmarkAddedMessage = stringResource(R.string.bookmark_added)
        val bookmarkRemovedMessage = stringResource(R.string.bookmark_removed)
        val tabLimitMessage = stringResource(R.string.tab_limit_reached)
        val urlCopiedMessage = stringResource(R.string.url_copied)
        val adaptiveInfo = rememberAdaptiveLayoutInfo()
        val useSideNavigation = adaptiveInfo.useSideNavigation
        val navigationBarPositionValue by appPreferences.browserNavigationBarPosition
            .collectAsStateWithLifecycle(
                initialValue = BrowserNavigationBarPosition.Bottom.storedValue
            )
        val navigationBarPosition = BrowserNavigationBarPosition.fromStoredValue(
            navigationBarPositionValue
        )
        // Ad blocking for the active page (More options menu). The live counter is
        // passed as State and only read by the open menu, so blocked requests never
        // recompose the browser screen.
        val activePageUrl = state.topSearchBarText.ifBlank { null }
        LaunchedEffect(activePageUrl) { siteAdBlockMenuViewModel.onPageUrlChanged(activePageUrl) }
        val siteAdBlock by siteAdBlockMenuViewModel.state.collectAsStateWithLifecycle()
        val blockedOnPage = (activeTabId?.let { contentBlockers[it]?.blockedOnPage } ?: NO_BLOCKED_REQUESTS)
            .collectAsStateWithLifecycle()
        val navigationState = state.toNavBarState(
            addressPreviewVisible = omniboxState.mode == BrowserOmniboxMode.Preview,
            workspace = workspace,
        ).copy(
            siteAdBlock = siteAdBlock?.let {
                BrowserSiteAdBlockState(
                    site = it.site,
                    globalEnabled = it.globalEnabled,
                    siteEnabled = it.siteEnabled,
                    blockedOnPage = blockedOnPage,
                )
            },
        )
        LaunchedEffect(Unit) {
            // The change is already visible to the engine: reload so the page reflects it.
            siteAdBlockMenuViewModel.changes.collect { change ->
                refreshCurrentPage()
                snackbarHostState.showSnackbar(
                    getString(
                        if (change.enabled) R.string.adblock_site_enabled_message else R.string.adblock_site_disabled_message,
                        change.site,
                    )
                )
            }
        }

        // One-shot feedback from the ViewModel (bookmark toggle, tab cap).
        LaunchedEffect(Unit) {
            browserViewModel.bookmarkToggleEvent.collect { added ->
                snackbarHostState.showSnackbar(
                    if (added) bookmarkAddedMessage else bookmarkRemovedMessage
                )
            }
        }
        LaunchedEffect(Unit) {
            browserViewModel.tabLimitEvent.collect {
                snackbarHostState.showSnackbar(tabLimitMessage)
            }
        }

        // Media download sniffer: appears only while the active page
        // offers downloadable media (see ResolveDownloadableMediaUseCase),
        // until dismissed for the current page load. A new page load
        // (navigation or refresh) changes the key, so a dismissal never
        // survives a refresh or navigation. downloadableMediaUrl is already
        // null while the button is disabled in settings.
        val downloadableMediaUrl by browserViewModel.downloadableMediaUrl.collectAsStateWithLifecycle()
        var snifferDismissMode by rememberSaveable { mutableStateOf(false) }
        var dismissedSnifferKey by rememberSaveable { mutableStateOf<String?>(null) }
        val snifferKey = "${state.pageLoadId}:${state.topSearchBarText}"
        val snifferVisible = state.toolbarVisible &&
            !omniboxState.mode.isOverlayVisible &&
            downloadableMediaUrl != null &&
            dismissedSnifferKey != snifferKey

        LaunchedEffect(state.pageLoadId, state.topSearchBarText) {
            snifferDismissMode = false
        }
        fun showDownloadSnackbar(started: StartedDownload) {
            composableScope.launch {
                coroutineScope {
                    val initialSnackbar = async {
                        snackbarHostState.showSnackbar(
                            message = downloadSnackbarTitle,
                            actionLabel = downloadSnackbarAction,
                            duration = SnackbarDuration.Long,
                        )
                    }
                    val terminalDownload = async {
                        downloadRepository.get().downloads
                            .map { downloads ->
                                downloads.firstOrNull { item ->
                                    item.url == started.url &&
                                        item.createdAt >= started.startedAt - DOWNLOAD_MATCH_TOLERANCE_MS &&
                                        (item.status == DownloadStatus.COMPLETED ||
                                            item.status == DownloadStatus.FAILED ||
                                            item.status == DownloadStatus.CANCELLED)
                                }
                            }
                            .filterNotNull()
                            .first()
                    }

                    val completedItem = select<DownloadItem?> {
                        initialSnackbar.onAwait { result ->
                            if (result == SnackbarResult.ActionPerformed) onNavigate(AppRoute.Downloads())
                            // A timeout and a manual dismissal are both reported as Dismissed by
                            // Material. Neither should stop lifecycle observation for the download.
                            terminalDownload.await().takeIf { it.status == DownloadStatus.COMPLETED }
                        }
                        terminalDownload.onAwait { terminalItem ->
                            // Cancelling showSnackbar removes this request whether it is visible or
                            // queued. Replace it with an immediately actionable completion message.
                            initialSnackbar.cancelAndJoin()
                            terminalItem.takeIf { it.status == DownloadStatus.COMPLETED }
                        }
                    }
                    if (completedItem != null) {
                        val result = snackbarHostState.showSnackbar(
                            message = downloadCompleteSnackbarTitle,
                            actionLabel = openDownloadAction,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            openDownloadedFile(completedItem) { message, duration ->
                                composableScope.launch {
                                    snackbarHostState.showSnackbar(message, duration = duration)
                                }
                            }
                        }
                    }
                }
            }
        }

        fun createTabInCurrentMode() {
            if (workspace.activeTab?.isPrivate == true) {
                browserViewModel.newPrivateTab()
            } else {
                browserViewModel.newTab()
            }
        }

        fun openTabSwitcher() {
            captureAttachedTabThumbnail()
            // Closing from the overview retains this state for undo; capture the latest first.
            attachedTabId?.let { id -> webViews[id]?.let { persistSessionState(id, it) } }
            showTabSwitcher = true
        }

        // The overview replaces the WebView slot, so every dismissal re-runs attachTab for
        // the (possibly new) active tab, which resumes exactly that renderer.
        fun dismissTabSwitcher() {
            showTabSwitcher = false
        }

        fun startBrowserRefresh() {
            if (!isRefreshing) isRefreshing = true
            pullDistancePx = 0f
            refreshCurrentPage()
        }

        val navBarActions = BrowserNavBarActions(
            onRefresh = ::startBrowserRefresh,
            onOpenSearch = browserViewModel::openOmniboxSearch,
            onHome = ::navigateToHome,
            onTabs = ::openTabSwitcher,
            onBack = ::goBack,
            onForward = ::goForward,
            onShare = ::shareCurrentPage,
            onNewTab = ::createTabInCurrentMode,
            onBookmarks = { onNavigate(AppRoute.Bookmarks) },
            onToggleBookmark = { browserViewModel.toggleBookmark() },
            onDownloads = { onNavigate(AppRoute.Downloads()) },
            onHistory = { onNavigate(AppRoute.History) },
            onSettings = { onNavigate(AppRoute.Settings()) },
            onAdBlocker = { onNavigate(AppRoute.AdBlock()) },
            onSetSiteAdBlocking = siteAdBlockMenuViewModel::setSiteEnabled,
        )

        // Back is consumed here only while the browser has somewhere to go back to: fullscreen
        // video, the page's own history, or the opener of a popup tab. Otherwise the callback is
        // disabled and the system handles Back, which plays the predictive back-to-home
        // animation (Android 13+) and keeps this task and its state alive (Android 12+).
        val browserConsumesBack = isTopDestination && (
            !state.toolbarVisible ||
                state.backButtonEnabled ||
                workspace.activeTab?.let { workspace.openerOf(it.id) } != null
            )
        BackHandler(enabled = browserConsumesBack) {
            // Fullscreen video: back exits fullscreen first, never
            // navigates history or finishes the activity.
            val activeChromeClient =
                attachedWebView()?.webChromeClient as? NexaWebChromeClient
            if (activeChromeClient?.isFullscreen == true) {
                activeChromeClient.onHideCustomView()
                return@BackHandler
            }
            val canGoBackInPage = attachedWebView()
                ?.let { runCatching { it.canGoBack() }.getOrDefault(false) } == true
            when (browserViewModel.onBackPressed(canGoBackInPage)) {
                BackNavigation.PageHistory -> safeWebViewOperation { wv ->
                    // Stepping through the history list revisits an
                    // already-recorded page — not a fresh visit.
                    (wv.webViewClient as? NexaWebViewClient)
                        ?.suppressNextVisitCommit = true
                    wv.goBack()
                }
                // The popup tab is closing; its opener becomes active and is
                // re-attached with its retained (or restored) exact state.
                is BackNavigation.ReturnToOpener -> Unit
                // The enabled state was a frame behind the WebView: leave like the system
                // would, keeping the task (and every tab's live state) for an instant return.
                BackNavigation.Exit -> moveTaskToBack(true)
            }
        }

        Box(modifier = modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxSize()) {
                if (useSideNavigation && state.toolbarVisible) {
                    BrowserNavigationRail(
                        state = navigationState,
                        actions = navBarActions,
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    if (useSideNavigation && state.toolbarVisible) {
                        BrowserNavigationProgress(progressPercent = navigationState.progressPercent)
                    }
                    if (
                        !useSideNavigation &&
                        state.toolbarVisible &&
                        navigationBarPosition == BrowserNavigationBarPosition.Top
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            BrowserTopNavBar(
                                state = navigationState,
                                pageFavicon = activeTabId?.let(privateTabFavicons::get),
                                actions = navBarActions,
                                modifier = Modifier.widthIn(max = adaptiveInfo.contentMaxWidth),
                            )
                        }
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
                            // Active tab's WebView via AndroidView. The key swaps
                            // the composition entry when the active tab changes;
                            // retained WebViews live in the Activity's map.
                            if (tabsRestored && activeTabId != null && !showTabSwitcher) {
                                key(activeTabId, webViewGeneration.intValue) {
                                    // A retained WebView already holds its live history. A WebView
                                    // about to be created first reads the tab's stored navigation
                                    // state off the main thread (one primary-key lookup).
                                    val restoreSnapshot by produceState(
                                        initialValue = if (webViews.containsKey(activeTabId)) {
                                            SessionSnapshot.Retained
                                        } else {
                                            null
                                        },
                                    ) {
                                        if (value == null) {
                                            value = SessionSnapshot(
                                                browserViewModel.tabSessionState(activeTabId)
                                            )
                                        }
                                    }
                                    val snapshot = restoreSnapshot
                                    if (snapshot != null) {
                                        var tabEntered by remember { mutableStateOf(false) }
                                        LaunchedEffect(Unit) { tabEntered = true }
                                        val tabContentAlpha by animateFloatAsState(
                                            targetValue = if (tabEntered) 1f else 0f,
                                            animationSpec = tween(180),
                                            label = "activeTabContentAlpha",
                                        )
                                        WebViewContent(
                                            tabId = activeTabId,
                                            sessionState = snapshot.state,
                                            isRefreshing = isRefreshing,
                                            modifier = Modifier.graphicsLayer {
                                                alpha = tabContentAlpha
                                                translationY = (1f - tabContentAlpha) * 8.dp.toPx()
                                            },
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
                                            launchDownload = downloadPermissionGate::launch,
                                            onShowBase64Image = { imageDialogDataUrl = it },
                                        )
                                    }
                                }
                            }

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
                    if (
                        !useSideNavigation &&
                        state.toolbarVisible &&
                        navigationBarPosition == BrowserNavigationBarPosition.Bottom
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            BrowserNavBar(
                                state = navigationState,
                                pageFavicon = activeTabId?.let(privateTabFavicons::get),
                                actions = navBarActions,
                                onToggleAddressPreview = browserViewModel::toggleAddressPreview,
                                onDismissAddressPreview = browserViewModel::dismissOmnibox,
                            )
                        }
                    }
                }
            }

            BrowserOmniboxOverlay(
                state = omniboxState,
                currentUrl = state.topSearchBarText,
                currentTitle = state.pageTitle,
                isPrivate = navigationState.isPrivate,
                searchEngine = browserViewModel.selectedSearchEngine.collectAsStateWithLifecycle().value,
                onQueryChange = browserViewModel::updateOmniboxQuery,
                onCommit = browserViewModel::onUrlCommitted,
                onEditCurrentUrl = browserViewModel::openOmniboxUrlEditor,
                onShareCurrentUrl = ::shareCurrentPage,
                onCopyCurrentUrl = { url ->
                    copyUrl(url)
                    composableScope.launch { snackbarHostState.showSnackbar(urlCopiedMessage) }
                },
                onDismiss = browserViewModel::dismissOmnibox,
                modifier = Modifier.fillMaxSize(),
            )

            BrowserSnackbarHost(
                hostState = snackbarHostState,
                bottomOffset = if (
                    !useSideNavigation &&
                    state.toolbarVisible &&
                    navigationBarPosition == BrowserNavigationBarPosition.Bottom
                ) {
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
                            downloadableMediaUrl?.let(::launchVideoDownloadSheet)
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

            DownloadPermissionRationale(downloadPermissionGate)

            imageDialogDataUrl?.let { dataUrl ->
                Base64ImageDialog(
                    dataUrl = dataUrl,
                    onDismiss = { imageDialogDataUrl = null },
                )
            }

            AnimatedVisibility(
                visible = showTabSwitcher,
                enter = fadeIn() + scaleIn(initialScale = 0.98f),
                exit = fadeOut() + scaleOut(targetScale = 0.98f),
            ) {
                TabSwitcherSheet(
                    tabs = tabs,
                    activeTabId = activeTabId,
                    bookmarkedUrls = bookmarkedUrls,
                    privateBrowsingAvailable = privateWebViewProfile.isSupported,
                    thumbnailFor = tabThumbnails::get,
                    runtimeFaviconFor = privateTabFavicons::get,
                    onTabClick = { tabId ->
                        composableScope.launch {
                            // Keep the overview covering the old renderer until the active
                            // pointer has changed; attachTab then resumes only the new tab.
                            browserViewModel.switchTab(tabId).join()
                            dismissTabSwitcher()
                        }
                    },
                    onCloseTab = browserViewModel::closeTab,
                    onCloseSelectedTabs = browserViewModel::closeTabs,
                    onSetTabPinned = browserViewModel::setTabPinned,
                    onSetTabsPinned = browserViewModel::setTabsPinned,
                    onReorderTab = browserViewModel::reorderTab,
                    onBookmarkTab = { tab -> browserViewModel.toggleBookmark(tab.url, tab.title) },
                    onSetTabsBookmarked = browserViewModel::setTabsBookmarked,
                    onShareTab = { tab -> shareCurrentPage(tab.url) },
                    onCloseTabs = browserViewModel::closeTabs,
                    onNewTab = {
                        composableScope.launch {
                            browserViewModel.newTab().join()
                            dismissTabSwitcher()
                        }
                    },
                    onNewPrivateTab = {
                        composableScope.launch {
                            browserViewModel.newPrivateTab().join()
                            dismissTabSwitcher()
                        }
                    },
                    onReopenTab = browserViewModel::reopenTab,
                    onDismiss = ::dismissTabSwitcher,
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        // Only a visible attached tab is interactive. Another page on top, or the tab overview,
        // keeps the covered renderer paused when returning from the background.
        if (browserRouteShown && !tabSwitcherVisible) attachedWebView()?.onResume()
    }

    override fun onPause() {
        super.onPause()
        // Every materialized WebView, not just the attached one: backgrounded
        // tabs must not keep running JS timers and rendering.
        webViews.values.forEach { it.onPause() }
    }

    override fun onStop() {
        super.onStop()
        // The process is killable from here on (swipe from Recents, low memory): capture every
        // tab's navigation state and force all coalesced writes so a kill loses nothing.
        persistAllSessionStates()
        browserViewModel.flushTabs()
        adBlockStatsRecorder.flushNow()
    }

    override fun onDestroy() {
        // Background pages can still navigate between onStop and here.
        persistAllSessionStates()
        browserViewModel.flushTabs()
        sessionCaptureJobs.values.forEach(Job::cancel)
        sessionCaptureJobs.clear()
        cleanUpAllWebViews()
        if (!isChangingConfigurations) {
            browserViewModel.closeTabs(BrowsingMode.Private)
            privateWebViewProfile.clearSession()
        }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
        Log.d(TAG, "MainActivity destroyed")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level > 0) {
            clearTabThumbnails()
            // Modern Android no longer gives apps a reliable ladder of memory-pressure levels;
            // any positive callback is treated as a request to release optional renderers.
            evictBackgroundWebViews(maxRetained = 1)
        }
    }

    override fun onLowMemory() {
        clearTabThumbnails()
        evictBackgroundWebViews(maxRetained = 1)
        super.onLowMemory()
    }

    // ========== Compose WebView ==========

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun WebViewContent(
        tabId: Long,
        sessionState: ByteArray?,
        isRefreshing: Boolean,
        modifier: Modifier = Modifier,
        onPullDistanceChange: (Float) -> Unit,
        onPullRefresh: () -> Unit,
        onRefreshComplete: () -> Unit,
        onShowMessage: (String) -> Unit,
        onDownloadStarted: (StartedDownload) -> Unit,
        launchDownload: (download: () -> Unit) -> Unit,
        onShowBase64Image: (String) -> Unit,
    ) {
        val currentIsRefreshing by rememberUpdatedState(isRefreshing)
        val currentOnPullDistanceChange by rememberUpdatedState(onPullDistanceChange)
        val currentOnPullRefresh by rememberUpdatedState(onPullRefresh)
        val currentOnRefreshComplete by rememberUpdatedState(onRefreshComplete)
        val currentOnDownloadStarted by rememberUpdatedState(onDownloadStarted)
        val currentLaunchDownload by rememberUpdatedState(launchDownload)

        // Pull-to-refresh bridge: mutable gesture state + latest callbacks in
        // one remembered holder that survives recomposition. The touch
        // listener is installed once per WebView and reads this holder, so
        // recomposition never resets an in-progress gesture.
        val pullBridge = remember { PullToRefreshTouchBridge() }

        AndroidView(
            factory = { ctx -> attachTab(ctx, tabId, sessionState, pullBridge) },
            modifier = modifier.fillMaxSize(),
            update = { view ->
                // reconcileWebViews can destroy a closed tab's WebView before
                // Compose disposes this entry; never touch a view we no longer
                // own (tabIdOf falls back for unowned views, so the identity
                // check below catches them).
                if (webViews[tabIdOf(view)] !== view) return@AndroidView
                // Only refresh callbacks here: the touch listener stays
                // installed once, so an in-progress pull gesture is never
                // reset by recomposition.
                pullBridge.isRefreshing = { currentIsRefreshing }
                pullBridge.onPullDistanceChange = { currentOnPullDistanceChange(it) }
                pullBridge.onPullRefresh = { currentOnPullRefresh() }
                view.bindDownloadListener(
                    onDownloadStarted = { currentOnDownloadStarted(it) },
                    launchDownload = { currentLaunchDownload(it) },
                )
                (view.webChromeClient as? NexaWebChromeClient)?.let { client ->
                    client.updateCallbacks(
                        onProgressChangedEvent = { browserViewModel.onProgressChanged(tabId, it) },
                        onFullscreenEnter = { browserViewModel.onFullscreenEnter(tabId) },
                        onFullscreenExit = { browserViewModel.onFullscreenExit(tabId) },
                        onProgressComplete = { currentOnRefreshComplete() },
                        onReceivedTitleEvent = { url, title ->
                            browserViewModel.onReceivedTitle(tabId, url, title)
                        },
                    )
                    client.updateFileChooserLauncher { intent ->
                        pendingFileChooserClient = client
                        launchFileChooser(intent)
                    }
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
                    val wv = attachedWebView() ?: return@ContextMenuScreen
                    ContextMenuHandler.onActionSelected(
                        action = action,
                        webView = wv,
                        context = this@MainActivity,
                        onDownloadStarted = { currentOnDownloadStarted(it) },
                        launchDownload = { currentLaunchDownload(it) },
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

        // ── WebView pause/resume ───────────────────────────────────
        // Handled at Activity level (onResume/onPause overrides): a
        // composition-scoped effect re-subscribes on every tab switch, and
        // observer catch-up would resume every WebView — undoing the
        // outgoing tab's pause — while non-browser routes would never
        // pause at all.
        // Note: pauseTimers()/resumeTimers() are intentionally NOT used —
        // they are process-global and would freeze every WebView in the
        // process, not just this one.
    }

    /**
     * Attaches [tabId]'s WebView to the Compose tree, creating it lazily if
     * this is the first time the tab is materialized in this Activity
     * instance (restoring [sessionState] when available). Detaches (pauses)
     * the previously attached WebView after capturing its navigation state.
     */
    private fun attachTab(
        context: Context,
        tabId: Long,
        sessionState: ByteArray?,
        pullBridge: PullToRefreshTouchBridge,
    ): WebView {
        val previousId = attachedTabId
        val retainedWebView = webViews.remove(tabId)
        val webView = retainedWebView?.also {
            // Reinsert to keep LinkedHashMap in least-recently-used order.
            webViews[tabId] = it
            (it.parent as? ViewGroup)?.removeView(it)
            it.installPullToRefreshTouchBridge(pullBridge)
        } ?: createWebView(context, tabId, sessionState, pullBridge)

        if (previousId != null && previousId != tabId) {
            // A pending long-press menu belongs to the old tab; its actions
            // would dispatch against the new tab's WebView.
            contextMenuActions.value = emptyList()
            webViews[previousId]?.apply {
                persistSessionState(previousId, this)
                // A tab switch leaves fullscreen video behind — the overlay
                // sits in an Activity-wide container above every tab.
                (webChromeClient as? NexaWebChromeClient)
                    ?.takeIf { it.isFullscreen }
                    ?.onHideCustomView()
                onPause()
            }
        }
        attachedTabId = tabId
        evictBackgroundWebViews(maxRetained = MAX_MATERIALIZED_WEBVIEWS)

        val tab = tabItem(tabId)
        browserViewModel.onActiveTabAttached(
            url = webView.url ?: tab?.url,
            title = webView.title ?: tab?.title,
            canGoBack = webView.canGoBack(),
            canGoForward = webView.canGoForward(),
        )
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            webView.onResume()
        }
        return webView
    }

    /**
     * Creates and registers a WebView for [tabId]. When the tab has a stored
     * navigation [sessionState] it is restored — complete back/forward list,
     * current entry reloaded — otherwise the tab's persisted URL is loaded.
     */
    private fun createWebView(
        context: Context,
        tabId: Long,
        sessionState: ByteArray?,
        pullBridge: PullToRefreshTouchBridge,
    ): WebView {
        val tab = tabItem(tabId)
        val persistedUrl = tab?.url?.takeIf { isSafeLoadableUrl(it) } ?: BrowserUrls.HOME

        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Assign the isolated profile before any settings or loads touch
            // WebView storage. Unsupported providers never create private tabs.
            if (tab?.isPrivate == true && !privateWebViewProfile.attach(this)) {
                destroy()
                throw IllegalStateException("Private WebView profiles are unavailable")
            }

            // ── WebView configuration ─────────────────────────
            WebViewConfigurator.configure(this, isPrivate = tab?.isPrivate == true)

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

            // Rebound with the screen's snackbar and permission gate by the AndroidView update.
            bindDownloadListener(onDownloadStarted = { _ -> }, launchDownload = { it() })

            // ── Content blocking (before the first load) ──────
            val contentBlocker = WebViewContentBlocker(
                repository = adBlockRepository,
                policyStore = adBlockPolicyStore,
                stats = adBlockStatsRecorder,
                assets = adBlockAssets,
                blockPage = BlockedPageRenderer(context),
                isPrivate = tab?.isPrivate == true,
            )
            contentBlocker.install(this)
            contentBlockers[tabId] = contentBlocker

            // ── Media probe (confirms media on tweets / posts) ─
            val mediaProbeInstalled = pageMediaProbe.install(this) { url, hasMedia ->
                browserViewModel.onPageMediaReported(tabId, url, hasMedia)
            }
            if (!mediaProbeInstalled) browserViewModel.onPageMediaProbeUnavailable(tabId)

            // ── WebViewClient (events carry this tab's id) ─────
            val historyClient = NexaWebViewClient(
                appContext = context.applicationContext,
                contentBlocker = contentBlocker,
                onPageStartedEvent = { url -> browserViewModel.onPageStarted(tabId, url) },
                onPageFinishedEvent = {
                    browserViewModel.onPageFinished(tabId, canGoBack(), canGoForward())
                },
                onNavigationConsumedEvent = {
                    browserViewModel.onNavigationConsumed(tabId, canGoBack(), canGoForward())
                },
                onUrlUpdatedEvent = { browserViewModel.onUrlUpdated(tabId, it) },
                onPageLoadErrorEvent = { browserViewModel.onPageLoadError(tabId) },
                onVisitCommittedEvent = { url, isReload ->
                    browserViewModel.onVisitCommitted(tabId, url, isReload)
                    scheduleSessionCapture(tabId)
                },
                onRenderProcessGoneEvent = { handleRenderProcessGone(tabId) },
                // Keeps Back ownership exact: the browser consumes Back only while the page can
                // go back, so the system back-to-home preview runs as soon as it cannot.
                onHistoryUpdatedEvent = {
                    browserViewModel.onHistoryUpdated(tabId, canGoBack(), canGoForward())
                },
            )
            webViewClient = historyClient

            // ── WebChromeClient ───────────────────────────────
            lateinit var chromeClient: NexaWebChromeClient
            chromeClient = NexaWebChromeClient(
                activity = this@MainActivity,
                webView = this,
                customViewContainer = customViewContainer,
                rootView = this,
                onProgressChangedEvent = { browserViewModel.onProgressChanged(tabId, it) },
                onFullscreenEnter = { browserViewModel.onFullscreenEnter(tabId) },
                onFullscreenExit = { browserViewModel.onFullscreenExit(tabId) },
                onProgressComplete = {},
                fileChooserLauncher = { intent ->
                    pendingFileChooserClient = chromeClient
                    launchFileChooser(intent)
                },
                onReceivedTitleEvent = { url, title ->
                    browserViewModel.onReceivedTitle(tabId, url, title)
                },
                onReceivedIconEvent = { url, icon ->
                    if (tabId < 0L) {
                        storePrivateTabFavicon(tabId, icon)
                    } else {
                        faviconRepository.get().store(url, icon)
                    }
                },
                isAttachedToUi = { attachedTabId == tabId },
                popupWindowHandler = PopupWindowHandler(
                    contentBlocker = contentBlocker,
                    createProbeWebView = {
                        WebView(context).also { probe ->
                            if (tab?.isPrivate == true && !privateWebViewProfile.attach(probe)) {
                                probe.destroy()
                                throw IllegalStateException("Private WebView profiles are unavailable")
                            }
                        }
                    },
                    openInNewTab = { url -> browserViewModel.openPopupTab(url, openerTabId = tabId) },
                ),
                onCloseWindowEvent = { browserViewModel.closeTab(tabId) },
            )
            webChromeClient = chromeClient

            // ── Initial load / navigation-state restore ───────
            // Restoring reloads the current entry, and an initial load is
            // programmatic: neither is a fresh user visit.
            historyClient.suppressNextVisitCommit = true
            val restored = sessionState?.let { WebViewSessionState.restore(this, it) }
            when {
                restored == null -> loadUrl(persistedUrl)
                // The restored entry must pass the same safe-URL check as any load.
                !isSafeLoadableUrl(restored.currentItem?.url) -> {
                    clearHistory()
                    // Re-arm: the restore's own commit may consume the flag.
                    historyClient.suppressNextVisitCommit = true
                    loadUrl(persistedUrl)
                }
            }
        }

        webViews[tabId] = webView
        return webView
    }

    private fun WebView.bindDownloadListener(
        onDownloadStarted: (StartedDownload) -> Unit,
        launchDownload: (download: () -> Unit) -> Unit,
    ) {
        setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            // Captured now: the page may navigate while storage access is being granted.
            val userAgent = settings.userAgentString
            val pageUrl = this.url
            val cookieManager = if (tabItem(tabIdOf(this))?.isPrivate == true) {
                privateWebViewProfile.cookieManager(this)
            } else {
                android.webkit.CookieManager.getInstance()
            }
            launchDownload {
                DownloadHandler.startDownload(
                    context = context,
                    url = url,
                    mimeType = mimeType,
                    contentDisposition = contentDisposition,
                    userAgent = userAgent,
                    currentPageUrl = pageUrl,
                    cookieManager = cookieManager,
                    onDownloadStarted = onDownloadStarted,
                )
            }
        }
    }

    /** Navigation state resolved for a tab's WebView before its AndroidView factory runs. */
    private class SessionSnapshot(val state: ByteArray?) {
        companion object {
            /** The tab's WebView is still materialized; its live history wins. */
            val Retained = SessionSnapshot(null)
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

    // This listener only intercepts an actual pull gesture. Returning false for
    // taps delegates the complete click/accessibility path to WebView itself;
    // calling performClick here would duplicate WebView's own click handling.
    @SuppressLint("ClickableViewAccessibility")
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

    // ========== WebView helpers ==========

    private fun attachedWebView(): WebView? = attachedTabId?.let { webViews[it] }

    private fun tabIdOf(view: View): Long =
        webViews.entries.firstOrNull { it.value === view }?.key ?: attachedTabId ?: -1L

    private fun tabItem(tabId: Long): TabItem? =
        browserViewModel.workspace.value.tabs.firstOrNull { it.id == tabId }

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

    private fun captureAttachedTabThumbnail() {
        val tabId = attachedTabId ?: return
        val webView = webViews[tabId] ?: return
        if (webView.width <= 0 || webView.height <= 0) return
        runCatching {
            val scale = minOf(
                1f,
                THUMBNAIL_MAX_WIDTH_PX.toFloat() / webView.width,
                THUMBNAIL_MAX_HEIGHT_PX.toFloat() / webView.height,
            )
            val width = (webView.width * scale).toInt().coerceAtLeast(1)
            val height = (webView.height * scale).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(width, height, Bitmap.Config.RGB_565)
            Canvas(bitmap).apply {
                scale(width.toFloat() / webView.width, height.toFloat() / webView.height)
                webView.draw(this)
            }
            tabThumbnails.put(tabId, bitmap)?.takeUnless { it === bitmap || it.isRecycled }?.recycle()
        }.onFailure { Log.w(TAG, "Unable to capture tab thumbnail", it) }
    }

    private fun storePrivateTabFavicon(tabId: Long, source: Bitmap?) {
        source ?: return
        runCatching {
            val ratio = minOf(
                1f,
                PRIVATE_FAVICON_MAX_SIZE_PX.toFloat() / maxOf(source.width, source.height),
            )
            val width = (source.width * ratio).toInt().coerceAtLeast(1)
            val height = (source.height * ratio).toInt().coerceAtLeast(1)
            val scaled = source.scale(width, height)
            val ownedCopy = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (scaled !== source && scaled !== ownedCopy && !scaled.isRecycled) scaled.recycle()
            privateTabFavicons.put(tabId, ownedCopy)
                ?.takeUnless { it === ownedCopy || it.isRecycled }
                ?.recycle()
        }.onFailure { Log.w(TAG, "Unable to retain private favicon", it) }
    }

    private fun clearTabThumbnails() {
        tabThumbnails.values.forEach { if (!it.isRecycled) it.recycle() }
        tabThumbnails.clear()
    }

    private fun clearPrivateTabFavicons() {
        privateTabFavicons.values.forEach { if (!it.isRecycled) it.recycle() }
        privateTabFavicons.clear()
    }

    private fun cleanUpAllWebViews() {
        webViews.keys.toList().forEach { destroyWebView(it) }
        webViews.clear()
        contentBlockers.clear()
        attachedTabId = null
        customViewContainer.removeAllViews()
        clearPrivateTabFavicons()
    }

    /**
     * Destroys runtime WebViews whose persistent tab row is gone (tab closed).
     * Without this, a closed tab's WebView — and the renderer/DOM memory and
     * Activity context it holds — would stay strongly referenced in [webViews]
     * until the Activity is destroyed.
     *
     * Runs on the main thread; both this and Compose recomposition are
     * main-thread-sequential, so there is no concurrency with [attachTab].
     */
    private fun reconcileWebViews(liveTabIds: Set<Long>) {
        webViews.keys.filter { it !in liveTabIds }.forEach { destroyWebView(it) }
        if (liveTabIds.none { it < 0L }) {
            // All profile-associated WebViews have now been destroyed.
            privateWebViewProfile.clearSession()
        }
    }

    /**
     * Tears down and unregisters the WebView for [tabId]. Swaps the clients to
     * dummies first so no further callback can reach a deleted tab, stops any
     * in-flight load, detaches from its parent, and destroys it.
     */
    private fun evictBackgroundWebViews(maxRetained: Int) {
        val protectedId = attachedTabId
        while (webViews.size > maxRetained.coerceAtLeast(1)) {
            val candidate = webViews.keys.firstOrNull { it != protectedId } ?: break
            // The tab stays open: keep its history so re-materializing restores it exactly.
            webViews[candidate]?.let { persistSessionState(candidate, it) }
            destroyWebView(candidate, removeVisuals = false)
        }
    }

    /** Captures [webView]'s navigation state for [tabId] now, superseding a debounced capture. */
    private fun persistSessionState(tabId: Long, webView: WebView) {
        sessionCaptureJobs.remove(tabId)?.cancel()
        WebViewSessionState.capture(webView)?.let { browserViewModel.saveTabSessionState(tabId, it) }
    }

    private fun persistAllSessionStates() {
        webViews.forEach { (tabId, webView) -> persistSessionState(tabId, webView) }
    }

    /**
     * Captures [tabId]'s navigation state shortly after a committed navigation, so even a crash
     * (no onStop) loses at most the last debounce window of history.
     */
    private fun scheduleSessionCapture(tabId: Long) {
        sessionCaptureJobs.remove(tabId)?.cancel()
        sessionCaptureJobs[tabId] = lifecycleScope.launch {
            delay(SESSION_CAPTURE_DEBOUNCE_MS)
            sessionCaptureJobs.remove(tabId)
            webViews[tabId]?.let { persistSessionState(tabId, it) }
        }
    }

    private fun destroyWebView(tabId: Long, removeVisuals: Boolean = true) {
        if (removeVisuals) {
            tabThumbnails.remove(tabId)?.takeUnless { it.isRecycled }?.recycle()
            privateTabFavicons.remove(tabId)?.takeUnless { it.isRecycled }?.recycle()
        }
        sessionCaptureJobs.remove(tabId)?.cancel()
        contentBlockers.remove(tabId)
        val webView = webViews.remove(tabId) ?: return
        try {
            if (pendingFileChooserClient === webView.webChromeClient) {
                pendingFileChooserClient = null
            }
            (webView.webChromeClient as? NexaWebChromeClient)?.cleanUpFullscreen()
            webView.apply {
                stopLoading()
                webViewClient = DESTROYING_WEB_VIEW_CLIENT
                webChromeClient = WebChromeClient()
                clearHistory()
                onPause()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error destroying WebView for tab $tabId: ${e.message}", e)
        }
        if (attachedTabId == tabId) attachedTabId = null
    }

    /**
     * Recovers from a renderer-process death: the dead WebView is destroyed
     * and the composable factory recreates a fresh one for the tab, restoring
     * its last captured navigation state (a dead WebView must not be touched
     * again, so no capture happens here). Without this, one crashed renderer
     * would kill the whole app process.
     */
    private fun handleRenderProcessGone(tabId: Long) {
        Log.w(TAG, "Recreating WebView for tab $tabId after renderer death")
        destroyWebView(tabId)
        webViewGeneration.intValue++
    }

    // ========== WebView Operations ==========

    /**
     * Safely executes WebView operations on the attached WebView with proper
     * lifecycle checks.
     */
    private fun safeWebViewOperation(operation: (WebView) -> Unit) {
        try {
            val wv = attachedWebView()
            if (wv == null) {
                Log.w(TAG, "No attached WebView")
                return
            }
            operation(wv)
        } catch (e: Exception) {
            Log.e(TAG, "WebView operation failed: ${e.message}", e)
        }
    }

    /** Loads [url] in the active tab (used by history/bookmarks/open-intent). */
    private fun loadUrlInActiveTab(url: String) {
        if (!isSafeLoadableUrl(url)) {
            Log.w(TAG, "Blocked unsafe programmatic URL")
            return
        }
        safeWebViewOperation { wv ->
            wv.post { wv.loadUrl(url) }
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
                    loadUrlInActiveTab(url)
                }
            }
        }
    }

    /**
     * Destroys runtime WebViews for tabs removed from the workspace (closed).
     * Collected for the whole lifecycle (not just STARTED) so a tab closed
     * while backgrounded still releases its WebView promptly.
     */
    private fun observeTabListForWebViewCleanup() {
        lifecycleScope.launch {
            browserViewModel.workspace.collect { workspace ->
                reconcileWebViews(workspace.tabs.map { it.id }.toSet())
            }
        }
    }

    private fun navigateToHome() {
        val homeUrl = browserViewModel.selectedSearchEngine.value.homeUrl
        safeWebViewOperation { wv ->
            wv.post {
                // Programmatic home load — not a user visit.
                (wv.webViewClient as? NexaWebViewClient)?.suppressNextVisitCommit = true
                wv.loadUrl(homeUrl)
            }
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
                // Initial/about:blank state: fall back to the persisted URL.
                val targetUrl = tabItem(tabIdOf(wv))?.url ?: BrowserUrls.HOME
                if (URLUtil.isValidUrl(targetUrl)) {
                    wv.post {
                        // Reload semantics, not a fresh visit.
                        (wv.webViewClient as? NexaWebViewClient)
                            ?.suppressNextVisitCommit = true
                        wv.loadUrl(targetUrl)
                    }
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
     * Navigates forward in the active tab's WebView history.
     */
    fun goForward() {
        safeWebViewOperation { wv ->
            if (wv.canGoForward()) {
                (wv.webViewClient as? NexaWebViewClient)?.suppressNextVisitCommit = true
                wv.goForward()
            }
        }
    }

    private fun copyUrl(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.website_url), url))
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

    /**
     * Android 12+ draws the splash screen (and the window background) before any app code
     * runs, using the app's persisted night mode. Mirroring the in-app theme choice there makes
     * a forced light/dark theme start without a flash of the other one. Older versions draw the
     * splash with the system mode.
     */
    private var appliedNightMode: Int? = null

    private fun observeThemeModeForSystemWindows() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val uiModeManager = getSystemService(UiModeManager::class.java) ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appPreferences.themeMode
                    .map { AppTheme.fromPreferenceValue(it).toUiModeNightMode() }
                    .distinctUntilChanged()
                    .collect { nightMode ->
                        // Persisted by the system: one IPC per change, not per start.
                        if (appliedNightMode != nightMode) {
                            uiModeManager.setApplicationNightMode(nightMode)
                            appliedNightMode = nightMode
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
        if (intent?.action == DownloadService.ACTION_OPEN_DOWNLOADS) {
            requestedRoute = AppRoute.Downloads()
        }
    }
}
