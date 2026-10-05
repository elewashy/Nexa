package com.elewashy.nexa.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import com.elewashy.nexa.feature.settings.presentation.settings.SettingsDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Drives the real Navigation 3 NavDisplay inside [AppNavDisplay] with predictive back events, as
 * Android 14+ delivers them (one progress event per frame while the finger moves), on a virtual
 * frame clock, and measures how long the leaving page stays composed after the finger lifts.
 *
 * Budgets are the remaining fraction of the transition plus [FRAMEWORK_FRAMES] frames, which is
 * what NavDisplay needs to pop the stack, start the settle animation, and remove the page.
 */
@RunWith(RobolectricTestRunner::class)
class AppNavDisplayBackTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var navigator: AppNavigator
    private val input = DirectNavigationEventInput()

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            navigator = rememberAppNavigator()
            val dispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current).navigationEventDispatcher
            DisposableEffect(dispatcher) {
                dispatcher.addInput(input)
                onDispose { dispatcher.removeInput(input) }
            }
            AppNavDisplay(navigator = navigator) {
                entry<AppRoute.Browser>(metadata = BrowserEntryMetadata) { Page("browser") }
                entry<AppRoute.Settings> { route -> Page("settings-${route.page}") }
            }
        }
        settle()
    }

    @Test
    fun `fast swipe inside a feature finishes within the remaining fraction`() {
        open(AppRoute.Settings(), AppRoute.Settings(SettingsDestination.General))

        val gesture = swipeBack(releaseAtProgress = 0.1f, dragFrames = 3)

        assertTrue("preview was not shown while dragging", gesture.previewShown)
        assertBudget(gesture.visibleAfterReleaseMs, remainingFraction = 0.9f)
        assertEquals(AppRoute.Settings(), navigator.currentRoute)
        assertTrue(pageExists("settings-Root"))
    }

    @Test
    fun `fast swipe from a page covering the browser finishes within the remaining fraction`() {
        open(AppRoute.Settings())

        val gesture = swipeBack(releaseAtProgress = 0.1f, dragFrames = 3)

        assertTrue("preview was not shown while dragging", gesture.previewShown)
        assertBudget(gesture.visibleAfterReleaseMs, remainingFraction = 0.9f)
        assertEquals(AppRoute.Browser, navigator.currentRoute)
        assertTrue(pageExists("browser"))
    }

    @Test
    fun `slow swipe released near the end completes almost immediately`() {
        open(AppRoute.Settings(), AppRoute.Settings(SettingsDestination.General))

        val gesture = swipeBack(releaseAtProgress = 0.9f, dragFrames = 30)

        assertTrue(gesture.previewShown)
        assertBudget(gesture.visibleAfterReleaseMs, remainingFraction = 0.1f)
    }

    @Test
    fun `flick released before the preview starts still pops quickly`() {
        open(AppRoute.Settings(), AppRoute.Settings(SettingsDestination.General))

        val gesture = swipeBack(releaseAtProgress = 0.05f, dragFrames = 0)

        // Falls back to the 200 ms pop transition.
        assertTrue(
            "page stayed ${gesture.visibleAfterReleaseMs}ms",
            gesture.visibleAfterReleaseMs <= 200 + FRAMEWORK_FRAMES * FRAME_MS,
        )
        assertEquals(AppRoute.Settings(), navigator.currentRoute)
    }

    @Test
    fun `cancelled swipe keeps the page and drops the preview`() {
        open(AppRoute.Settings(), AppRoute.Settings(SettingsDestination.General))

        input.backStarted(event(progress = 0f))
        frame()
        repeat(10) { step ->
            input.backProgressed(event(progress = 0.05f * (step + 1)))
            frame()
        }
        assertTrue("previous page is revealed during the preview", pageExists("settings-Root"))

        input.backCancelled()
        settle()

        assertEquals(AppRoute.Settings(SettingsDestination.General), navigator.currentRoute)
        assertTrue(pageExists("settings-General"))
        assertTrue(!pageExists("settings-Root"))
    }

    private class Gesture(val previewShown: Boolean, val visibleAfterReleaseMs: Long)

    /** Navigates forward and waits, frame by frame, until only the new page is composed. */
    private fun open(vararg routes: AppRoute) {
        routes.forEach { route ->
            val previous = textOf(navigator.currentRoute)
            compose.runOnIdle { navigator.navigate(route) }
            var elapsedMs = 0L
            while (elapsedMs < GIVE_UP_MS && (!pageExists(textOf(route)) || pageExists(previous))) {
                frame()
                elapsedMs += FRAME_MS
            }
            check(pageExists(textOf(route)) && !pageExists(previous)) { "navigation to $route did not settle" }
        }
    }

    private fun textOf(route: AppRoute): String =
        if (route is AppRoute.Settings) "settings-${route.page}" else "browser"

    /**
     * Back gesture from the left edge: started, then one progress event per frame up to
     * [releaseAtProgress] over [dragFrames] frames, then released (committed).
     */
    private fun swipeBack(releaseAtProgress: Float, dragFrames: Int): Gesture {
        val leaving = textOf(navigator.currentRoute)
        val previousText = textOf(navigator.backStack[navigator.backStack.lastIndex - 1])

        input.backStarted(event(progress = 0f))
        frame()
        for (frameIndex in 1..dragFrames) {
            input.backProgressed(event(progress = releaseAtProgress * frameIndex / dragFrames))
            frame()
        }
        val previewShown = pageExists(leaving) && pageExists(previousText)

        input.backCompleted()
        var elapsedMs = 0L
        while (elapsedMs < GIVE_UP_MS && pageExists(leaving)) {
            frame()
            elapsedMs += FRAME_MS
        }
        return Gesture(previewShown, elapsedMs)
    }

    private fun assertBudget(visibleMs: Long, remainingFraction: Float) {
        val budget = (PREDICTIVE_BACK_DURATION_MS * remainingFraction).toLong() + FRAMEWORK_FRAMES * FRAME_MS
        assertTrue("page stayed ${visibleMs}ms after release; budget ${budget}ms", visibleMs <= budget)
    }

    private fun pageExists(text: String): Boolean =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun event(progress: Float) =
        NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = progress)

    private fun frame() {
        compose.mainClock.advanceTimeBy(FRAME_MS)
        compose.waitForIdle()
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private companion object {
        const val FRAME_MS = 16L
        const val SETTLE_MS = 2_000L
        const val GIVE_UP_MS = 2_000L
        const val FRAMEWORK_FRAMES = 5

        /** Mirrors the production predictive back transition length in [AppNavDisplay]. */
        const val PREDICTIVE_BACK_DURATION_MS = 150
    }
}

@Composable
private fun Page(name: String) {
    Box(Modifier.fillMaxSize()) { Text(name) }
}
