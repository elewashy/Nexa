# Changelog

All notable changes to Nexa are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/2.0.0/), and this project follows [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.0] - Unreleased

### Added

- **Ad blocking:** Added uBlock Origin–compatible scriptlets, redirect resources, element hiding (including procedural filters), and `$popup` blocking.
- **Ad blocking:** Added an Ad blocker page, opened directly from the browser's More options menu or from Settings, with the ad blocker's status, an on/off switch, filter updates (update now, last and next check, and the automatic update interval), and blocking statistics: requests, pop-ups, and pages blocked, tracking parameters removed, estimated data saved, a 7-day chart, and the most blocked domains.
- **Ad blocking:** Choose which filter lists to use from a catalog grouped by purpose (ads, privacy, malware, annoyances, regions), see each list's status and rule count, and import any uBlock Origin or Adblock Plus list by its address.
- **Ad blocking:** Added custom rules, like uBlock Origin's "My filters": block or allow an address or domain, or write any supported filter. Each rule can be edited, turned off, or deleted with undo, and follows the same precedence as list rules, including exceptions, `$important`, and `$badfilter`.
- **Ad blocking:** Ad blocking can now be turned off for a site, and its subdomains, from the More options menu, which also shows how much was blocked on the current page. Every site setting, including element hiding and pop-up blocking per site, can be managed from the Ad blocker page without revisiting the site.
- **Ad blocking:** Added the remaining uBlock Origin scriptlets used by the default lists, including response pruning for XML, HLS, and DASH ads, the `json-edit` family, and safeguards against ClickFix-style clipboard attacks.
- **Ad blocking:** Added support for `$removeparam` on page loads and `$csp` policies, generic `#@#` exceptions, uBlock Origin's `site>>` and regex hostname syntax, and the `:others()`, `:shadow()`, `:matches-prop()`, and `:watch-attr()` operators.
- **Ad blocking:** Added the remaining uBlock Origin replacement resources.
- **Browser:** Pages opened in a new tab can now close themselves, as in Chrome.
- **Navigation:** Back gestures now preview the previous page on Android 14 and later, and a quick swipe finishes immediately instead of replaying a long animation. Backing out of the browser now plays the system's back-to-home animation where Android supports it (Android 15 and later) and keeps your tabs ready for an instant return.
- **Share:** Images can now be downloaded from shared posts — Instagram posts and carousels, TikTok photo posts, X/Twitter tweets, Threads posts, and Facebook photos. When a post has several images, choose which ones to download.

### Changed

- **Startup:** The app now opens straight into the browser. The loading screen and the no-internet screen are gone; the system splash screen shows only while your tabs are restored, and the update check runs in the background after launch.
- **Onboarding:** The first-launch onboarding is redesigned: grant storage, notification, and app-install access (or skip them for now), pick a search engine, and open the Theme and navigation bar pages to set them up, with Skip on every page. It appears only on the first launch; if you already completed it, you won't see it again.
- **Permissions:** Storage access and notifications are now also requested the first time you download something, so skipping them during onboarding never blocks a download.
- **Bookmarks:** The chosen order and layout are now remembered.
- **Navigation:** The open pages, an address bar you were typing in, bookmark and history searches, the open bookmark folder, and selected tabs are now restored after Android closes the app in the background.
- **Ad blocking:** Filters load several times faster at startup by restoring the last compiled filter engine instead of recompiling the lists.
- **Ad blocking:** Changes made by the ad blocker to page functions are now hidden from page scripts, so sites can't detect them.
- **Updates:** Settings → Updates now only checks for app updates; filter updates are managed from the Ad blocker page.
- **Ad blocking:** Replaced host-only blocking with a full filter engine that understands resource types, `domain=` and party options, exception rules, `$important`, and `$badfilter`, so filters block only the requests they target.
- **Ad blocking:** The default filter lists now match uBlock Origin's (uBlock filters, badware, privacy, quick fixes, unbreak, EasyList, EasyPrivacy, Peter Lowe's list), plus Liste AR when the app language is Arabic.
- **Ad blocking:** Filter updates use conditional requests, verify downloads before replacing them, and keep the last working filters when an update fails.
- **Browser:** Links and pop-ups that open a new window now open in a new tab instead of replacing the current page.
- **Tabs:** Closing a tab that was opened by another page now returns to the tab that opened it.
- **Tabs:** Undoing a tab close now restores its position, pin state, and back/forward history.
- **Browser:** The navigation bar no longer hides itself on YouTube; it now only hides while a video plays in full screen, as on every other site.
- **Share:** X/Twitter videos now download straight from X in seconds instead of going through a third-party site, which could take 40 seconds and then fail.
- **Share:** Extraction reads only the part of each page it needs and stops, downloading and keeping in memory a fraction of what it did before, and closing the share sheet now cancels any extraction still in progress.
- **Share:** A failed extraction now says why: no connection, a post without downloadable media, or an unsupported link.

