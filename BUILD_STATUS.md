# Build status

## Verified in this repository

- Full Gradle build on JDK 17 / Gradle 9.5.0 / AGP 9.3.1 / compileSdk 37:
  `testDebugUnitTest`, `assembleDebug`, `assembleRelease` (incl. `lintVitalRelease`) pass.
- `SearchRanker` unit test passes.
- Toolchain note: AGP 9.3.1 embeds Kotlin 2.2.10, so the Compose compiler plugin is pinned
  to 2.2.10 (the original source bundle pinned 2.3.21, which does not match AGP's built-in Kotlin).
- CI builds a debug APK on every push and publishes a GitHub Release on `v*` tags.

## UNVERIFIED

- Runtime behavior on Xiaomi/HyperOS and other OEM launchers.
- Widget sizing across third-party widgets; v0 deliberately uses a fixed 190dp host height.
- Android work/private-profile behavior; v0 lists the current user only.

## Falsifying checks to run locally

1. Install on an API 28 emulator and an API 37 emulator.
2. Set Lumenberg as Home, press Home from another app, and verify Lumenberg receives the action.
3. Add one configurable and one non-configurable widget, reboot/relaunch, and verify both persist and update.
4. Search and launch an installed app; verify usage ranking changes only locally.
5. Configure a test OpenAI-compatible endpoint and verify a natural-language query returns `choices[0].message.content`.
6. Trigger voice input on a device with a recognizer and verify the transcript lands in the command bar without any accessibility permission.
