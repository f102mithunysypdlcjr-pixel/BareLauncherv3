# BareLauncher

A minimal, zero-dependency Android HOME launcher built for both phones and
Android TV. One activity, pure Android SDK, no third-party runtime
dependencies in the shipped APK.

![BareLauncher banner](LauncherV15/app/src/main/res/drawable/banner.png)

## Highlights

- **Recycling app shelf** with focus ring, reorder mode, and a context
  menu (move / app info / uninstall).
- **Remote-key shortcuts** — Red, Green, Yellow, Blue, Menu, and
  Subtitle keys can each be bound to launch any installed app.
- **Hide-apps manager** — keep clutter off the shelf without
  uninstalling.
- **Wallpaper picker** — system wallpaper or a user-selected image,
  with a cross-fade transition.
- **Built-in crash logger** — uncaught exceptions are written to
  `<internalFiles>/crash.log` (rotated at 32 KB) so issues can be
  triaged from a remote device without a third-party crash service.
- **Zero runtime dependencies** — the production APK ships only
  Android SDK code; no AndroidX, no Kotlin runtime, no Material.

## Compatibility

| | |
|---|---|
| **Min Android** | 11 (API 30) |
| **Target Android** | 16 (API 36) |
| **Form factors** | Phone, tablet, Android TV / Leanback |
| **ABIs** | Universal APK, runs on `arm64-v8a` and `armeabi-v7a` |

The launcher is pure Java, so a single APK runs on every supported ABI.

## Install (sideload)

1. Download `BareLauncher-<version>.apk` from the
   [latest release](https://github.com/f102mithunysypdlcjr-pixel/laughing-engine-9-3rd/releases/latest).
2. Transfer the APK to your device. On Android TV the simplest path is
   `adb install BareLauncher-<version>.apk` from a host PC.
3. On the device, allow installation from your transfer source if
   prompted.
4. Open the installed app once so Android registers it as a launcher
   candidate.

### Make BareLauncher the default HOME

- **Phone / tablet:** Settings → Apps → Default apps → Home app →
  Bare Launcher.
- **Android TV (Google TV):** Settings → Apps → See all apps →
  Bare Launcher → Open. The first time you press the Home button after
  that, Android prompts to pick a default — choose Bare Launcher.
- **Stock Android TV (Leanback):** Settings → Device Preferences →
  Home screen → choose Bare Launcher. On ROMs without that menu, use
  `adb shell cmd package set-home-activity com.bare.launcher/.LauncherActivity`.

To revert, repeat the same flow and pick the original launcher.

## Build from source

Requirements:

- JDK 21
- Gradle 8.14.1 (or use the wrapper generated on first run)
- Android SDK with platforms `android-30` and `android-36`

```bash
git clone https://github.com/f102mithunysypdlcjr-pixel/laughing-engine-9-3rd.git
cd laughing-engine-9-3rd/LauncherV15
gradle wrapper --gradle-version 8.14.1
./gradlew :app:assembleDebug
```

The unsigned debug APK lands at
`LauncherV15/app/build/outputs/apk/debug/app-debug.apk`.

### Release build (signed)

Drop your release keystore at `LauncherV15/app/release.jks` and pass the
signing credentials as Gradle properties:

```bash
./gradlew :app:assembleRelease \
  -Pandroid.injected.signing.store.file=$PWD/app/release.jks \
  -Pandroid.injected.signing.store.password=<storePassword> \
  -Pandroid.injected.signing.key.alias=<alias> \
  -Pandroid.injected.signing.key.password=<keyPassword> \
  -Pandroid.injected.signing.store.type=JKS
```

The signed, R8-minified, resource-shrunk APK lands at
`LauncherV15/app/build/outputs/apk/release/`.

CI does the same thing on every push to `main`; tagged commits also
publish a GitHub Release. See `.github/workflows/main.yml`.

### Tests

```bash
./gradlew :app:testDebugUnitTest          # JVM unit tests
./gradlew :app:lintDebug                  # Android lint
./gradlew :app:connectedDebugAndroidTest  # instrumentation (needs emulator/device)
```

## Releasing a new version

1. Bump `versionCode` and `versionName` in
   `LauncherV15/app/build.gradle.kts`.
2. Add a `## [<version>] — <date>` section to `CHANGELOG.md`.
3. Merge to `main`. The CI quality gate (lint + unit tests +
   instrumentation smoke test) runs automatically.
4. Tag the merge commit and push:

   ```bash
   git tag v<version>
   git push origin v<version>
   ```

5. The release workflow assembles the signed APK, renames it to
   `BareLauncher-<version>.apk`, and attaches it to a new GitHub
   Release whose body is the matching `CHANGELOG.md` section.

## Project layout

```
LauncherV15/
  app/
    src/main/java/com/bare/launcher/   # production sources
    src/test/java/...                  # JVM unit tests
    src/androidTest/java/...           # instrumentation smoke test
    src/main/res/                      # drawables, strings, theme
    proguard-rules.pro                 # R8 keep / strip rules
    lint.xml                           # lint quality gate config
.github/workflows/main.yml             # quality gate + release pipeline
CHANGELOG.md
LICENSE
```

## License

[Apache License 2.0](LICENSE).

## Changelog

See [CHANGELOG.md](CHANGELOG.md).
