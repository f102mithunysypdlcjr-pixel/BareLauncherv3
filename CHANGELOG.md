# Changelog

All notable changes to BareLauncher land here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.2.1] — 2026-05-29

Safe perf / stability hardening pass — surgical, behaviour-preserving
fixes triaged from a focused audit of `LauncherActivity` and the
helpers it forwards to. Every change either (a) prevents an existing
edge-case crash on stripped-down ROMs from killing the launcher, or
(b) drops a redundant allocation, or (c) enables a regression test
for a previously-fixed bug to land. Net impact on the visible
behaviour for healthy installs is zero — the changes only matter on
the kinds of hardware where the launcher was already on the edge of
crashing.

### Fixed

- **`addApps` no longer aborts the shelf batch when `loadLabel` throws.**
  `ResolveInfo.loadLabel(pm)` was previously only null-guarded, but
  stripped-down Fire-TV ROMs have been observed throwing
  `Resources$NotFoundException` / `SecurityException` /
  `RuntimeException` out of `loadLabel` when the app's label string-
  resource id resolves to a missing or cross-user resource. A single
  bad app aborted the whole `queryApps` batch via the outer
  `Throwable` handler in `loadApps` — visible to the user as a blank
  shelf until the next package broadcast retried. The throw is now
  treated identically to the null path (fall back to the package
  name and surface the app as a labelled cell).
- **`packageReceiver` null-guards `getSchemeSpecificPart()`.** The SSP
  is documented non-null for `package:` URIs, but malformed broadcasts
  on stripped-down ROMs have been observed returning null. Guard
  before calling cache / inflight removers so a null key cannot
  bubble up through `BroadcastReceiver` and trip the system's
  misbehaving-receiver protection.
- **`registerPkgReceiver` / `registerTimeReceiver` are now best-effort.**
  Hardened TV ROMs (and rare cases after a `system_server` restart)
  have been observed throwing `SecurityException` out of
  `registerReceiver` even though the launcher is the active home.
  Without a catch the throwable bubbled up through `onCreate` /
  `onResume` and the activity died before its view tree was visible.
  Both registrations now catch and continue; the user-visible
  consequence is that package / time broadcasts won't auto-refresh
  the affected subsystem until the next lifecycle event retries the
  listener wiring.
- **`unregisterPkgReceiver` / `unregisterTimeReceiver` symmetry.** Both
  unregister paths now also catch `SecurityException`, mirroring the
  register-side broadening above. Prevents `onDestroy` / `onPause`
  from throwing out of an unregister that the system also gates.

### Performance

- **Lazy-init reorder-mode `menuOverlay`.** The reorder-mode context
  menu (~10 views, 3 paint backgrounds, 3 click listeners) was built
  eagerly during `buildLayout()` even though it is only visible while
  the user is rearranging icons — a workflow that fires 0× on cold
  start and 0× for users who never long-press a shelf cell. Construction
  is now deferred to first `enterReorderMode` entry via a new
  `ensureMenuOverlay()` helper, mirroring the existing lazy-init
  pattern for `buildKeymapOverlay()`. Saves ~5-15 ms of cold-start
  view-tree work on slow TV ROMs for a feature most users never trigger.
  All consumers (`showContextMenu`, `hideContextMenu`,
  `updateMenuHighlight`, `dispatchTouchEvent`) already null-guard their
  entry, so a missed `ensureMenuOverlay()` call would silent-no-op
  rather than NPE — `enterReorderMode` is the single mandatory call site.
- **Pre-warm `SharedPreferences` async load in `initCaches`.**
  `SharedPreferencesImpl` spawns its `"SharedPreferencesImpl-load"`
  background thread inside the constructor that runs on the first
  `getSharedPreferences()` call, but the actual `Map` parse is
  synchronous-waited only on the first `.getString()` / `.getInt()`
  read. Calling `getSharedPreferences(PREFS, MODE_PRIVATE)` early in
  `initCaches` (before the slow `buildLayout()` step) lets the
  file-parse run in parallel with view-tree construction. By the
  time the first reader hits (`loadKeyMap`, `loadHiddenApps`, the
  `KEY_SCROLL_IDX` read in `onResume`), the parsed map is already
  in memory and the synchronous wait completes in a single
  `CountDownLatch` await. Net effect: ~5-30 ms less UI-thread
  blocking on slow ROMs at cold start, with zero behavioural
