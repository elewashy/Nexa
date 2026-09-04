# Nexa

[![CI](https://img.shields.io/github/actions/workflow/status/elewashy/Nexa/ci.yml?branch=main&label=CI)](https://github.com/elewashy/Nexa/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/elewashy/Nexa?label=release)](https://github.com/elewashy/Nexa/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](https://developer.android.com/about/versions/oreo)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Nexa is an open-source Android browser and download manager built with Kotlin and Jetpack Compose. It combines everyday browsing, private tabs, ad blocking, bookmarks, history, and resumable downloads in one app.

## Features

- Multi-tab browsing with private tabs, tab search, pinning, reordering, and multi-select actions
- Omnibox suggestions from browsing history, search history, frequent sites, and Google autocomplete
- Bookmarks with folders, ordering, search, and list or visual layouts
- Browsing history with search and grouped, undoable deletion
- Built-in ad blocking, safer URL handling, file uploads, and fullscreen media support
- Adaptive Material 3 navigation for phones, tablets, foldables, Chromebooks, and large windows
- Resumable segmented downloads with notifications, automatic retry, concurrency controls, and optional speed limits
- Media-gallery and tabbed-list download layouts, plus rename and share actions
- Video extraction for supported YouTube, Facebook, Instagram, Threads, TikTok, and Twitter/X links
- In-app update checks through GitHub Releases with SHA-256 verification
- English, Arabic, and French interfaces with light, dark, and dynamic color themes

Private tabs use an isolated Android System WebView profile and are removed when the private session ends. Private browsing data is not added to Nexa's history, search history, or favicon cache.

## Install

Download the latest APK and its checksum from [GitHub Releases](https://github.com/elewashy/Nexa/releases/latest). Nexa requires Android 8.0 (API 26) or newer.

Android may ask you to allow installation from the app used to open the APK. Download features also require access to the device's shared storage.

## Build from source

### Requirements

- JDK 17
- Android SDK 37
- Android Studio or the Android SDK command-line tools

```bash
git clone https://github.com/elewashy/Nexa.git
cd Nexa
./gradlew assembleDebug
```

The APK is generated under `app/build/outputs/apk/debug/`.

Run the same primary checks used by CI:

```bash
./gradlew lintDebug testDebugUnitTest
```

Release builds require a local `keystore.properties` file. See [`keystore.properties.example`](keystore.properties.example) for the required fields; never commit signing credentials.

## Architecture

Nexa uses a feature-oriented architecture with Compose UI, lifecycle-aware ViewModels, unidirectional state, repositories, Kotlin coroutines and Flow, and Hilt dependency injection. Room stores downloads, tabs, bookmarks, history, and search history; DataStore stores user preferences. Browser rendering is provided by Android System WebView.

```text
app/src/main/java/   Application code organized into core, feature, and UI packages
app/src/main/res/    Resources and English, Arabic, and French translations
app/src/test/        JVM and Robolectric tests
app/schemas/         Versioned Room database schemas
web_resources/       Browser filter lists and injected scripts
docs/                Focused implementation and behavior notes
.github/workflows/   CI and release automation
```

## Permissions

Nexa uses:

- Network and network-state access for browsing, downloads, suggestions, filters, and update checks
- Notification permission for download and update progress on supported Android versions
- Shared-storage access for files saved to `Downloads/Nexa`; Android 11 and newer use the system's all-files-access setting
- Package-install permission only when the user chooses to install a downloaded APK or app update

Permissions are requested when the related feature needs them. Some download functionality is unavailable without storage access.

## Contributing

Contributions are welcome. Keep pull requests focused, include tests for changed behavior, and run the primary checks before opening a pull request. See the [changelog](CHANGELOG.md) for notable user-facing changes.

## License

Nexa is available under the [MIT License](LICENSE).
