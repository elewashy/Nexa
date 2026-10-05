# Motion and UI performance

Nexa follows Android's official Jetpack Compose and Material 3 guidance:

- [Compose animation quick guide](https://developer.android.com/develop/ui/compose/animation/quick-guide)
- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Navigation 3](https://developer.android.com/guide/navigation/navigation-3)
- [Predictive back](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)
- [Splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen)
- [App startup time](https://developer.android.com/topic/performance/vitals/launch-time)
- [Save UI state](https://developer.android.com/topic/libraries/architecture/saving-states)
- [Request runtime permissions](https://developer.android.com/training/permissions/requesting)
- [Material 3 menus](https://developer.android.com/develop/ui/compose/components/menu)
- [Material 3 snackbars](https://developer.android.com/develop/ui/compose/components/snackbar)
- [Compose gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)
- [Drag, swipe, and fling](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/drag-swipe-fling)
- [Compose graphics shapes](https://developer.android.com/develop/ui/compose/graphics/draw/shapes)
- [Compose lazy lists](https://developer.android.com/develop/ui/compose/lists)
- [State hoisting and UI state holders](https://developer.android.com/develop/ui/compose/state-hoisting)
- [Support different display sizes](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes)
- [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes)

## Project rules

1. The app has one Navigation 3 back stack (`AppNavigator`, keys in `AppRoute`) shown by
   `AppNavDisplay`; features add their pages with `EntryProviderScope<AppRoute>` builders instead
   of nesting their own hosts. Each entry gets its own saveable state and ViewModel store, and the
   serialized back stack survives process death. Pages that cover the browser fade through so the
   `WebView` is never translated or scaled; pages inside a feature use the shared-axis pattern.
   The predictive back preview is short and linear so it tracks the finger 1:1 and a fast swipe
   completes in the remaining fraction of 150 ms; back without a preview (keys, buttons, a flick
   released before the preview starts) uses 200 ms pops. `AppNavDisplayBackTest` measures both.
   A `BackHandler` is enabled only while a page has something of its own to undo (selection,
   folder level, fullscreen video, page history); otherwise the navigation host or the system
   owns Back and plays the predictive animation.
2. Use the theme's standard Material motion scheme. Material components such as menus, dialogs,
   and snackbars own their entrance and exit transitions; do not wrap them in duplicate animations.
3. Prefer alpha, scale, and translation. For rapidly changing visual values, read state from a
   `graphicsLayer` lambda so updates can skip composition and layout.
4. Never animate directly behind pointer input. Dragged surfaces must track the pointer immediately;
   optional settling motion starts only after input ends.
5. Keep motion finite and purposeful. Avoid infinite transitions and bouncy springs in core UI.
6. Give lazy-list items stable keys and move expensive parsing, formatting, and I/O out of
   composition. Use Paging or lazy containers for potentially large collections.
7. Keep animation state local or in a stable UI state holder. Persist user state, not transient
   animation progress.
8. Respect the platform animator-duration scale. Compose animation and Material component APIs do
   this automatically; do not implement wall-clock animation loops.
9. Assess jank in a non-debuggable release/profileable build on representative 60 Hz and high-refresh
   devices. Debug Compose performance is not representative. Use Macrobenchmark, system traces, and
   frame-timing data before adding device-specific behavior.
10. Gesture regions must have one unambiguous owner. Bookmark icons own long-press drag while the
    rest of each row owns long-press selection; selection mode disables drag at the gesture source.
11. Snackbars follow a two-dimensional drag after touch slop. Distance and outward-velocity
    thresholds decide dismissal; an incomplete or reversing gesture settles to the origin with a
    no-bounce spring. Translation and drag feedback stay in `graphicsLayer`, and an equivalent
    accessibility dismiss action is always provided.
12. Manual bookmark ordering is one sibling sequence containing folders and links. The optimistic
    lazy-list order and the transactional Room order use the same keys, tie-breakers, and position
    domain so mixed drag operations cannot diverge.
13. Browser address-mode changes use one finite `AnimatedContent` transition in the toolbar and one
    `AnimatedVisibility` transition for the keyboard-aware overlay. Material `DropdownMenu` owns
    overflow entrance, exit, transform origin, focus, and anchor fallback; feature code must not
    layer another popup animation or maintain a parallel position provider.
14. Preview-based settings use the shared `PhoneDesignSelectorScreen` and `PhonePreviewFrame`.
    `HorizontalPager` owns dragging, fling thresholds, RTL, accessibility paging, and state restoration for both
    Download Manager design and browser navigation position.
15. Full-page and embedded empty states use `AppEmptyState`: a decorative `RoundedPolygon` badge,
    Material `titleLarge`/`bodyMedium` roles, centered copy, and a restrained finite entrance.
    Geometry is normalized to the 0..1 bounds required by Material's `RoundedPolygon.toShape()` so
    it cannot paint beyond its measured container. It then resolves from the component's actual
    `BoxWithConstraints` space: 56/26 dp badge/icon in compact panes or short landscape windows,
    80/36 dp normally, and a bounded 96/44 dp on expanded windows. Content width, horizontal
    padding, and vertical spacing adapt with the same
    policy; constrained content can scroll for large fonts instead of clipping or overlapping.
    Material does not prescribe an empty-state component or fixed dimensions, so these are app
    design-system tokens rather than claimed platform constants.

## Startup and state rules

1. The system splash screen (core-splashscreen) is the only launch screen. It is held only while
   the persisted onboarding state (DataStore) and, for the browser, the tab workspace (Room) are
   read, capped at 500 ms, and exits with a short fade. Nothing on the startup path waits for the
   network, an update check, or a permission.
2. The start destination comes from persisted state, not from saved state: `StartupViewModel`
   maps `ShouldShowOnboardingUseCase` to `StartupDestination`, and `StartupHost` composes only
   that destination. The first launch shows onboarding; once it is finished or skipped
   (`CompleteOnboardingUseCase`, DataStore key `onboarding_completed`, the same key as 1.3.1) it
   never returns unless the app's data is cleared. The browser and its WebView are not composed
   while onboarding is on screen.
3. Onboarding is four short pages (welcome, permissions, search engine, appearance) with a
   persistent Skip, hosted by `OnboardingFlow`: its own small Navigation 3 back stack on
   `NexaNavDisplay` (the same host, decorators and motion as `AppNavDisplay`), so the Appearance
   rows open the app's real Theme and navigation-bar pages with their own entry-scoped
   ViewModels, and Back/predictive back returns to the step. Each choice is a regular setting
   written when picked; the open step is `rememberSaveable` and the flow's back stack is
   serialized, so both survive process death.
   The permissions step is optional: each permission is granted from its row
   (`PermissionsRequestState`: the system dialog for runtime permissions, falling back to the
   app's settings once the dialog is no longer shown; the matching Settings page for special
   access), grant state is re-read on every resume, and "Skip for now" moves on.
4. Work that the first frame does not need is deferred: the launch update check waits 3 s and for
   a validated network (and only starts with the browser), and singletons such as the download
   engine are injected lazily.
5. Every permission is still requested in context when a feature first needs it (a download,
   `DownloadPermissionGate`), whether or not it was granted or skipped during onboarding.
6. Persistent data (tabs and their WebView history, settings, bookmarks, history, downloads) lives
   in Room or DataStore. Recreatable UI state the user expects back (the back stack, an open
   address editor, searches, folder paths, selections, scroll positions, the onboarding page) is
   saved state: `rememberSaveable` or `SavedStateHandle`, kept small and free of private-tab
   content.
7. Startup is reported with `ReportDrawn`/`ReportDrawnWhen` (TTFD): onboarding on its first
   frame, the browser once the tab workspace is restored.
8. `app/src/main/baseline-prof.txt` holds hand-written Baseline Profile rules for the startup and
   browsing path (library profiles are merged automatically). Replace it with a generated profile
   (Baseline Profile Gradle plugin + Macrobenchmark) when a device-backed benchmark module exists.

Centralized motion files:

- `app/src/main/java/com/elewashy/nexa/ui/navigation/AppNavDisplay.kt`
- `app/src/main/java/com/elewashy/nexa/ui/startup/StartupHost.kt`
- `app/src/main/java/com/elewashy/nexa/feature/onboarding/presentation/OnboardingScreen.kt`
- `app/src/main/java/com/elewashy/nexa/ui/theme/Theme.kt`
- `app/src/main/java/com/elewashy/nexa/ui/components/common/AppOverflowMenu.kt`
- `app/src/main/java/com/elewashy/nexa/ui/components/common/AppSnackbarHost.kt`
- `app/src/main/java/com/elewashy/nexa/ui/components/common/AppEmptyState.kt`
