# Motion and UI performance

Nexa follows Android's official Jetpack Compose and Material 3 guidance:

- [Compose animation quick guide](https://developer.android.com/develop/ui/compose/animation/quick-guide)
- [Compose performance best practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Navigation with Compose](https://developer.android.com/develop/ui/compose/navigation)
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

1. Use `AppNavHost` for every navigation graph. Top-level destinations use fade-through so an
   expensive `WebView` is never translated across the screen. Hierarchical destinations use the
   shared-axis pattern.
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
    Download Manager design and browser navigation position. The first-run flow embeds the same selectors instead
    of maintaining onboarding-only carousel logic.
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

Centralized motion files:

- `app/src/main/java/com/elewashy/nexa/ui/navigation/AppNavHost.kt`
- `app/src/main/java/com/elewashy/nexa/ui/theme/Theme.kt`
- `app/src/main/java/com/elewashy/nexa/ui/components/common/AppOverflowMenu.kt`
- `app/src/main/java/com/elewashy/nexa/ui/components/common/AppSnackbarHost.kt`
- `app/src/main/java/com/elewashy/nexa/ui/components/common/AppEmptyState.kt`
