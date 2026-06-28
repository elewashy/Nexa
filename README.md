# Nexa

[![CI](https://img.shields.io/github/actions/workflow/status/elewashy/Nexa/ci.yml?branch=main&label=CI)](https://github.com/elewashy/Nexa/actions/workflows/ci.yml)
[![Latest Version](https://img.shields.io/badge/latest-1.0.3-0A7EA4)](https://github.com/elewashy/Nexa/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](https://developer.android.com/about/versions/oreo)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Nexa is an open-source Android browser and download manager built for fast everyday browsing, cleaner pages, and reliable file downloads.

It combines a modern WebView browser, maintained ad-block resources, trusted-link filtering, GitHub-based updates, and a segmented download engine in a native Kotlin application.

## Download

The latest public build is available from GitHub Releases:

```text
https://github.com/elewashy/Nexa/releases/latest
```

Current app version:

```text
1.0.3
```

Download the release APK from the latest release page, then install it on an Android 8.0+ device. Android may ask you to allow installs from the browser or file manager used to open the APK.

## Key Features

- WebView-based browser with a clean native Android interface
- Built-in ad blocking using Nexa-maintained rules and external filter providers
- Trusted-link filtering for safer navigation flows
- Remote JavaScript injection resources with HTTP cache validation
- Segmented download engine for large files
- APK download and install support
- GitHub-based app update checks
- Material 3 UI built with Jetpack Compose
- Hilt-powered dependency injection
- Localized Android string resources

## How Updates Work

Nexa uses GitHub Releases for application updates. The app checks release metadata, compares the latest available version with the installed version, and can direct users to the release APK.

Browser resources are handled separately from app releases. Filter lists and JavaScript resources live in `web_resources/` and are fetched at runtime with `ETag` and `Last-Modified` validation. This keeps browser resources current without requiring a full app update for every list change.

## Build Requirements

- JDK 17
- Android Studio or Android SDK command-line tools
- Android SDK platform matching `compileSdk = 37`
- Android 8.0+ device or emulator for runtime testing
- Git, Bash, and the included Gradle wrapper

## Build From Source

```bash
git clone https://github.com/elewashy/Nexa.git
cd Nexa
./gradlew assembleDebug
```

The debug APK is generated in:

```text
app/build/outputs/apk/debug/
```

Run the standard local validation suite:

```bash
./gradlew assembleDebug
./gradlew lintDebug
./gradlew testDebugUnitTest
```

## Release Builds

Release builds are signed. Local release builds require a `keystore.properties` file at the repository root. This file is ignored by Git and must never be committed.

Use `keystore.properties.example` as the template:

```properties
storeFile=/absolute/path/to/nexa-release.jks
storePassword=replace-with-keystore-password
keyAlias=replace-with-key-alias
keyPassword=replace-with-key-password
```

Build a signed release APK:

```bash
./gradlew assembleRelease
```

GitHub release builds use repository secrets:

- `SIGNING_KEY`: base64-encoded release keystore
- `SIGNING_KEY_ALIAS`: release key alias
- `SIGNING_KEY_PASSWORD`: release key password
- `SIGNING_STORE_PASSWORD`: release keystore password

The release workflow fails clearly if any signing secret is missing.

## Project Structure

```text
.
├── app/                    Android application module
├── app/src/main/java/      Kotlin source code
├── app/src/main/res/       Android resources and localized strings
├── gradle/                 Version catalog and Gradle wrapper files
├── web_resources/          Browser filters and JavaScript resources
└── .github/workflows/      CI and release automation
```

Main source areas:

- `core/`: shared infrastructure for networking, storage, notifications, localization, dispatchers, and file handling
- `feature/browser/`: browser UI, WebView integration, ad-blocking, trusted links, scripts, and browser resources
- `feature/downloads/`: download models, engine, service integration, and download UI
- `feature/update/`: GitHub release update checks and update state
- `feature/settings/`: settings UI and preferences
- `ui/`: reusable Compose UI, app theme, icons, and layout helpers

## Browser Resources

Nexa-managed browser resources are stored in the repository under `web_resources/` and downloaded by the app when needed:

```text
web_resources/filters/blocklist.txt
web_resources/filters/allowlist.txt
web_resources/scripts/pre_load.js
web_resources/scripts/post_load.js
```

The Android app does not bundle these files as APK assets. `BrowserResourceRepository` is the single source of truth for remote URLs, local cache files, HTTP cache metadata, conditional requests, and atomic writes.

External filter providers are checked on a conservative interval instead of every upstream change. This keeps ad blocking fresh while limiting bandwidth, battery usage, parsing work, and provider traffic.

## Permissions

Nexa is a browser and download manager, so it requests permissions that are tied to browsing, downloads, notifications, and APK installation.

- `INTERNET`: browser, downloads, remote resources, and update checks
- `ACCESS_NETWORK_STATE`: network availability checks
- `POST_NOTIFICATIONS`: download and update notifications on Android 13+
- `REQUEST_INSTALL_PACKAGES`: installing downloaded APKs and app updates
- `MANAGE_EXTERNAL_STORAGE`: segmented downloads to public storage using random-access writes
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`: legacy storage access on older Android versions

## Technology Stack

- Kotlin
- Jetpack Compose
- Material 3
- AndroidX
- Hilt
- OkHttp
- DataStore
- KSP
- JUnit
- GitHub Actions

## Continuous Integration

CI runs on pushes to `main` and pull requests when Android source or build files change. It builds and checks only the debug variant:

```bash
./gradlew assembleDebug
./gradlew lintDebug
./gradlew testDebugUnitTest
```

Changes limited to documentation or `web_resources/` do not trigger CI. Release builds run only from published GitHub Releases or `v*` version tags.

## Contributing

Contributions are welcome. Good pull requests are focused, tested, and easy to review.

Before opening a pull request:

1. Run `./gradlew assembleDebug lintDebug testDebugUnitTest`.
2. Keep changes scoped to one feature or fix.
3. Follow the existing feature-based structure.
4. Do not commit local SDK files, signing keys, APKs, AABs, build outputs, IDE folders, or secrets.
5. Describe user-visible behavior changes clearly.

For large changes, open an issue first so the design can be discussed before implementation.

## Roadmap

- Publish signed GitHub releases
- Strengthen update verification around downloaded APKs
- Expand tests for browser resources, downloads, and update flows
- Continue improving storage behavior for broader Android distribution
- Add contributor documentation for release management and architecture decisions

## Acknowledgements

Nexa is built on the Android open-source ecosystem, including Kotlin, Jetpack Compose, AndroidX, Hilt, OkHttp, and community-maintained filter lists such as EasyList.

## License

Nexa is released under the MIT License. See [LICENSE](LICENSE) for details.
