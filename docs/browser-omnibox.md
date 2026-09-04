# Browser omnibox

The browser search/address interaction follows Android's official guidance for
[state hoisting and UI state holders](https://developer.android.com/develop/ui/compose/state-hoisting),
[Compose animation](https://developer.android.com/develop/ui/compose/animation/quick-guide),
[adaptive layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-different-display-sizes),
[accessibility and 48dp touch targets](https://developer.android.com/develop/ui/compose/accessibility/api-defaults),
[Compose window insets](https://developer.android.com/develop/ui/compose/system/insets-ui),
[RTL and bidirectional text](https://developer.android.com/training/basics/supporting-devices/languages#SupportRTL),
[Room migrations](https://developer.android.com/training/data-storage/room/migrating-db-versions),
[Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations),
[coroutine best practices](https://developer.android.com/kotlin/coroutines/coroutines-best-practices),
[Compose state-based text fields](https://developer.android.com/develop/ui/compose/text/migrate-state-based),
[Compose app bars](https://developer.android.com/develop/ui/compose/components/app-bars),
[Compose menus](https://developer.android.com/develop/ui/compose/components/menu),
[Compose progress indicators](https://developer.android.com/develop/ui/compose/components/progress),
[Preferences DataStore](https://developer.android.com/topic/libraries/architecture/datastore),
[Compose focus management](https://developer.android.com/develop/ui/compose/touch-input/focus),
[unsafe URI loading prevention](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading), and
[network battery/performance practices](https://developer.android.com/develop/connectivity/network-ops/network-access-optimization).

## Interaction model

`BrowserOmniboxMode` is the single state machine for `Collapsed`, in-navigation-bar `Preview`,
full-layer `Search`, and `EditUrl`. The compact bottom bar uses one animated content slot: tapping
Search replaces every action with a centered current-URL field, expanding horizontally from the
former action row. Tapping that URL opens the keyboard-aware Search layer with an empty query and the
current page immediately below it. That current-page row owns the Edit, Copy, and Share actions; Edit
switches the same state-based field to `EditUrl` with the full current URL selected. This avoids two
competing edit affordances in the toolbar. Back, close, successful navigation, and clear behavior have
explicit state transitions. Expanded windows retain the side rail and open the same shared omnibox
layer directly.

On compact windows, users can persist a bottom or top toolbar position through Preferences DataStore.
The bottom option retains the action row and address-preview transition. The top and bottom toolbar
rows use the same adaptive height and touch-target dimensions. The top option keeps Home, a dominant
48dp address surface, New tab, tab count, and overflow in the browser row. Tapping its address surface
opens empty Search directly, preserving the current-page row and its actions below. In the top layout's
overflow, Back, Forward, Bookmark, and Share form the first action row; in the bottom layout they retain
the established final-row placement. Medium and expanded windows continue to use the adaptive side
rail, where moving a compact toolbar to the top would waste horizontal space.

The layer uses logical start/end spacing, auto-mirrored directional icons, stable lazy list
keys/content types, bounded adaptive width, and Material controls with at least 48dp targets.
`adjustResize`, edge-to-edge drawing, and consuming navigation-bar/IME padding on the inner content
keep the surface stable while the keyboard resizes the available results viewport without double
insets. Compose's finite transition APIs respect the system animation scale. WebView state and
transient omnibox input stay separate, so redirects cannot overwrite text while a user is typing.
The editable address uses Compose's state-based `TextFieldState`, which owns text and selection
atomically. External query synchronization only replaces text when the actual string differs, so
selection handles and cursor movement at either edge are not reset by ViewModel emissions.

The design-system typography applies `TextDirection.Content` to every Material text role, so feature
screens do not carry page-specific bidi style copies. App chrome still follows the selected locale,
while each user/web paragraph resolves direction from its first strong Unicode character. This
preserves natural Arabic/Hebrew and Latin/URL direction, including mixed-language content, instead of
incorrectly forcing all text to the app locale. The shared `core.text` first-strong utility is reserved
for directional affordances whose geometry must follow content (such as populate-query arrows);
navigation vectors use Android/Compose auto-mirroring.

## Progress behavior

The page-load indicator is determinate and belongs to the browser toolbar edge: above a bottom toolbar,
below a top toolbar, and above content beside the adaptive rail. It is created only by a main-frame
`onPageStarted`, ignores unsolicited or background-tab Chromium progress callbacks, advances
monotonically in bounded 5% steps, and disappears on completion, failure, consumed navigation, or tab
attachment. Zero-percent state is intentionally not rendered, avoiding an idle track that resembles a
divider. Values animate briefly without retaining a previous page's percentage.

Browser overflow uses the shared Material 3 menu implementation. Top and rail triggers use
`DropdownMenu` automatic placement; the bottom toolbar uses the official `DropdownMenuPopup` and its
public `DropdownMenuPopupPositionProvider` extension point to align the popup's bottom edge directly
to the trigger's top edge. This avoids the generic provider's 48dp window-edge fallback gap when the
anchor itself is in a bottom app bar. Material still owns popup focus, dismissal, and menu motion; the
small provider owns only above-anchor edge alignment, safe horizontal clamping, RTL start/end, and
the matching transform origin.

Top and bottom address surfaces share one `BrowserAddressPill`. Both render the current site's actual
`SiteFavicon` (including the process-only private favicon path), content-directed URL text, private
colors, shape, size, and click semantics. Only label compaction differs between the top toolbar and
the expanded bottom address preview.

Autocomplete is intentionally not represented by a linear progress line. Suggestions are incremental,
cancellable, and failure-tolerant; a flashing indicator beneath the editable field interfered with
selection handles and incorrectly implied page navigation.

## Suggestions and history

The ViewModel debounces input by 220ms and uses cancellation-aware `mapLatest`. Room projects only
small bounded result sets for local website matches and visit-frequency ranking. The empty-query
screen presents up to eight host-deduplicated frequent sites in a horizontal row without a section
title, followed by search history. The ranking query and search-history query run concurrently on the
ViewModel scope while Room dispatches database work off the main thread.

Search terms are stored separately in the `search_history` Room table, so browsing records never
become search-query rows. Repeating a normalized query moves it to the top; retention and result
counts are bounded. Matching prior searches remain available offline and are shown before remote
suggestions.

Each prior-query row uses a history leading icon and a 48dp populate action. The populate action
updates the existing focused field without navigating. Its Material Rounded diagonal arrow is mirrored
from the query's first strong Unicode character—not from the app locale—so Arabic/Hebrew and Latin
queries point in their natural direction, including neutral prefixes and mixed-language text.

## Favicons

A shared `FaviconRepository` and `SiteFavicon` renderer replace duplicated `/favicon.ico` logic in
history, bookmarks, and omnibox results. Following the
[`WebChromeClient.onReceivedIcon`](https://developer.android.com/reference/android/webkit/WebChromeClient#onReceivedIcon(android.webkit.WebView,%20android.graphics.Bitmap))
callback and [Google's favicon guidance](https://developers.google.com/search/docs/appearance/favicon-in-search),
icons discovered by Chromium—including declared `<link rel="icon">` resources—are bounded,
atomically persisted by origin, and reused throughout the app. Unvisited sites make one conventional
same-origin `/favicon.ico` attempt. Coil supplies decoded-memory and HTTP disk caches; process-level
failure memory prevents request loops during lazy-list recomposition. Loading, invalid URLs, failed
decodes, and sites without icons retain a stable Material Rounded globe fallback. Cache file size,
image dimensions, age, and entry count are bounded, and URL credentials are never copied into fallback
requests.

Database schemas are exported and every schema change receives a new Room version and explicit
migration. Version 6 intentionally represents the original unindexed `search_history` schema;
version 7 adds its recency index through `MIGRATION_6_7`. This preserves already-installed version-6
databases and follows Room's requirement that an entity/index change must not reuse an existing
schema version.

Google autocomplete uses Google's HTTPS `suggestqueries.google.com` response with a three-second
call timeout, a 64KiB response cap, bounded query/result sizes, cancellation propagation, and no
cookies. Google does not publish a stability contract for this autocomplete endpoint, so it is kept
behind `SearchSuggestionRepository`; any timeout, protocol change, malformed response, or offline
state degrades silently to local history. Replacing it with a contracted provider does not affect the
ViewModel or UI.