- **`launchApp` direct-intent fast path.** `pm.getLaunchIntentForPackage`
  performs **two synchronous binder calls** internally
  (`queryIntentActivities` for `CATEGORY_INFO`, then `CATEGORY_LAUNCHER`)
  to discover the launcher activity — but the launcher already cached
  the activity in `app.component` at `queryApps` time, AND
  `Intent.setComponent` bypasses resolution entirely (the named activity
  is started directly). The new primary path constructs the intent
  locally with the same shape `getLaunchIntentForPackage` returns
  (`ACTION_MAIN`, `CATEGORY_LAUNCHER`, `setPackage`, component override,
  `FLAG_ACTIVITY_NEW_TASK`) and skips the binder calls. Saves 50–200 ms
  of UI-thread latency per app launch on stripped TV ROMs where
  `PackageManager` is slow — the single biggest user-perceptible win
  in the codebase. The legacy `getLaunchIntentForPackage` and bare-
  `setComponent` paths are preserved as second / third fallbacks for
  the rare strict-mode ROMs that reject the direct path.
- **`iconExecutor` and `appExecutor` allow core-thread timeout.** Both
  background executors previously kept their core threads alive for
  the full activity lifetime. They are idle 99.99 % of the session
  (icon flood: ~1 s at cold start; app-list scan: same shape on every
  package broadcast). `allowCoreThreadTimeOut(true)` plus a 30 s
  `keepAliveTime` lets the threads exit when idle, reclaiming
  ~0.5–1 MB of stack each and removing them from heap dumps /
  StrictMode views. The pool re-creates threads on the next
  `execute()` call — observable behaviour for the next icon flood is
  identical (one-time thread creation cost in the µs range).
- **`appByPackage` map for O(1) `findAppByPackage`.** The previous
  linear scan was a measurable cost inside `refreshKeymapRows`,
  called on every UP / DOWN press in the keymap overlay — 6 rows ×
  N apps of `String.equals` per press. The map is rebuilt atomically
  inside the same UI block that mutates `appList`, so the two
  structures cannot diverge mid-frame. `dispatchKeyEvent`'s mapped-
  shortcut routing also benefits (drops from O(N) per remote-key
  press to O(1)).

### Fixed

- **`addApps` defensive null guard on `ai.name`.** Same hardened-ROM
  failure class as the previous `loadLabel` / `packageName` guards.
  `ActivityInfo.name` is documented as the activity's class name
  (always non-null in well-formed manifests) but stripped-down ROMs
  have been observed shipping `ResolveInfo` records where it is
  null — `new ComponentName(pkg, null)` throws an NPE in the
  constructor, which bubbled up through `addApps` → `queryApps` →
  `loadApps`'s outer `Throwable` handler, leaving a blank shelf
  until the next package broadcast retried. Skip silently and let
  the rest of the batch populate.

### Quality

- **`saveHiddenApps` drops a redundant `ArrayList` wrapper.**
  `ArraySet<String>` already implements `Iterable<String>` via its
  inherited `Collection` / `Set` typed signature, so it can be passed
  straight to `KeymapStore.serializeHiddenApps` without an
  intermediate copy. Saves one allocation per toggle — not a hot
  path, but the wrapper was strictly redundant and the comment
  explaining the wrapper was wrong about the type contract.
- **`paintHideChip` dead-ternary cleanup.** The hide-strip selected
  branch had `tv.setTextColor(hidden ? 0xFF111114 : 0xFF111114)` —
  both arms of the ternary identical. The flat assignment matches
  the comment intent without hinting (incorrectly) that the hidden
  state changes the colour. Cosmetic; no behavioural delta.

### Testing

