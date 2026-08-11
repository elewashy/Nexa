# Changelog

## 1.2.0 - 2026-08-11

### Added

- Support for shared links that redirect to the actual post, including Threads share links and Twitter/X short (t.co) links.
- Unit tests covering share-link label handling and quality formatting.
- New About page sections: Contributors, Open Source Licenses, and a link to the developer's GitHub profile.
- GitHub icon on the About page next to the Telegram icon; Issues & Feedback now opens GitHub.
- In-browser video download button: appears automatically on pages from supported video platforms and opens the download sheet; long-press turns it into a close button to hide it for the current page, and it can be disabled entirely in Settings (General → Downloads).
- "Continue anyway" option on the no-internet screen, so cached pages and settings stay reachable offline.

### Improved

- Faster video extraction: redirect links are resolved only when needed, and shared-link handling no longer adds extra requests for regular links.
- Rebuilt the video extraction pipeline for reliability and maintainability, with clearer error messages when a video can't be found.
- TikTok downloads now use a single faster service instead of scraping a third-party fallback site.
- The Filters status on the Updates page now refreshes automatically after every filter check, including the one that runs at app startup.
- Download file names now use the correct platform name even when the link was shared as a short or redirect link.
- GitHub releases now publish SHA-256 checksum files, and in-app updates verify the downloaded APK against them before offering installation.
- The app can now start and browse without an internet connection; filters update in the background when connectivity returns.
- More reliable downloads: resume state now survives app restarts, and unfinished files are kept hidden until complete.
- Downloads on Android 10 devices now check the storage permission correctly, with clear guidance instead of unexplained failures.
- When the system pauses a long background download, you now get a notification that lets you resume it.
- Failed page loads show a clear error screen with retry and back-to-home options, without covering pages that loaded fine.
- Update checks now handle GitHub rate limits with a clear message.
- Updated Compose Material 3, Material Kolor, Compose Shimmer, and KSP to their latest versions.

### Fixed

- Fixed video extraction failing for shared Threads links.
- Fixed unsupported-platform errors for Twitter/X links shared as t.co short links.
- Fixed the large-screen/tablet browser navigation differing from the phone layout: the side rail now uses the same actions, colors, and sizing as the bottom bar, with icon labels removed.
- Fixed the AdBlock filter update notification appearing on every launch even when nothing changed; it now only shows while updated lists are actually being downloaded and applied.
- Fixed website file uploads not working in the browser, including multi-file uploads.
- Fixed fullscreen video handling: back now exits fullscreen correctly, switching videos works, and portrait videos are no longer force-rotated.
- Fixed the address bar accepting unsafe URL schemes (javascript:, data:, file:).
- Fixed downloads restarting from zero or corrupting after the app was closed mid-download.
- Fixed tapping a download notification opening the browser instead of the Downloads page.

## 1.1.0 - 2026-07-18

### Added

- Added adaptive browser navigation: phones use the bottom controls, while tablets, foldables, Chromebooks, and larger windows use a left-side navigation rail.

### Improved

- Improved the language search experience with the latest Material 3 full-screen search behavior.
- Unified browser navigation styling and accessibility across compact and large-screen layouts.

### Fixed

- Fixed a startup crash related to restoring browser address-field state.

## 1.0.3 - 2026-06-29

### Improved

- Redesigned the download quality selector with a cleaner Material 3 layout.
- Added a smoother shimmer loading state while quality options are prepared.
- Improved TikTok download quality detection and labeling.
- Replaced download delete confirmations with a faster undo option.
- Improved browser download feedback with a clearer snackbar and shortcut to Downloads.
- Refined browser and downloads overflow menus for a more consistent Material 3 experience.

## 1.0.2 - 2026-06-27

### Fixed

- Fixed Pull-to-Refresh indicator visibility after returning to the browser.

## 1.0.1 - 2026-06-27

### Improved

- Improved Pull-to-Refresh behavior and visual feedback.
- Prevented Pull-to-Refresh gestures from affecting page content underneath.

## 1.0.0 - 2026-06-26

Initial public release of Nexa.

### Added

- Fast browser experience with a clean native interface.
- Built-in ad blocking and safer link handling.
- Reliable file downloads with support for large files.
- App update checks through GitHub Releases.
- Runtime updates for browser protection resources.
