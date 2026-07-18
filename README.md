# Nexa

[![CI](https://img.shields.io/github/actions/workflow/status/elewashy/Nexa/ci.yml?branch=main&label=CI)](https://github.com/elewashy/Nexa/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/elewashy/Nexa?label=release)](https://github.com/elewashy/Nexa/releases/latest)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84)](https://developer.android.com/about/versions/oreo)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

Nexa is an open-source Android browser and download manager. It provides everyday browsing, ad blocking, safer link handling, and reliable downloads in a native Kotlin app.

## Features

- Browser with Material 3 adaptive layouts for phones, tablets, foldables, and desktop-sized windows
- Ad blocking and trusted-link filtering
- Segmented downloads for large files, with download progress and notifications
- APK downloads and update checks through GitHub Releases
- Localized interface and light/dark themes

## Install

Download the latest APK from the [GitHub Releases page](https://github.com/elewashy/Nexa/releases/latest), then open it on an Android 8.0 or newer device. Android may ask you to allow installation from the app used to open the file.

## Build from source

Requirements: JDK 17, Android SDK 37, and the Android SDK command-line tools or Android Studio.

```bash
git clone https://github.com/elewashy/Nexa.git
cd Nexa
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

Run the local checks with:

```bash
./gradlew lintDebug testDebugUnitTest
```

## Project layout

```text
app/                 Android application
app/src/main/java/   Kotlin source code
app/src/main/res/    Android resources and translations
web_resources/       Browser filter lists and scripts
.github/workflows/   CI and release automation
```

The code is organized by feature: browser, downloads, settings, updates, and shared UI/core modules.

## Permissions

Nexa requests network access for browsing, downloads, filter updates, and update checks. It also requests notifications for download/update status and storage or package-install permissions only for download and APK-install features.

## Contributing

Contributions are welcome. Keep pull requests focused, include relevant tests, and run `./gradlew lintDebug testDebugUnitTest` before opening one. Never commit signing keys, APKs, build outputs, or secrets.

## License

Licensed under the [MIT License](LICENSE).
