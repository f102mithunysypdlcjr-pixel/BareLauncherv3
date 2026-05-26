# Changelog

All notable changes to BareLauncher land here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] — 2026-05-26

First public-release-ready cut. Production-readiness pass: SDK 36, real
instrumentation CI, performance-preserving split of the activity, crash
sink, and a hardened release pipeline.

### Added

- **API 36 (Android 16) support.** `compileSdk` and `targetSdk` both
  bumped to 36. AGP 8.10.1 already supports compiling against API 36
  per the [release notes](https://developer.android.com/build/releases/agp-8-10-0-release-notes).
- **Instrumentation smoke test on a real Android emulator.**
  `LauncherSmokeTest` now actually runs as part of CI on every push and
  every PR, against an API 30 (minSdk) AVD with hardware acceleration
  (KVM). AVD snapshots are cached so steady-state runs take ~2 minutes;
  cold runs ~5–7 min. The release `build` job depends on the new
  `instrumentation` job, so a smoke-test failure blocks the signed APK
  from ever being produced.
- **Zero-dependency crash logger** (`CrashLogger`). Hooks
  `Thread.setDefaultUncaughtExceptionHandler` on activity create and
  appends timestamped traces to `<internalFiles>/crash.log` (rotated
  at 32 KB). Same trace is sent to logcat at `ERROR`. Useful for
  triaging issues from a remote user without depending on a
  third-party crash service.
- **`KeymapStore` JVM unit tests.** 31 new tests (`KeymapStoreTest`)
  exercising the parse/serialise round-trip, corrupt-input recovery,
  curated-keycode filtering, and edge cases (null, empty, trailing
  comma, double comma, mixed valid/invalid).
- **`CHANGELOG.md`** — this file.

### Changed

- **`LauncherActivity` split into focused helpers.** Reduced from 4366
  to ~3990 lines (-380), with the rest moved to dedicated files:
  - `IconRenderer` — pure-static icon-bitmap pipeline (drawable
    rasterisation, transparent-background detection, white-plate
    application, circle clipping). Owns its `ThreadLocal` scratch
    buffers and `Paint` instances. Allocation hygiene unchanged.
  - `AppleStyle` — static helpers for the toolbar pill button style
    (`applyApplePillStyle`, paint factories for stroke / fill / idle /
    focused / rim).
  - `KeymapStore` — pure-Java parse / serialise helpers for the
    persisted keymap and hidden-apps strings, exposed via a visitor /
    array-pair API so they can be exercised in JVM unit tests (no
    Robolectric, no Android-framework stubs).
  - `ClockFormatter` — encapsulates the per-tick allocation hygiene
    (Calendar reuse, char buffer, SpannableStringBuilder, AM/PM
    spans). Activity now only deals with scheduling / TextView wiring.
  - `WallpaperController` — full wallpaper state machine (executor,
    loading guards, decode, sub-sampling, cross-fade, recycle-on-
    destroy). Activity now forwards four lifecycle / interaction calls.
- **`RecyclingShelfView` and `CellView` stay inner classes.** Hot
  paths (focus / scroll / `onDraw` / key dispatch) keep direct field
  access through the implicit outer reference — no extra indirection,
  no virtual dispatch, no allocation per frame.
- **`OldTargetApi` lint check** stays a warning (not ignored) so a
  future Android release that we miss bumping to becomes a loud
  reminder during CI lint.
- **`versionCode` 1 → 2, `versionName` 1.0.0 → 1.1.0.**

### Fixed

- Hidden-but-uninstalled packages are now garbage-collected from the
  persisted hidden-apps set the next time `loadApps()` runs, even when
  the in-memory `ArraySet` was the only place that knew about them
  (the path was already correct in code; the test coverage extracted
  for `KeymapStore` confirms the semantics).

### Performance

- Wallpaper decode no longer keeps the icon executor's threads warm —
  separate single-threaded executor lives inside `WallpaperController`.
- No regression in any hot path: shelf scroll, focus, key dispatch,
  `onDraw`, icon delivery, package-broadcast refresh all execute the
  same code as before with identical allocation profile. Static
  helpers were moved across files but every call inlines through the
  JIT exactly as it did inside the activity.

### Deferred to a follow-up

- `KeymapOverlay` extraction (~1000 lines covering the slot list,
  app picker, hide manager, key-routing, animations, and chip
  state). Too much regression surface for this PR alongside the SDK
  bump and CI changes; will land in 1.2.0 with the same
  performance-preservation contract.

### Release engineering

- **Public-release polish at the repository root.** Added `README.md`
  (sideload install / make-default / build-from-source / release
  process) and `LICENSE` (Apache License 2.0) so the project is
  legally and practically distributable. Apache 2.0 chosen over MIT
  for its explicit patent grant and contribution clauses, which
  matter for a launcher that interacts with arbitrary installed
  apps and may attract third-party patches.
- **ABI cap** for the shipped APK: `armeabi-v7a` + `arm64-v8a`.
  The launcher is pure Java with zero native dependencies, so the
  produced APK has no `lib/` folder and a single binary already
  runs on every Android ABI; the new `ndk.abiFilters` block is a
  defensive cap so any future transitive native dependency cannot
  silently bloat the APK with x86 / x86_64 binaries we do not test
  against.
- **Tag-triggered GitHub Release pipeline.** Pushing a `v<semver>` tag
  (e.g. `v1.1.0`) now runs the full quality gate (lint + unit tests
  + emulator smoke test), assembles and signs the release APK,
  renames it to `BareLauncher-<version>.apk`, and creates a GitHub
  Release whose body is the matching `CHANGELOG.md` section. Uses
  the `gh` CLI preinstalled on the runner — no third-party
  "create-release" action, no extra supply-chain surface.

## [1.0.0] — initial public revision

- Single-activity, zero-runtime-dependency Android TV / phone HOME
  launcher.
- Recycling shelf with focus ring, reorder mode, context menu (move /
  app info / uninstall).
- Remote-key shortcut mapping (Red / Green / Yellow / Blue / Menu /
  Subtitle).
- Hide-apps manager.
- Wallpaper picker (system or user image).
- 10 unit tests covering the persisted app order.
- CI quality gate: `lintDebug` + `testDebugUnitTest` on every PR;
  signed `assembleRelease` on `main`.