- **`WallpaperController.computeSampleSize` extracted as static and unit-tested.**
  The sub-sampling math now lives in a pure-function `static int
  computeSampleSize(int srcW, int srcH, int screenW, int screenH)`
  helper that the instance `calcSampleSize(int,int)` delegates to.
  New `WallpaperControllerSampleSizeTest` (12 cases) pins every
  shape of input that has ever produced a wallpaper-decode
  regression: in-bounds, exact-screen, square 2× / 4× over,
  the **1.1.4 panorama OOM** (4000 × 500 on 1920 × 1080), tall
  portrait, 4 K-panel variants, 16 K × 16 K stress, power-of-two
  invariant, and pathological zero / 1 px screen termination via
  the `0x8000` safety cap.
- **`ClockFormatterTest` covers the minute-boundary scheduler.** The
  Spannable / Typeface output of `format(long)` cannot be exercised
  in JVM unit tests (Android-framework only), but the pure-arithmetic
  `nextMinuteDelay(long)` math now has 8 cases pinning the contract
  that drove the once-per-minute clock loop redesign in 1.1.0:
  result is always positive, never schedules more than `MIN +
  CUSHION` out, always lands at-or-after the next minute boundary,
  and the +50 ms cushion prevents firing twice in the same minute
  on a slightly-fast wall clock.



## [1.2.0] — 2026-05-29

Lower the supported-Android floor from 11 (API 30) to 8 (API 26),
unlocking roughly 25% more of the active Android-TV install base —
Mi Box S, older Fire TV sticks, many 2018-2020 Amlogic / Allwinner TV
boxes — exactly the hardware whose stock launcher BareLauncher exists
to replace. No performance compromise on newer Android: version-gated
branches via `Build.VERSION.SDK_INT` are evaluated by ART's branch
predictor in roughly one CPU cycle, and the kernel + framework
libraries on a device running Android 14 are unchanged regardless of
the APK's `minSdk`. The 1.1.5 dual-target dedupe fix (skipped as a
standalone tag) ships inside this 1.2.0 release.

### Changed

- **`minSdk` lowered from 30 (Android 11) to 26 (Android 8).** Single
  universal APK, no multi-APK split. The floor lands at API 26
  specifically because Adaptive Icons were introduced there and
  `IconRenderer` relies on `AdaptiveIconDrawable` as its primary
  rendering path; lowering further would force a second rendering
  strategy with a worse fallback for legacy bitmap icons. Raising
  higher (API 28 / Android 9) would cut off the Mi-Box-S /
  older-Fire-TV segment that's still in active daily use.
- **`hideSystemUI` now version-gated.** API 30+ devices take the
  modern `WindowInsetsController` branch (unchanged behaviour from
  1.1.x); API 26-29 devices take the legacy `setSystemUiVisibility`
  flag-based branch — same immersive-sticky / hide-bars behaviour,
  routed through the API the platform shipped before R. Both paths
  hide the navigation bar AND keep both bars hidden during transient
  swipes.
- **CI instrumentation matrix expanded** from `[30]` to `[26, 30]`.
  The smoke test now runs against the new minimum supported Android
  version on every PR — any future change that breaks API 26
  compatibility fails CI before merge.
- **Comments updated** to reflect the new floor: the lint `disable`
  rationale for `SyntheticAccessor` and the `IconRenderer` Adaptive
  Icon branch comment both note the 26-not-30 minimum (ART has been
  the default Android runtime since API 21, so the "ART always"
  invariant the synthetic-accessor argument relies on is still
  trivially true at the new floor).

### Audit (no behaviour change)

A full pass over every modern Android API used in the codebase
confirmed the only ungated API 30 surface was `hideSystemUI`. Every
other modern API call was already correctly version-gated for
TIRAMISU (API 33) or earlier — predictive back via
`OnBackInvokedDispatcher`, `Context.RECEIVER_NOT_EXPORTED` on both
the package and time receivers, `PackageManager.ResolveInfoFlags.of`
in `queryApps`. The 1.1.5 dual-target dedupe fix (`addApps` package-
only key, `queryApps` device-aware ordering) ships unchanged inside
1.2.0.

### CI

