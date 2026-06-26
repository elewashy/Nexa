# Changelog

## 1.0.1 - 2026-06-27

### Changed

- Incremented the Android app version after the initial public release.
- Aligned the browser refresh affordance more closely with the official Material 3 Expressive `LoadingIndicator` defaults.

## 1.0.0 - 2026-06-26

Initial public release of Nexa.

### Added

- Native Android browser built with Kotlin and Jetpack Compose.
- WebView browsing experience with Material 3 UI.
- Built-in ad blocking with Nexa-maintained filters and external providers.
- Trusted-link filtering for safer navigation flows.
- Runtime browser resource updates from GitHub-hosted `web_resources/` files.
- HTTP cache validation for browser filters and JavaScript resources.
- Segmented download engine for large files.
- APK download and install support.
- GitHub-based update checks.
- CI workflow for debug builds, lint, and unit tests.
- Signed release workflow for APK publishing.
