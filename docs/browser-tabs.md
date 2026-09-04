# Browser tabs and private browsing

The tab overview follows the supplied Chrome reference while deliberately omitting tab groups. Its header uses
the app-wide compact `PillTabBar`, shared tab-count glyph, shared overflow menu, and standard icon-button geometry.
The control and content share one `HorizontalPager`, so taps, accessibility actions, and horizontal swipes all update
the same workspace state. The incognito segment is absent until an incognito tab exists; the first incognito tab is
created from the overflow menu. The add action then follows the selected workspace. The shared Snackbar host
provides close/Undo behavior.

Cards retain the reference hierarchy: a compact favicon/title/close header, a top-cropped page preview, rounded
container, and primary outline/header treatment only for the active tab. Search and grid widths are bounded on
large windows without creating a second source of tab state. Each card is wrapped in Material 3
`SwipeToDismissBox`: either horizontal direction closes only after the component's positional threshold and uses
the existing close/Undo path. Vertical grid scrolling remains owned by `LazyVerticalGrid`; workspace paging stays
available outside a card drag. Each card's anchored Material menu provides Select, dynamic Bookmark/Remove
bookmark, Pin/Unpin, context-aware reorder/share, and Close actions. Selection mode uses Material checkboxes and
the same shared selection app bar as Bookmarks; its overflow menu derives available actions from the selected tabs.

## Architecture and lifetime

`TabRepository` is the workspace source of truth. It exposes one immutable `TabWorkspaceState` `StateFlow`, so the
ordered list, active pointer, and restoration status are published atomically. Normal tabs are transactional Room
rows. Private tabs are negative-id, process-memory-only `TabItem`s in the same repository state machine,
distinguished by `BrowsingMode`; they never cross the Room boundary. Create, switch, close, selection-close, pin,
reorder, URL, and title mutations therefore share one set of rules rather than parallel UI logic. Reorder UI keeps
only a temporary stable-id order; current repository membership and metadata always win during rendering.

`MainActivity` owns runtime WebViews, keyed by tab identity. A WebView is created only when its tab first becomes
active; cards never create WebViews. Only one WebView is attached and resumed. A small access-ordered pool retains
at most four materialized renderers; older background renderers are destroyed without deleting their metadata or
preview and are recreated from their safe committed URL when selected. Any memory-trim callback reduces the pool
to the active renderer. Closed views are stopped, detached, stripped of callbacks, destroyed, and removed
immediately. Renderer death replaces only the affected view. The tab overview pauses the attached view while it
covers browser content.

Card previews are bounded RGB_565 snapshots (at most 240 × 360 pixels), captured only from the attached view.
Capture preserves the WebView aspect ratio; the card then top-crops through `ContentScale.Crop` rather than
non-uniformly stretching page pixels. Missing previews use the same lightweight skeleton treatment as the
reference. Snapshots are optional, never persisted, recycled on close, and dropped on memory pressure. This keeps
the visual grid close to the reference without materializing tabs or retaining full-size page bitmaps.

## Private-data boundary

Private browsing is enabled only when the installed Android System WebView supports AndroidX WebKit's
`MULTI_PROFILE` feature. It is never emulated with the process-global `CookieManager`, because that would mix site
data. Every private WebView is assigned `nexa_private` before configuration or navigation, giving it isolated
cookies, WebStorage, service workers, and cache. Private loads use `LOAD_NO_CACHE`; downloads explicitly obtain
cookies from that private profile.

Private tabs and their WebView state are excluded from Room and instance-state persistence. Their navigations,
titles, searches, and autocomplete UI do not read or write local browsing/search history. Remote suggestions
remain optional and cancellable. Normal favicons use the shared bounded cache; incognito favicons are copied into
a small process-memory map and never query or write that cache. When the final private tab closes, associated
WebViews are destroyed before the profile is deleted/cleared; the clear is a no-op while no private WebView has
been attached, so workspace changes never touch the WebView provider needlessly. Stale profile data left by
process death is removed before the next private WebView is configured, so it is never reused. Downloads and shared bookmarks remain intentionally persistent.

On WebView providers without multi-profile support, private actions are disabled and the UI explains that a newer
Android System WebView is required. The app never presents a non-isolated session as private.

## Adaptive, bidi, motion, and accessibility

The overview is full-screen and edge-to-edge with safe-drawing/navigation insets. `GridCells.Adaptive` chooses the
column count from available width, while content width is bounded on large displays. Stable tab ids back lazy-grid
items. The pager composes no offscreen workspace beyond its required page, and item placement animations use
layout transforms; Android's system animator scale remains authoritative. A fixed two-page pager lifetime avoids
an invalid page when the final incognito tab closes; user scrolling is disabled whenever that second workspace is
absent, and the incognito segment is removed only after the pager has atomically returned to normal.

All controls meet a 48dp minimum target, close actions have labels, and selected cards expose Material checkbox
semantics. Selection state is hoisted to the overview, while durable workspace state remains immutable in the
repository. Private availability is represented by enabled state. Start/end layout primitives and the ambient
layout direction handle global RTL/LTR ordering. The shared pill indicator maps logical pager indices to visual
positions in RTL. Titles, hosts, URLs, and queries use content-directed typography, so mixed Arabic, Hebrew, Latin,
numbers, and neutral prefixes follow the Unicode bidi algorithm rather than the app locale. No directional gesture
or icon is hard-coded in the overview.

## Official guidance reviewed

- [Build web apps in WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- [Manage WebView objects](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview)
- [AndroidX WebKit `WebViewCompat.setProfile`](https://developer.android.com/reference/androidx/webkit/WebViewCompat#setProfile(android.webkit.WebView,java.lang.String))
- [AndroidX WebKit `ProfileStore`](https://developer.android.com/reference/androidx/webkit/ProfileStore)
- [AndroidX WebKit 1.9 multi-profile release](https://developer.android.com/jetpack/androidx/releases/webkit#1.9.0)
- [Compose pagers](https://developer.android.com/develop/ui/compose/layouts/pager)
- [Compose touch input and gestures](https://developer.android.com/develop/ui/compose/touch-input/pointer-input)
- [Material 3 swipe-to-dismiss](https://developer.android.com/develop/ui/compose/touch-input/user-interactions/swipe-to-dismiss)
- [State and Jetpack Compose](https://developer.android.com/develop/ui/compose/state)
- [Where to hoist state](https://developer.android.com/develop/ui/compose/state-hoisting)
- [Material 3 checkboxes](https://developer.android.com/develop/ui/compose/components/checkbox)
- [Material 3 menus](https://developer.android.com/develop/ui/compose/components/menu)
- [Compose adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive)
- [Support different display sizes](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes)
- [Material 3 tabs in Compose](https://developer.android.com/develop/ui/compose/components/tabs)
- [Material Components tabs guidance](https://github.com/material-components/material-components-android/blob/master/docs/components/Tabs.md)
- [Compose accessibility](https://developer.android.com/develop/ui/compose/accessibility)
- [Touch target sizing](https://developer.android.com/develop/ui/compose/accessibility/api-defaults#minimum-touch-target-sizes)
- [Support RTL layouts](https://developer.android.com/training/basics/supporting-devices/languages#SupportRtl)