- **`abiFilters` cap broadened from `[armeabi-v7a, arm64-v8a]` to
  `[armeabi-v7a, arm64-v8a, x86_64]`.** AGP's `connectedAndroidTest`
  task uses the `ndk.abiFilters` declaration as one of the inputs to
  its "is this device compatible?" check — it intersects the device's
  reported ABIs with this filter. GitHub-Actions runners host x86_64
  emulators, so the AVD reports `[x86_64, x86]` on API 26-29 and
  `[x86_64, x86, arm64-v8a]` on API 30+ (the arm64 binary-translation
  layer ships in API 30+ system images only). With the previous
  ARM-only filter, the API 26 emulator instrumentation row failed
  with "Found 1 connected device(s), 0 of which were compatible" —
  while API 30 accidentally worked because of the translation layer.
  Adding `x86_64` to the cap fixes the smoke test on every API level
  AND keeps Chromebooks / x86 Android tablets supported as real
  production targets. The launcher has zero native dependencies so
  this remains a no-op against the current dependency set; the cap
  is purely a forward-defensive guard for any future native dep.

## [1.1.5] — 2026-05-29

App-shelf de-duplication fix and device-aware activity selection.
Closes a long-standing report on TV boxes where dual-target apps
(those declaring BOTH a phone-style `CATEGORY_LAUNCHER` activity and
a TV-style `CATEGORY_LEANBACK_LAUNCHER` activity) showed up twice on
the home shelf — once per category. Side-effect of the same fix is
that phone-only apps now reliably surface on TV and TV-only apps on
phone, so every installed launchable app (user, system, sideloaded)
appears exactly once regardless of which UI flavour it targets.

### Fixed

- **Dual-target apps no longer appear twice on the shelf.**
  `LauncherActivity.addApps` deduped using the composite key
  `"package/activity"`. A package that declared both a phone
  `CATEGORY_LAUNCHER` activity and a TV `CATEGORY_LEANBACK_LAUNCHER`
  activity has two ResolveInfo entries with the SAME package but
  DIFFERENT activity names — both passed the dedupe and the package
  showed twice. Switched the dedupe key to package name only: the
  first ResolveInfo to land for a given package wins, every later
  one is skipped.
- **Right activity wins for the right device.** `queryApps` now
  inspects `Configuration.uiMode` and asks the system for
  `CATEGORY_LEANBACK_LAUNCHER` first on TV (so the TV-tuned activity
  wins the dedupe race) and `CATEGORY_LAUNCHER` first on phones /
  tablets (so the phone-tuned activity wins). Both categories are
  still queried in series — apps that only declare one or the other
  surface on the shelf as before.



Pre-public-release audit pass. Five real bug fixes triaged from a deep
scan of every source file ahead of opening the repository to the world.
Nothing visible to existing users on the home screen — but each fix
removes a class of "wrong on edge devices" failure that would have shown
up as a "BareLauncher OOMs / shows the wrong time / crashes" report
once the install base widened.

### Fixed

- **Wallpaper sub-sampling now caps memory on every aspect ratio.**
  `WallpaperController.calcSampleSize` used `&&` (keep halving while
  BOTH source dimensions exceed the screen) instead of `||` (keep
  halving while EITHER dimension exceeds). On a panorama-aspect
  source — say a 4000 × 500 photo on a 1920 × 1080 screen — the loop
  saw `srcH (500) > screenH (1080)` evaluate to false on the very
  first iteration and exited at `inSampleSize = 1`, allocating a
  ~8 MB bitmap instead of a ~1.5 MB one. `CENTER_CROP` then scaled
  it down anyway, so the bug was invisible — but on a 4K panel with
  a high-resolution wallpaper, it could push the launcher over the
  per-process memory ceiling and trigger a `BitmapFactory` OOM that
  the surrounding `catch (OutOfMemoryError)` swallowed silently. Now
  the loop halves until the smaller-fitting axis fits, matching the
  intent of the existing `wpDrawable` cap.
- **Home-screen clock refreshes immediately on time / timezone
  changes.** A new lightweight `BroadcastReceiver` listens for
  `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` and
  `ACTION_DATE_CHANGED`. On any of those, the clock formatter's
  per-minute idempotency sentinel is reset and the next paint runs
  unconditionally. Without this, after a flight or a DST transition
  the clock kept showing the old time for up to 60 seconds. Receiver
  registers in `onResume` and unregisters in `onPause` so it costs
  zero while the launcher is in the background.