### Fixed

- **Downloads:** Fixed downloads on Android 10, where storage access could never be granted.
- **Browser:** Returning to the app on another page (Settings, Downloads, …) no longer resumes the hidden web page in the background.
- **Browser:** The download button now appears only on pages with downloadable media — a specific video, reel, or post, and for tweets, Threads posts, and Facebook posts only when the post itself shows a video or image — instead of on every page of a supported site.
- **Share:** Instagram and Threads posts now download the shared post's own media instead of a video from a related post on the same page.
- **Browser:** The download button now appears on X/Twitter posts that have a video or photo; the post's media is checked with X directly, and the page check now also works when you're not signed in to X.
- **Share:** Fixed Instagram links that failed to extract: Instagram's current post format, `instagram.com/share/` links, and posts where Instagram serves a page without post data are now handled, and carousel photos download at full resolution.
- **Share:** Threads text posts with a link preview no longer offer the preview image or another post's video.
- **Share:** Fixed YouTube downloads, which had stopped working after the download service changed how it signs and encrypts its responses. Videos the service already has ready now show up in under a second and download directly, without waiting for a conversion.
- **Ad blocking:** Blocked pop-ups from embedded video players are now closed silently instead of replacing the page with the block page, which caused a reload loop when going back.
- **Ad blocking:** Pop-up ads opened by embedded players to unrelated sites are now blocked even when their rotating domains are not yet in any filter list.
- **Ad blocking:** Pop-up ads opened by a page from a tap on a non-link element (as mycima.bid does) are now blocked when they lead to another site, while legitimate same-site pop-ups and taps on real links keep opening.
- **Ad blocking:** Fixed YouTube videos not playing: requests whose type WebView cannot report (such as YouTube's video stream uploads) are no longer matched by filters meant for other request types.
- **Ad blocking:** Element-hiding styles now survive pages that replace `document.adoptedStyleSheets`.
- **Ad blocking:** Fixed embedded video players such as VideoTube and UpDown not loading: filters that guard built-in page functions (such as `addEventListener` or `document.documentElement`) against ad scripts no longer hide those functions from the rest of the page.
- **Tabs:** Reopening the app now restores every tab's complete back and forward history instead of only its last page.
- **Tabs:** Pressing Back on the first page of a tab opened by another page now closes it and returns to the tab that opened it, instead of exiting the app.
- **Tabs:** Background tabs unloaded to save memory now keep their history when reopened.

## [1.3.1] - 2026-10-02

### Added

- **Search:** Added a search engine setting with Google, Bing, DuckDuckGo, Yahoo, Brave Search, Startpage, Ecosia, and Qwant.

### Changed

- **Search:** Address-bar searches, suggestions, new tabs, and the Home action now use the selected search engine.
- **History:** Result pages from every supported search engine are now labeled with the search query.
- **About:** Redesigned the About screen with app and developer cards, project links, and community links.

### Fixed

- **Share:** Fixed the quality sheet staying open after tapping Download, which allowed duplicate downloads.

## [1.3.0] - 2026-09-06

### Added

- **Tabs:** Added persistent tabs, ephemeral private tabs, pinning, reordering, search, and multi-select actions.
- **Browser:** Added an omnibox with history, previous searches, frequent sites, and Google suggestions.
- **Bookmarks:** Added searchable bookmarks with folders, custom ordering, and list or visual layouts.
- **History:** Added searchable browsing history grouped by date.
- **Navigation:** Added a top-toolbar option on compact screens.
- **Downloads:** Added configurable layouts, filters, concurrency, speed limits, and rename and share actions.

### Changed

- **Data:** Downloads, tabs, bookmarks, history, and search history now use a migrated Room database.
- **UI:** Improved adaptive layouts, accessibility, Material motion, and bidirectional text handling.
- **Downloads:** Deleting downloads now requires confirmation because the file is permanently removed.

### Fixed

- **Settings:** Refactored persisted settings state so settings screens and theme startup render from a shared DataStore-backed source of truth, avoiding temporary default-value flicker while preferences load.
- **Downloads:** Refactored download notifications to use stable per-download IDs, update existing notifications in place, and keep pause, resume, failure, completion, retry, and lifecycle restoration states synchronized with the persisted download state.
- Fixed tab closing and bookmark state synchronization.
- Fixed private browsing data not always being cleared when the browser closed.
- Fixed excess navigation-bar spacing in the tab overview.

## [1.2.2] - 2026-08-19

### Changed

- Failed downloads now stop retrying when Android prevents the foreground service from starting.
- Corrupt legacy download records are skipped individually instead of discarding all history.

### Fixed

- Fixed download history disappearing after restarting minified builds.
- Fixed resumable progress being discarded when servers temporarily reject probe requests.
- Fixed valid links behind restrictive CDNs or firewalls being reported as unavailable.

## [1.2.1] - 2026-08-11

### Changed

- Added automatic retry with backoff for failed and interrupted downloads.
- Download state is saved immediately for important lifecycle and status changes.

### Fixed

- Fixed completed and paused downloads disappearing after the app was removed from recents.
- Fixed interrupted downloads restarting instead of resuming.
- Fixed downloads waiting for connectivity not resuming when the network returned.

## [1.2.0] - 2026-08-11

### Added

- Added video download actions for supported pages and shared redirect links.
- Added offline startup and a continue-offline option.
- Added contributors, open-source licenses, developer links, and GitHub issue reporting to About.
- Added checksum generation and verification for app updates.
- Added browser file uploads and page-load error recovery.

### Changed

- Improved video extraction, platform detection, download naming, and error messages.
- Improved download persistence, resume behavior, Android 10 storage handling, and background-resume notifications.
- Filter status now refreshes after manual and startup checks.
- Large-screen browser navigation now matches compact-screen controls.

### Fixed

- Fixed extraction from Threads and Twitter/X redirect links.
- Fixed repeated ad-block update notifications.
- Fixed fullscreen video navigation and orientation handling.
- Blocked unsafe `javascript:`, `data:`, and `file:` URLs in the address bar.
- Fixed interrupted segmented downloads restarting or becoming corrupt.
- Fixed download notifications opening the wrong destination.

## [1.1.0] - 2026-07-18

### Added

- Added adaptive browser navigation for tablets, foldables, Chromebooks, and larger windows.

### Changed

- Updated language search and browser navigation to consistent Material 3 behavior.

### Fixed

- Fixed a startup crash while restoring address-field state.

## [1.0.3] - 2026-06-29

### Changed

- Redesigned the download-quality selector and its loading state.
- Improved TikTok quality detection and download labeling.
- Improved browser download feedback and overflow menus.
- Download deletion became undoable instead of requiring confirmation.

## [1.0.2] - 2026-06-27

### Fixed

- Fixed the pull-to-refresh indicator remaining visible after returning to the browser.

## [1.0.1] - 2026-06-27

### Changed

- Improved pull-to-refresh behavior and visual feedback.

### Fixed

- Prevented pull-to-refresh gestures from affecting page content underneath.

## [1.0.0] - 2026-06-26

### Added

- Initial release with browsing, ad blocking, safer link handling, large-file downloads, filter updates, and GitHub release updates.

[1.4.0]: https://github.com/elewashy/Nexa/compare/v1.3.1...HEAD
[1.3.1]: https://github.com/elewashy/Nexa/compare/v1.3.0...v1.3.1
[1.3.0]: https://github.com/elewashy/Nexa/compare/v1.2.2...v1.3.0
[1.2.2]: https://github.com/elewashy/Nexa/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/elewashy/Nexa/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/elewashy/Nexa/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/elewashy/Nexa/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/elewashy/Nexa/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/elewashy/Nexa/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/elewashy/Nexa/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/elewashy/Nexa/releases/tag/v1.0.0
