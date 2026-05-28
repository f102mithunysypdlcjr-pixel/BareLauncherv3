# BareLauncher

> The launcher Android TV deserves.

A minimal, fast, ad-free, telemetry-free launcher for Android TV, Google
TV, and Fire TV. Built for TV boxes and TVs where every
megabyte of RAM and every dropped frame is felt.

- Pure Java. Zero runtime dependencies in the production APK.
- No ads. No telemetry. No trackers. No analytics. No background services.
- Smooth D-pad navigation, optimised scrolling, instant app launching.
- Hide apps, reorder apps, and remap remote shortcut keys (Red / Green /
  Yellow / Blue / Menu / Subtitle) without an Accessibility Service (works only when you are on home screen).
  

---

## Install

BareLauncher is distributed as a signed `BareLauncher-<version>.apk` on
the [GitHub Releases](../../releases) page.

1. Open the latest release.
2. Download `BareLauncher-<version>.apk` to your TV box (USB, network
   share, or a sideload tool such as Send Files To TV / Downloader).
3. Allow your sideload app to install unknown apps when prompted.
4. Tap the APK and confirm the install.

There is no Play Store / Amazon Appstore listing. Sideload only.

### Set BareLauncher as the default launcher

BareLauncher does not use Accessibility Services to override the stock
launcher. To make it your default home app, use Launcher Manager:

- **Launcher Manager (XDA)** – works on Google TV, Android TV, and Fire
  TV Stick.(Recommended)
 - **OR**
- **Use Button-mapper** - to map home button for bareLauncher.
### Supported platforms

- Android TV / Google TV (API 30 / Android 11 and newer)
- Fire TV Stick & Fire TV Cube
- Phones and tablets (API 30+) — works fine, but the UI is designed for
  D-pad / remote-first navigation.

---
## Performance & privacy posture

- One-activity, programmatic-UI design — no XML inflation overhead, no
  fragments.
- Zero external dependencies in the shipped APK (no AndroidX, no
  Material, no appcompat, no Kotlin runtime).
- Per-tick allocation hygiene in the clock, icon pipeline, and shelf
  recycler so steady-state operation produces almost no GC pressure.
- No background services, no broadcast receivers staying alive when the
  launcher is not the foreground app, no scheduled jobs.
- No internet permission. No telemetry endpoint. No crash reporting SDK
  (a tiny on-device log at `<internalFiles>/crash.log` is the only
  diagnostic sink — see `CrashLogger.java`).
- ProGuard / R8 in full mode, resource shrinking on, ABI cap to
  `armeabi-v7a` + `arm64-v8a`.

The full set of techniques the codebase uses to stay fast on cheap TV
ROMs is documented in `CHANGELOG.md` and inline javadoc on each helper
class (`IconRenderer`, `ClockFormatter`, `WallpaperController`).

---
## Build from source

Requirements:

- JDK 21 (Temurin recommended).
- Android SDK with API 36 platform installed.
- AGP 8.10.1 (provisioned automatically by the Gradle wrapper).

```bash
git clone https://github.com/<owner>/BareLauncherv3.git
cd BareLauncherv3/LauncherV15
gradle wrapper --gradle-version 8.14.1   # one-time, then ./gradlew thereafter
./gradlew :app:assembleDebug             # debug APK
./gradlew :app:assembleRelease           # unsigned release APK
```

Run the JVM unit tests:

```bash
./gradlew :app:testDebugUnitTest
```

Run the instrumentation smoke test (requires an emulator or a connected
device):

```bash
./gradlew :app:connectedDebugAndroidTest
```

Lint:

```bash
./gradlew :app:lintDebug
```

The release pipeline is fully described in
[`.github/workflows/main.yml`](./.github/workflows/main.yml). Tag a
commit `v<semver>` (e.g. `v1.1.0`) to trigger a signed APK build and a
GitHub Release whose body is the matching `CHANGELOG.md` section.

---

## Project structure

```
BareLauncherv3/
├── LauncherV15/                    Gradle project
│   └── app/
│       ├── src/main/java/...       Activity + helpers
│       ├── src/test/java/...       JVM unit tests
│       └── src/androidTest/java/...Smoke test (requires emulator)
├── .github/workflows/main.yml      CI: lint + tests + signed release
├── CHANGELOG.md                    Versioned change log
├── LICENSE                         PolyForm Noncommercial 1.0.0
└── NOTICE.md                       Licensing notes & commercial enquiries
```

---



---

## License

BareLauncher is licensed under the **PolyForm Noncommercial License
1.0.0**. See [LICENSE](./LICENSE) and [NOTICE.md](./NOTICE.md) for
details and commercial-licensing enquiries.

In short:

- ✅ Free to read, build, run, modify, share for personal /
  non-commercial use.
- ❌ Not free to bundle into a commercial product, ship pre-installed
  on hardware sold for profit, or run as part of a paid service
  without a separate licence.

---

## Contributing

Issues and pull requests are welcome. By contributing you agree to the
contributor terms in [NOTICE.md](./NOTICE.md), which let the maintainer
relicense the project (including any future commercial edition) without
needing per-contributor sign-off.

---

## Disclaimer

Button-mapper functionality is intentionally limited to keep the
launcher lightweight without an Accessibility Service. Some keys may not
be remappable on every TV ROM (Android TV / Fire OS / OEM forks vary
in which keycodes are user-overridable).