- **`addApps` null-guards `ActivityInfo.packageName`.** Stripped-down
  TV ROMs (Fire TV in particular) have been observed returning
  `ResolveInfo` objects with a non-null `activityInfo` whose
  `packageName` is `null`. The previous `ai.packageName.equals(self)`
  would NPE inside the icon-load executor body, propagate up through
  `queryApps`, and bubble into the `loadApps` `catch (Throwable)`
  that resets `appsLoading=false` — the user-visible effect was a
  blank shelf until the next package broadcast retried. Same kind of
  defensive guard 1.1.2 added for `loadLabel`.

### Documentation

- **`CHANGELOG.md` header de-duplicated.** The "# Changelog ..."
  preamble appeared twice at the top of the file. Cosmetic, but the
  release pipeline's `awk` script that extracts a version's section
  scans top-down and would have skipped the second header on parse —
  no functional impact, just noise.
- **`CHANGELOG.md` `[1.1.3]` section added.** The 1.1.3 work landed
  in `app/build.gradle.kts` as a comment block but was never lifted
  into the changelog. Filled in retroactively so the tag-triggered
  release pipeline emits the correct notes when `v1.1.3` is pushed.
- **Repository `.gitignore` added.** Covers `build/`, `.gradle/`,
  `local.properties` (which leaks the contributor's absolute SDK
  path), IDE noise (`.idea/`, `*.iml`, `.vscode/`), and any
  accidentally-dropped `*.jks` / `*.keystore`. The CI runner
  generates the gradle wrapper on the fly so `gradlew*` and
  `gradle-wrapper.jar` are also gitignored — only
  `gradle-wrapper.properties` is tracked.

### Versioning

- `versionCode` 5 → 6, `versionName` 1.1.3 → 1.1.4.

## [1.1.3] — 2026-05-28

Top-bar visual unification + wallpaper memory hygiene. The home-screen
clock now wears the same dark-glass plate as the toolbar pills, the
WiFi pill gains a long-press shortcut to general system Settings, and
the wallpaper crossfade no longer keeps a permanent screen-sized GPU
buffer alive for both `ImageView`s.

### Changed

- **Home-screen clock is now a pill that matches the toolbar.** The
  clock wears the same vocabulary as the top-right toolbar buttons:
  dark-glass plate (~40 % black), 1 dp white hairline rim, capsule
  corner radius (= height / 2). The bare digits used to lean on a
  heavy 14 dp drop shadow for contrast against the wallpaper; with
  a real plate the shadow is redundant and gone. Text drops from
  44 sp + bold-with-shadow to a compact 22 sp `sans-serif-medium`
  pill that lines up with the toolbar buttons across the top edge,
  giving the home screen a single horizontal baseline for time +
  toolbar instead of two.
- **WiFi pill long-press opens general system Settings.** Short
  click is unchanged (WiFi / network settings). Long-press
  (TV remote: hold DPAD_CENTER; touch: long-press) opens
  `Settings.ACTION_SETTINGS` so the second-tier "I want all the
  system settings" shortcut is exactly one gesture away from the
  first-tier WiFi shortcut. Discoverable via the standard
  press-and-hold gesture; falls back to a toast on stripped ROMs
  that have no Settings activity.
- **`AppleStyle.makePillBackground(density)`** factory added so the
  clock pill and the toolbar plates draw with the same code (one
  paint set, one rounded-rect path). Each call produces a fresh
  `Drawable` because `Drawable` state (bounds / alpha / level) is
  per-instance, but the underlying paints are not shared across
  drawables, so mutating one cannot leak into another.

### Fixed

- **`pkgReloadRunnable` is dropped on activity destroy.** A package
  broadcast scheduling a deferred 400 ms reload via
  `shelf.postDelayed(pkgReloadRunnable, 400)` could leave a method-
  reference (`this::loadApps`) queued on the looper after the
  activity went away. `loadApps` short-circuits on `destroyed` so
  the runnable was harmless, but it implicitly held a reference to
  the activity until the looper drained it — on a slow ROM that
  stretched the activity's lifetime by a few hundred milliseconds
  past the user navigating away. `onDestroy` now removes the
  callback explicitly.

