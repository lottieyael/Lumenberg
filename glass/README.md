# Lumenberg Glass

A separate Android launcher with a stable side dock and a space for widgets.
Application ID: `dev.lumenberg.glass`. It installs alongside Lumenberg.

## The dock

Four fixed app slots sit beside the lower part of the screen, within thumb reach.
Phone, messages, browser and camera are suggested when available. Slots keep their
positions. Long-press a slot to choose its app; an empty slot opens the picker.
The separated nine-dot button opens the full app menu. Move the dock to either side
from the three-dot menu.

## The space

- Built-in clock, date, battery and editable note tiles work locally.
- Add real widgets from installed apps using **Add widget**.
- Long-press a tile to select **Small square**, **Large square**, or **Wide**.
- Both square sizes preserve an exact 1:1 footprint on different screen widths.
- **Arrange widgets** exposes size, move and remove controls. Drag a tile onto
  another to reorder it.
- Widget binding and configuration use Android's permission and setup activities.
  Cancelling setup releases the allocated widget ID.

The app menu browses installed apps and filters names locally. Long-press an app
to pin it to a specific dock slot or open Android's app info.

## Glass

Cards sample a cached, softened copy of the selected wallpaper, with a slight optical
magnification, translucent shading, a gradient glaze and specular edges. Text and icons
remain sharp. The backdrop is recomputed when its size or wallpaper changes, rather
than continuously. The built-in lavender ribbon is drawn locally.

Choose a photo through Android's document picker. Persistent access is limited to that
image. No broad storage permission is requested.

## Build

JDK 17, Android SDK platform 35 and build-tools 35.0.0 are required.
The app uses Android framework views and has no third-party runtime dependencies.

```sh
ANDROID_SDK_ROOT=/path/to/android-sdk bash glass/build.sh
```

Run this from the repository root. The signed preview APK is written to
`glass/build/Lumenberg-Glass-0.1.0.apk`. The preview signing key is kept in the
ignored build folder; retain it for compatible future preview updates. For distribution,
use your own signing configuration. The Gradle project can also be opened separately
in Android Studio using the `glass/` folder.

The independent Gradle wrapper uses Gradle 8.11.1:

```sh
cd glass
./gradlew assembleDebug
```

```sh
javac -d glass/build/test glass/app/src/main/java/dev/lumenberg/glass/GridPlanner.java glass/tests/GridPlannerTest.java
java -cp glass/build/test GridPlannerTest
```

## Scope

No assistant, model provider, network access, microphone or notification listener.
Installed apps and widgets currently come from the primary user; work profiles are
not included. Third-party widgets control their own content and backgrounds. Their
providers may not adapt well to every square size, even though the host reports the
exact footprint to Android.

MIT; covered by the repository's licence.
