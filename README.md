# Lumenberg

An experimental, open-source Android launcher built around three verbs:

**Ask. Glance. Launch.**

It deliberately avoids pages, folders, and a permanent icon grid.

[![CI](https://github.com/lottieyael/Lumenberg/actions/workflows/ci.yml/badge.svg)](https://github.com/lottieyael/Lumenberg/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## v0.1

- One home screen over the user's existing wallpaper.
- Material You colors adapt to the wallpaper on Android 12+.
- Real Android widgets hosted directly with `AppWidgetHost`.
- One bottom command bar:
  - type an app name to launch it;
  - otherwise send the prompt to the configured AI;
  - tap the mic to use Android speech recognition;
  - tap the apps icon for the full searchable app list.
- Local app-use ranking, with no analytics or network telemetry.
- OpenAI-compatible `POST /v1/chat/completions` provider configuration.
- A short first-run onboarding flow.

## Install

Download the APK from [Releases](../../releases) and install it, then press Home and pick Lumenberg:

```sh
adb install Lumenberg-0.1.0.apk
```

## Build

Requirements:

- JDK 17
- Android SDK 37 (the Gradle wrapper and platform tooling are included)

```sh
./gradlew testDebugUnitTest assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`.

## Releases

Push a tag like `v0.1.0` and CI builds and publishes a GitHub Release with the APK attached. Release artifacts are signed with the standard debug key so they install out of the box — swap in your own signing config in `app/build.gradle.kts` before distributing.

## AI configuration

Open Settings in Lumenberg and provide:

- endpoint, e.g. an OpenAI-compatible `/v1/chat/completions` URL;
- model name;
- optional bearer token.

The launcher has no bundled API key and no backend. Credentials are currently stored in app-private SharedPreferences with Android backup disabled. This is acceptable for an experimental build, not hardened secret storage.

## Known v0 limitations

- Current-user apps only. Work/private profiles need explicit profile UI and Android 15+ private-space handling.
- Widgets use a fixed 190dp host height. Proper resize negotiation is next.
- No pinned shortcuts, notification dots, icon packs, gestures, or backup/import yet.
- AI conversation history is in memory only and disappears when the process dies.
- The AI protocol assumes the common `choices[0].message.content` chat-completions response shape.
- Voice input uses whatever speech recognizer Android resolves for `RecognizerIntent`; there is no accessibility-service dependency.
- OEM launcher behavior, especially HyperOS, needs device testing.

## Philosophy

Lumenberg should remain boring to maintain. Platform APIs first, very few dependencies, no cloud service owned by the launcher, and no feature whose maintenance cost is larger than its daily utility.

## License

MIT.