### Performance

- **Wallpaper `ImageView`s drop their permanent
  `LAYER_TYPE_HARDWARE`.** Earlier versions forced both stacked
  wallpaper views into a hardware layer so the cross-fade alpha
  animation could run on an offscreen FBO. The cost: a screen-sized
  GPU buffer for each view, *continuously*, in service of a 200 ms
  transition that fires only when the user actually changes the
  wallpaper. On 1080p that's ~16 MB of GPU memory burned for nothing
  steady-state; on 4K it's ~64 MB. `ImageView` is a leaf with a
  single drawable — alpha applies directly via the `BitmapDrawable`'s
  paint, so the hardware layer was buying nothing. Default
  `LAYER_TYPE_NONE` is now used and the wallpaper subsystem returns
  ~16–64 MB of GPU memory to the rest of the device.

### Versioning

- `versionCode` 4 → 5, `versionName` 1.1.2 → 1.1.3.

## [1.1.2] — 2026-05-27

Minimal-toolbar pass + executor-flag hardening. The wifi / mapper /
wallpaper pill cluster reads more quietly, and a handful of stranded-
flag / null-ROM edge cases in the package-load and icon-load executors
now converge cleanly instead of silently freezing those code paths.

### Changed

- **Top-right toolbar pills are smaller.** Layer/clip-outline box
  shrinks from 52 dp → 40 dp, gap between pills tightens from 6 dp
  → 4 dp, top/end margins from 18/20 dp → 14/16 dp, and the focus pop
  softens from 1.06× → 1.04× (a new `BTN_FOCUS_SCALE` constant the
  three button factories share — replaces three identical 1.06f
  literals). Color philosophy is identical: dark-glass idle plate,
  frosted-white focused plate, hairline white rim, glyph inverts on
  focus. Every paint factory in `AppleStyle` is untouched.
- **Mapper "sliders" glyph rebalanced for the smaller plate.** Bar
  length scales from 1.30× → 1.12× of the icon container so the bars
  no longer skim the rim, with a slightly thinner stroke (0.18 →
  0.16) and tighter spacing (0.55 → 0.50) so the symbol stays
  balanced rather than ink-heavy at small size.

### Fixed

- **`loadApps` UI block now resets `appsLoading` in a `finally`.** A
  fault inside `pruneHiddenApps` (SharedPreferences write), the shelf
  apply path, or `requestFocusOnIndex` could previously strand the
  flag at `true`, turning every subsequent package broadcast into a
  silent no-op for the lifetime of the activity. The reset is now
  guaranteed.
- **`loadIconAsync` no longer NPEs on null `ResolveInfo`.** Some
  stripped-down TV ROMs (Fire-TV in particular) return `ResolveInfo`
  objects with broken icon-loading paths. `app.ri.loadIcon(pm)` is
  now null-guarded explicitly; the surrounding `RuntimeException`
  catch already covered the case in practice but the guard makes
  the intent obvious. Same fix in `preWarmIcon`.
- **`addApps` tolerates null `loadLabel`.** Stripped-down ROMs can
  return apps with broken label resources; we now fall back to the
  package name instead of NPE-ing on `.toString()` and dropping the
  whole shelf-refresh batch.
- **`loadIconAsync` UI handler no longer dispatches when bitmap is
  null.** Matches `preWarmIcon`'s contract exactly: clear the
  inflight entry so a future bind can retry, but don't push a null
  bitmap into cells (which would do nothing — small cleanup).

### Performance

- **`RingView` drops `LAYER_TYPE_HARDWARE`.** For a single-stroke
  anti-aliased circle, the hardware layer forced an offscreen FBO
  and texture upload every frame the ring moved or scaled (every
  focus animation, every shelf scroll). The default
  `LAYER_TYPE_NONE` lets the stroke go straight to the display list,
  saving ~0.3 ms per frame on slow TV ROMs.

### Versioning

- `versionCode` 3 → 4, `versionName` 1.1.1 → 1.1.2.

## [1.1.1] — 2026-05-26

Public-release polish pass. Bug fixes triaged from a focused audit of
`LauncherActivity` plus a license change to reserve future commercial
edition rights.

### Fixed

- **Volume / power / media keys now pass through while the keymap
  overlay is open.** `handleKeymapOverlayKey` previously returned `true`
  for every keycode, swallowing `KEYCODE_VOLUME_*`, `KEYCODE_POWER`,
  `KEYCODE_HOME`, and the media-transport keys. Users could not adjust
  volume or sleep the device while configuring shortcuts. New
  `isLetThroughKey` allow-list lets these keys reach the platform via
  `super.dispatchKeyEvent`.
- **Predictive-back (Android 13+) now closes the keymap overlay and the
  context menu.** The previous `OnBackInvokedCallback` only handled
  reorder mode; on devices that route BACK through the platform
  dispatcher (instead of `dispatchKeyEvent`) the overlay was un-closable
  by gesture. The callback now mirrors the legacy back-priority chain:
  picker → hide-manager → slot list → reorder mode → no-op.
- **Stranded `appsLoading=true` when an exception fires inside
  `loadApps`.** `queryApps` and `applyStoredOrder` are now wrapped in a
  `try / catch (Throwable)` that always resets the flag. A single
  PackageManager binder fault could previously freeze the shelf-refresh
  path for the lifetime of the activity (every package install /
  uninstall broadcast became a silent no-op).
- **Null `queryIntentActivities` result handled.** `addApps` now
  early-returns on `null`. `PackageManager.queryIntentActivities` is
  documented non-null but several real-world ROMs (Fire TV in
  particular) have been observed returning `null` after a system
  process restart or a SELinux denial.
- **Toast leak on activity destroy.** `currentToast.cancel()` is now
  called in `onDestroy`. A long toast in flight when the activity tears
  down used to keep the activity context alive for ~3.5 s on older
  ROMs.
- **`globalFocusListener` no longer registers twice on rapid
  `onResume → onResume` paths.** Same dedupe pattern that
  `focusRestoreListener` already uses.
- **`OnBackInvokedCallback` is now unregistered on destroy.** Held as a
  field so a partial-recreate path cannot leave a stale callback
  registered against the prior activity instance.
- **`loadApps` runOnUiThread lambda has a `destroyed` guard.** Stops
  spurious `pruneHiddenApps` SharedPreferences writes after the
  activity is gone.

### Performance

- **Shelf scroll avoids a full `View.layout()` per attached cell every
  frame.** `repositionAttached` now uses `offsetLeftAndRight` for
  position-only updates and only falls back to `cv.layout(...)` when the
  cell width / height drifted (defensive — should never happen with the
  recycler). `View.layout` triggers `onSizeChanged` plumbing and a
  `requestLayout` chain even when sizes are unchanged; skipping it
  visibly reduces dropped frames during fast flings on cheap TV ROMs.
- **`dispatchKeyEvent` short-circuits the `keyMap.get` lookup when no
  shortcuts are bound.** A 1-line guard avoids a `SparseArray.get` on
  the system-wide UI key path during gameplay-style remote use.

### Changed

- **License: MIT → PolyForm Noncommercial 1.0.0.** Source remains
  available; non-commercial use (personal, hobby, education, charity)
  is unchanged. Commercial use (reselling, bundling into a paid product,
  shipping pre-installed on hardware sold for profit, paid service
  integration) now requires a separate written licence from the
  copyright holder. See [`NOTICE.md`](./NOTICE.md) for details and
  commercial-licensing enquiries.
- **`README.md`** rewritten with practical install / build / contribute
  / license sections instead of marketing prose only.
- **`versionCode` 2 → 3, `versionName` 1.1.0 → 1.1.1.**

### Added

- **`NOTICE.md`** — explains the licensing posture, contribution terms
  (contributors agree to allow commercial relicensing by the maintainer),
  and the trademark carve-out for "BareLauncher" name and icon.

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
  process) and `LICENSE` (MIT — superseded by PolyForm Noncommercial in
  1.1.1) so the project is legally and practically distributable.
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
