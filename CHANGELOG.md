# Changelog

All notable changes to BareLauncher land here.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/)
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.4.9] — 2026-06-24

Adds an About screen and reworks the clock and toolbar icons. Still
zero-dependency: the QR codes are generated on-device by a small built-in
encoder (no library, no network).

### Added

- **About card** (Settings → About): app version (read live from the
  installed package, so it always matches the build), a "Support me on
  Ko-fi" row that shows a scannable QR of the Ko-fi page, the Downloader
  code for the latest release, a "Check latest version on GitHub" row
  that shows a QR of the releases page (with a note to use the Downloader
  code to grab a newer build), and a "Made by Mithun" credit. Version,
  code, and credit lines carry no selector; the two QR rows are
  selectable.
- **Self-contained QR generator** (`QrCode`): byte-mode, level-M,
  versions 1–10, dependency-free and offline. Only runs when a QR is
  opened, so there is no steady-state cost.

### Changed

- **Clock is now a 3-state cycle** (Settings → Clock): Full (time +
  day/date) → Time only → Off → Full. Pre-1.4.9 show/hide installs
  migrate automatically.
- **Clock restyle**: the rounded pill plate is gone — the time floats
  bigger over the wallpaper with a soft shadow for legibility, and a
  small, dim "day, date month" line sits beneath it.
- **Minimal Wi-Fi and gear icons**: no plate when idle (the glyph floats
  and is dimmed), with the plate returning on focus as the selection
  indicator. The Wi-Fi fan's 2nd and 3rd bars are slightly shorter for a
  cleaner look.
- **Slightly more spacing between app tiles.**

## [1.4.8] — 2026-06-24

App-drawer UX pass. Nine reported issues fixed, all on the existing
zero-dependency render path — no new dependencies, no new allocations on
any hot path.

### Fixed

- **Hidden-apps manager showed the wrong icon next to an app name.** The
  live icon-delivery hook (`onIconLoaded`) indexed the hidden-apps row
  list with an `appList` index, but those rows are keyed by the filtered
  `hideListApps` subset — so a late-arriving icon landed on a different
  row. Icons are now matched to rows by package name.
- **Selector jumped to the last app after hiding one from the drawer.**
  `drawer.setApps()` posts its own focus request; the explicit
  hide-refocus raced it and lost. The hide-refocus is now posted so it
  runs last and lands on the slot the hidden app vacated.
- **Divider between the home row and the grid froze during a fast
  scroll.** The divider is painted by the drawer in `dispatchDraw`, but
  `doScrollTo` only offset the cells without invalidating the parent, so
  the line stopped tracking the content during a drag/fling. The drawer
  now invalidates on every scroll step.

### Changed

- **Drawer app moves now snap instantly in every direction.** The
  left/right glide on the 2-D grid had to contend with scroll reflow and
  promote/demote cases and read as broken. Moves are now crisp and
  allocation-free (the per-move FLIP capture is gone); the clean 1-D
  slide remains on the single-row home shelf.
- **Drawer close cross-fades home back in.** The shelf, clock, and
  toolbar are restored concurrently with the drawer's fade instead of
  popping in at the end, removing the blink. Focus and the selection
  ring land on the destination home cell immediately, so the ring no
  longer lingers at the old drawer slot.
- **Drawer grid keeps a row of context when navigating.** Focus now
  scrolls the grid at the second-last visible row instead of sitting
  flush against the edge.
- **Drawer divider is slightly thicker and more visible**, centred in
  the row gap.
- **App-name labels are slightly heavier** (fake-bold) on both the
  drawer and the home shelf — free at draw time.
- **Drawer background veil toned down a notch** so it is less dominant
  over the wallpaper.
- **Hidden-apps panel hugs its content** with a max label width, so it
  is compact for short names and ellipsises long ones instead of leaving
  dead space.

## [1.4.5] — 2026-06-01

Focused polish pass from a deep post-1.4.4 audit. One correctness fix,
one hot-path optimisation, one dead-resource cleanup. No new features,
no API changes, no behavioural change on the happy path.

### Fixed

- **Settings panel no longer swallows the KEY_UP edge of device-control
  keys.** The settings-panel branch of `dispatchKeyEvent` consumed every
  non-`ACTION_DOWN` event unconditionally, including the volume / mute /
  power / media keys whose `ACTION_DOWN` edge the same branch already
  lets fall through to the platform (via the `isLetThroughKey` path in
  `handleSettingsKey`). On the rare ROMs that deliver both key edges to
  user space — HDMI-CEC volume bridges and some set-top remotes — that
  left `AudioService` / `PowerManager` with an unbalanced
  DOWN-without-UP while the panel was open. The branch now mirrors the
  keymap overlay's contract exactly: only the UP edge of keys we
  actually consume on DOWN is swallowed; device-control keys pass
  through on both edges. On mainstream Android TV ROMs the platform
  intercepts these keys before dispatch, so this was latent there —
  the fix matters for the CEC-bridge / set-top hardware in the
  post-1.2.0 minSdk-26 install base.

### Performance

- **Per-`AppInfo` ellipsised-label memo removes re-measure / re-alloc on
  the scroll hot path.** A shelf cell's truncated label is a pure
  function of constant inputs (cell width budget, label text size,
  typeface), so the result is identical for every cell that ever renders
  a given app. It is now computed once per app and cached on the model;
  a recycled cell scrolling back onto an already-seen app during a fling
  reads the cache instead of re-running `Paint.measureText` and (on
  overflow) `TextUtils.ellipsize`, the latter of which allocates a
  `CharSequence` + a `String` on every call. The memo is cleared on a
  density / font-scale change (`onConfigurationChanged`) so a truncation
  computed against old metrics can't go stale. UI-thread-confined, so no
  synchronisation against the icon-decode workers.

### Cleaned up

- **Removed the `android:roundIcon` resource set.** The manifest
  attribute, the `mipmap-anydpi-v26/ic_launcher_round.xml` adaptive
  definition, and the five `ic_launcher_round.png` density fallbacks are
  gone. On the minSdk-26 floor every device resolves the adaptive
  `ic_launcher` and the platform derives the round / squircle / teardrop
  mask from it, so a separate round set was pure dead weight — it only
  fattened the source tree and the debug APK (the release APK already
  dropped the unreferenced files via `shrinkResources`).
  `scripts/render_launcher_icons.py` no longer emits the round variants.

## [1.4.4] — 2026-05-31

Defensive guards for OOM and missing-service crashes on memory-
constrained / stripped TV ROMs. Each fix is purely additive — zero
behaviour change on the happy path, only fires when the underlying
failure mode would otherwise crash a worker thread or the activity.

### Fixed

- **`getSystemService(ACTIVITY_SERVICE)` null guard with 64 MB
  heap-class fallback.** {@link android.content.Context#getSystemService}
  is documented to return `null` when the named service does not
  exist. `ACTIVITY_SERVICE` is a core Android service that should
  always be present, but stripped TV firmware (some Wear OS /
  IoT-derived ROMs that have ended up running on cheap TV boxes)
  have been observed missing it. Without the guard the launcher
  NPEs inside `initCaches` during `onCreate` and dies on launch —
  user gets a black home screen with no recovery short of factory
  reset.
- **`WallpaperController.loadSystem` catches `OutOfMemoryError`.**
  Pre-1.4.4 the catch was `catch (Exception ignored)` which doesn't
  include `Error` subclasses. `wpDrawable` allocates a screen-sized
  ARGB_8888 bitmap (8 MB at 1080p, 32 MB at 4K) which can OOM on
  memory-constrained TV boxes (1 GB-RAM Fire TV stick variants,
  older Mi Box models). Without the OOM catch the throwable
  propagates out of the wallpaper executor task, kills the worker
  thread, and the wallpaper never appears for the rest of the
  session.
- **`WallpaperController.writeSnapshotBestEffort` catches `OOM`.**
  `Bitmap.compress` on a 1080p ARGB source needs ~10–20 MB of
  transient encoder workspace. On low-memory TV boxes under
  post-decode peak heap pressure, this OOMs. The pre-1.4.4 catch
  only caught `IOException`, so OOM propagated and crashed the
  wallpaper executor thread.
- **`IconDiskCache.writeSync` catches `OOM`.** Same shape as
  above for the per-icon disk-cache writes during the cold-start
  icon flood.

## [1.4.3] — 2026-05-31

Skip background CPU work when the activity is paused, plus three
small consistency cleanups. Real-world impact is the most modest of
the post-1.4.0 audit series — a few dozen ms of CPU per background
package update saved on memory-constrained ROMs, plus tighter state
machine in the package-broadcast / loadApps interaction.

### Fixed

- **Package broadcast no longer schedules a reconcile while the
  activity is paused.** Pre-1.4.3 the receiver always scheduled a
  400 ms-delayed `loadApps` reconcile, burning ~50–250 ms of CPU
  on a background PM scan + cell rebuild that no human was looking
  at. The pending looper message also kept the launcher process
  alive past the broadcast, bumping memory pressure on the system's
  process LRU. New `uiPaused` field (set in `onPause`, cleared in
  `onResume`) makes the receiver skip the post while paused. The
  existing `pkgChangedWhilePaused` flag still triggers the same
  reconcile in `onResume` so no broadcast is missed.
- **`pkgReloadRunnable` clears `pkgChangedWhilePaused` at start.**
  Pre-1.4.3 the runnable was a bare `this::loadApps` method-
  reference; a successful in-foreground reconcile left the flag at
  `true`, causing the next `onResume` to fire `loadApps` redundantly
  (short-circuited by `appsLoading.compareAndSet`, but still the
  cost of allocating the method-reference and walking the entry
  path). Cleaner state machine, no observable change beyond the
  redundant call eliminated.

### Cleaned up

- Reused `pkgReloadRunnable` for the trim-memory and
  rejected-execution retry posts. Pre-1.4.3 each used a fresh
  `this::loadApps` method-reference; they now share the cached
  Runnable instance. Side benefit: a future
  `uiHandler.removeCallbacks(pkgReloadRunnable)` call cancels any
  of the three deferred-`loadApps` paths.
- Deleted the unused legacy `shutdown(ExecutorService)` helper
  retained from 1.4.1 with `@SuppressWarnings("unused")`. The
  parallel-shutdown path in `onDestroy` has been stable across
  two release cycles; the legacy helper had no callers.

## [1.4.2] — 2026-05-31

Hot-path performance audit — five real bottlenecks fixed. APK
*shrank* (96.1 KB → 95.7 KB) because removing the buffer-copy and
intermediate-bitmap-scale paths deletes more code than the new
direct paths add.

### Fixed

- **`IconRenderer.needsFill` replaced 290 KB pixel-buffer copy with
  12 `getPixel` calls.** The white-plate detection heuristic samples
  12 alpha values to decide whether an icon needs a plate. The
  pre-1.4.2 implementation copied the *entire* bitmap (~290 KB at
  xxxhdpi) into a per-worker thread-local byte buffer just to read
  those 12 bytes — a ~25 000× amplification. Per-icon cost dropped
  from ~30 µs memcpy to ~1.2 µs (12 × ~100 ns JNI). Per-worker
  held memory: ~290 KB ThreadLocal → 0. Pool-wide: 3-4 workers ×
  290 KB ≈ 870 KB – 1.2 MB of held memory eliminated.
- **`IconRenderer.renderDrawable` non-`BitmapDrawable` path skips
  the intermediate intrinsic-size bitmap.** For non-`BitmapDrawable`
  / non-`AdaptiveIconDrawable` drawables, the old path allocated an
  intrinsic-size bitmap, drew the drawable into it, then matrix-
  scaled into a target-size bitmap. Every standard Android
  `Drawable` subclass honours `setBounds` correctly, so the new
  code draws directly at `sz × sz`. Saves ~290 KB transient
  ARGB_8888 allocation per such icon at xxxhdpi. `BitmapDrawable`
  keeps its existing matrix fast-path; `AdaptiveIconDrawable` is
  handled by the caller.
- **`iconExecutor` pool sizing raised from `cores − 1` to `cores`.**
  Pre-1.4.2 used `Math.max(2, cores − 1)` core threads with
  `max = cores`. On a 4-core TV that's 3 workers running cold-start
  icon decode in parallel; bumping to `cores = 4` gives a ~25 %
  wall-clock reduction (~333 ms → ~250 ms on a 50-app cold start).
  The "cores − 1" tuning assumed leaving a core free for the UI
  thread, but Android's CFS scheduler rotates threads at sub-ms
  granularity and the foreground UI thread's priority class gets its
  fair share regardless. The `Math.max(2, cores)` floor also
  defensively avoids the latent `IllegalArgumentException` that
  `core=2 > max=1` would have triggered on a hypothetical 1-core
  device (Android always reports ≥ 2 logical cores in practice, but
  the contract is now safe by construction).
- **`positionRing` caches root location across calls.** The
  activity's root view doesn't move on screen for the lifetime of a
  TV launcher (no multi-window, no window position changes).
  Caching `root.getLocationOnScreen()` eliminates one full
  view-tree walk (~5 matrix multiplications + 5 offset
  accumulations) per `positionRing` call. At 60 Hz × 150 ms focus
  animation = ~9 view-tree walks saved per focus event.
  Invalidated on `onConfigurationChanged` for HDMI swaps / font
  scale / multi-window. UI-thread only, no synchronisation.
- **`AppListCache.readFile` uses `Files.readAllBytes` single-shot.**
  Replaces the `BufferedReader` + `InputStreamReader` +
  `StringBuilder` loop with one syscall + one alloc. Faster
  cold-start cache pre-paint. API 26+ available since our minSdk
  floor.

## [1.4.1] — 2026-05-31

Post-1.4.0 audit pass — two PR cycles of correctness, robustness, and
performance fixes found in a deep code review of the cache-layer
release. No new features, no API surface changes, no resource changes.
Release APK size unchanged (`~96 KB`); `classes.dex` grew `+1.5 KB`
for the new helpers.

### Fixed

- **Wallpaper bitmap recycle race in `WallpaperController.crossfade`.**
  The old FRONT and previous BACK bitmaps used to recycle synchronously
  inside the same UI runnable as the `setImageBitmap` that scheduled
  them, so the next vsync could still play back a `DrawBitmapOp`
  pointing at just-recycled pixels. On SkiaGL TV ROMs this surfaced
  as `Canvas: trying to use a recycled bitmap` and killed the launcher
  mid-cross-fade. Both recycles are now deferred via
  `View.postOnAnimation` so they land on the next animation frame
  *after* the display list has been refreshed with the new bitmap.
- **`onDestroy` UI-thread block reduced from worst-case ~1.2 s to
  bounded 300 ms.** The previous teardown sequentially called
  `shutdown()` + `awaitTermination(300, MS)` + `shutdownNow()` on each
  of the four executors (icon, app, `IconDiskCache` write,
  `WallpaperController`). Each executor now splits into
  `beginShutdown` / `awaitShutdown` phases; `LauncherActivity.onDestroy`
  fires `begin()` on every executor up front, then drains all four
  against a single shared 300 ms wall-clock deadline via
  `awaitOrSkip` + `remainingMs` helpers. Total UI-thread block now
  bounded by 300 ms regardless of how many executors are involved —
  visible-stutter regression on launcher-exit eliminated.
- **`ResolveInfo` grafting in the no-change reconcile branch.**
  `AppInfo` instances reconstructed from the on-disk `AppListCache`
  carry `ri == null` because `ResolveInfo` isn't serialisable. When
  the post-launch PM scan reconciled and saw no structural change,
  the previous code skipped the `appList` rebuild and left every
  cache-sourced entry's `ri` null for the activity's lifetime — every
  icon load took the slower `pm.getActivityIcon` binder fallback. The
  no-change branch now grafts `ri` from `freshFinal[i]` onto
  `appList[i]` for every upgraded entry. `AppInfo.ri` changed from
  `final` to `volatile` non-final to give the icon executor's worker
  thread a clean visibility guarantee on the upgrade.
- **Chip-strip rebuild during open keymap overlay.** When a package
  broadcast lands while the user is inside the keymap card's
  `KEYMAP_MODE_PICKER` or `KEYMAP_MODE_HIDE` chip strip, the strip's
  chip → package mapping just changed under their hands. The package
  receiver invalidated the `*BuiltSize` caches for the next open, but
  the user is currently looking at stale chips — pressing OK on chip
  *i* would silently toggle a different package than the chip's label
  suggested. The reconcile UI body now calls
  `rebuildOpenChipStripsAfterReconcile` which detects an open overlay
  and forces an immediate rebuild + selection clamp.
- **`onTrimMemory` no longer orphans icon-decode tasks.**
  `iconInflight.clear()` was called at `TRIM_MEMORY_MODERATE` /
  `TRIM_MEMORY_BACKGROUND`, which orphaned executor tasks that had
  captured the cleared waiters list. The next bind for the same
  package re-inserted a fresh waiters list, leaving two concurrent
  decode tasks racing on `iconCache.put` and the disk write. The two
  intermediate-pressure clears are removed; the `TRIM_MEMORY_COMPLETE`
  clear stays because that branch already empties `appList` so there
  are no live waiters to orphan.
- **Rejected reconcile now retries.** The catch on
  `RejectedExecutionException` from `appExecutor.execute` used to drop
  the request silently. For the cold-start cache pre-paint that meant
  the cache-sourced `AppInfo` entries (with `ri == null`) survived for
  the rest of the session. The catch now posts a 400 ms-delayed
  `loadApps` retry on `uiHandler` so the executor has time to drain.
  Bounded by the existing `appsLoading` `compareAndSet` gate so
  retries can't pile up.
- **`pendingScrollIdx` clamped against displayed-list size.** The
  saved scroll index is a displayed-list index (the shelf saves
  `s.focusedIndex` in `onPause`). The reconcile previously clamped it
  against `freshFinal.size()` and let `setApps` clamp it again to
  `displayed.size() - 1` — silently landing the user on the last
  visible cell instead of the closest valid one when hide-apps was
  filtering. New `countVisible` helper computes the post-filter size;
  the clamp now uses it directly.
- **Cross-thread visibility for `iconCache`, `iconDiskCache`,
  `iconExecutor`, `appExecutor`.** Declared `volatile` so a stale
  non-null reference can never be read on a background worker after
  the activity's `onDestroy` nulls them.
- **`loadApps` early-out on `destroyed`.** A package broadcast can
  post the `pkgReloadRunnable` to the shelf's looper just before
  `onDestroy` nulls the shelf, and the looper drains the runnable
  after `destroyed = true`. `loadApps` now early-outs at the top so
  the cache pre-paint block doesn't run on a dead activity and submit
  a doomed task to a shut-down executor.

### Performance

- **`IconRenderer` plate-path collapsed from 2 transient ARGB_8888
  bitmaps to 1.** The previous pipeline composited the white plate +
  scaled icon into one bitmap, then ran a separate `clipToCircle`
  pass with a `SaveLayer` + `SRC_IN` mask. The composite now uses
  `PorterDuff.Mode.SRC_ATOP` against the white circle directly, which
  preserves the destination's alpha (so the circle's anti-aliased
  edge profile is retained verbatim) while letting the icon overwrite
  where it has non-zero alpha. Output is pixel-equivalent at every
  alpha permutation. Saves ~290 KB of transient ARGB_8888 allocation
  per plate-path icon at xxxhdpi — typically ~30 of 50 apps on cold
  start, so ~8.7 MB less transient GC pressure during the icon-decode
  flood. Same fix applied to both the `AdaptiveIconDrawable` plate
  branch and the legacy bitmap-icon plate branch.
- **`applyShelfApps` reuses an instance-level scratch
  `ArrayList`.** The filtered "visible" list (apps minus hidden) is
  now built into a reusable `visibleScratch` field instead of
  allocating a fresh `ArrayList` on every package broadcast / hide-
  toggle commit / `loadApps` reconcile. `setApps` snapshots into its
  own list so the scratch never leaks references across UI events.
- **`equalizeKeymapRowWidths` drops the redundant restore-prevW
  pass.** The intermediate width never affected output (every row is
  unconditionally re-snapped to `max` in the second pass); restoring
  it just to overwrite it on the next call was 6 wasted
  `setLayoutParams` cascades per re-equalize. Functional output
  unchanged.
- **`CrashLogger` switched to static `DateTimeFormatter`.** Replaces
  the per-crash `new SimpleDateFormat(...).format(new Date())`
  pattern. `DateTimeFormatter` is immutable and thread-safe;
  `SimpleDateFormat` is neither. Available since API 26 (our minSdk
  floor), no compatibility shim needed. Allocation-free per crash.

### Hygiene

- **`WallpaperController.crossfade` defensive `destroyed` check at
  top of method.** Functionally equivalent to the existing
  `withEndAction` guard but makes the contract "no animation starts
  after destroy" explicit and survives future refactors.
- **`lint.xml` suppressions for 6 structurally-required warnings.**
  `ClickableViewAccessibility`, `NonResizeableActivity`,
  `DiscouragedApi`, `ViewConstructor`, `IconLauncherShape`,
  `ObsoleteSdkInt` — each ignored because the underlying lint
  guidance does not apply to a TV-first programmatic launcher (full
  rationale documented inline in `lint.xml`). Drops the warning count
  from 23 to 11. The remaining 11 are real pending issues — newer
  AGP available, missing monochrome adaptive-icon layer, etc.

### Build / CI

- **Gradle wrapper bumped 8.14.1 → 8.14.5.** Patch bump within the
  8.14 series; no breaking changes. CI `GRADLE_VERSION` env updated
  to match the wrapper so reproducibility is preserved.
- **`androidx.test:ext:junit` 1.2.1 → 1.3.0;
  `androidx.test:runner` 1.6.2 → 1.7.0; `androidx.test:core`
  1.6.1 → 1.7.0.** Test-classpath only; production APK unaffected
  (zero external deps in production, see project README).
- **AGP 8.10.1 → 9.2.1 deferred.** The 8 → 9 boundary contains real
  semantic changes (deprecated DSL removal, manifest merger
  behaviour, build cache invalidation rules). A dedicated PR with
  full regression testing on both API 26 and API 30 emulator rows is
  warranted; this audit-PR keeps the AGP version steady.

## [1.4.0] — 2026-05-31

Cold-start cache layer release. Three new on-disk caches paint the
wallpaper, the app shelf, AND the icons in the very first frame on
every cold start after the first. HARDWARE bitmap config on the
wallpaper drops 8–32 MB of Java heap. Resolution-keyed icon files
self-invalidate on DPI / display-mode changes. ProGuard rules
tightened — `classes.dex` shrank from 97 KB to 78 KB (~20 % smaller).
Six bugs caught and fixed across the deep audit pass that produced
this release.

### Added

- **`WallpaperController` cold-start snapshot.** A WEBP-q95 copy of
  the last-loaded user wallpaper is kept at
  `filesDir/wallpaper.snap`. The activity calls `loadSnapshotSync()`
  synchronously from `buildLayout`, before the first vsync — the
  wallpaper appears in the very first painted frame instead of after
  the 200–500 ms `ContentResolver` + `BitmapFactory` pipeline.
  `loadStored()` short-circuits when the snapshot pre-painted so the
  same URI is not re-decoded over identical bytes (no visible
  re-fade flicker). Wallpaper bitmaps now use `Bitmap.Config.HARDWARE`
  (API 26+, our minSdk floor) so the pixel data lives in graphics
  memory instead of on the Java heap. Saves ~8 MB at 1080p, ~32 MB
  at 4K. ARGB-fallback path preserved for the rare GPU driver that
  fails the conversion — zero observable regression.
- **`AppListCache` (new file).** Line-based version-prefixed text
  format at `filesDir/applist.cache`: magic `BLAC`, version byte,
  count, then `(pkg, label, activityClass)` triples. `loadApps()`
  reads it synchronously before submitting the PM scan; the shelf
  paints from cache instantly. PM scan reconciles in background;
  identical results → no re-render, package set differs → replace
  `appList` and rewrite cache. Cached `AppInfo` instances carry a
  `null` `ResolveInfo`; the icon-load path falls back to
  `PackageManager.getActivityIcon(component)` for those — same
  observable behaviour, same binder cost. `saveOrder()` also writes
  the cache so a user reorder is reflected on the next cold start
  without flicker.
- **`IconDiskCache` (new file).** Per-package WEBP-q95 at
  `filesDir/icons/{pkg}-{px}.icn`. Disk reads happen on the icon
  executor (background) inside a new `loadIconBlocking()` helper that
  consolidates the disk-first → PM-fallback → disk-write pipeline.
  UI thread is never blocked on icon disk I/O; `cell.bind` calls
  `iconCache.get` which is memory-only. Atomic writes (tmp +
  rename). Invalidated by package broadcasts (`REPLACED` /
  `CHANGED` / `REMOVED`).
- **Resolution-keyed icon filenames.** Filenames include the
  current `dp(ICON_DP)` pixel size so DPI / font scale / display
  configuration changes silently switch the lookup key — a stale-
  resolution icon never renders inside a different-size cell after
  the user reconfigures their TV. The constructor purges legacy
  v1.4.x first-cut `.cache` files in a one-time background sweep
  so the format change does not leak disk space.
- **`pruneKeyMap` helper.** Mirror of the existing
  `pruneHiddenApps`; called from the `loadApps` reconcile to drop
  remote-key shortcut bindings whose target package has been
  uninstalled. Without it, the keymap settings slot row showed the
  raw package name (instead of "Not assigned") for every binding
  pointing at a vanished app, until the user actually pressed that
  specific key. Eager prune keeps the slot list visually correct
  without user action.
- **`AppInfo.java` extracted to its own top-level file.** Was a
  nested class inside `LauncherActivity`; pulled out so the new
  `AppListCache` and `IconDiskCache` helpers can construct
  `AppInfo` instances without reaching into the activity's
  namespace. Behaviour identical (still package-private, same
  constructor).
- **`AppListCacheTest` unit-test class (30 tests).** Covers the
  parse rejection paths (bad magic, unsupported version, truncated
  entries, empty fields, zero count), success paths, serialise
  round-trip, label sanitisation (no-op fast-path + replacement of
  `\n` / `\r`), Windows line-ending tolerance, large-list
  (200 entries), unicode (CJK / RTL / emoji), and pipe-character-
  in-label round-trips. Pure JVM, no Android dependencies.

### Changed

- **Wallpaper executor switched** from
  `Executors.newSingleThreadExecutor()` (kept its worker thread
  alive forever, holding ~512 KB of stack through millions of idle
  cycles) to a `ThreadPoolExecutor` with `allowCoreThreadTimeOut(true)`.
  Worker thread now exits when idle, matching the `iconExecutor` /
  `appExecutor` discipline already in the activity.
- **Hidden-app icon prewarm made lazy.** Was eager on every
  `applyShelfApps` call (i.e. every package broadcast); now runs
  inside `showKeymapOverlay()` only when the user actually opens
  the surface that displays hidden-app icons. Stops the per-
  broadcast disk-read storm for installs with many hidden apps.
- **Package-broadcast invalidation extended** from `REPLACED` /
  `CHANGED` to also include `REMOVED`. Each invalidation now also
  calls `iconDiskCache.delete(pkg)` so a stale icon file does not
  survive a package replace / uninstall.
- **`onCreate` ordering: `loadKeyMap()` and `loadHiddenApps()`
  now run BEFORE `loadApps()`.** Both are simple SharedPreferences
  reads with no dependency on `appList`; the original ordering was
  an oversight from the pre-cache era when `applyShelfApps` did
  not run inside `loadApps`. The cache pre-paint now sees the
  correct `hiddenApps` set on the very first `applyShelfApps` call.
- **`hideKeymapOverlay` snap-close path now opens the settings
  panel BEFORE hiding the keymap overlay.** When the previous
  draft hid the overlay first, Android's synchronous focus search
  ran on the still-focused descendant and landed focus on a shelf
  cell — whose `onFocusChange` listener overwrote `focusedIndex`
  and called `ensureVisible`, scrolling the shelf. Showing the
  panel first moves focus to `ov` before `ko` goes GONE, so the
  shelf's `focusedIndex` and `scrollX` stay where the user left
  them.
- **Chip strip and keymap slot row icons reserve layout space
  via `View.INVISIBLE`** instead of dropping it via `View.GONE`.
  The earlier `GONE` design caused chips with cached icons to
  render wider than chips without; when an icon arrived
  asynchronously the chip visibly "popped" wider and shifted
  every chip after it along the strip. Same effect in the keymap
  slot list — the binding-label text moved sideways every time an
  async icon landed. `INVISIBLE` keeps the slot reserved so width
  and label position are stable across the entire icon-load
  lifecycle.
- **`SharedPreferences` handle cached as a field.** Was being
  resolved 12 times across the activity via repeated
  `getSharedPreferences(PREFS, MODE_PRIVATE)` calls, each walking
  Context's name → prefs HashMap. One field read per consumer
  now.
- **Reconcile path shares one `ArraySet<String>`** across the
  icon-cache invalidation, `pruneHiddenApps`, and `pruneKeyMap`
  helpers. Was building three separate sets from the same
  `freshFinal` data on every package broadcast; now one
  N-element pass + one allocation.
- **`IconDiskCache.purgeOrphans` runs on the write executor**
  instead of synchronously in the constructor. Same single-thread
  executor as future writes, so FIFO queue ordering naturally
  serialises the sweep before any `writeAsync` call (a freshly-
  written file cannot be mistakenly deleted). Drops ~30 ms of
  cold-start UI-thread blocking on installs with a heavy icon
  cache.
- **`loadIconBlocking` captures `dp(ICON_DP)` once per call.**
  Without the snapshot, `processIcon`'s internal `dp(ICON_DP)`
  re-read could race a mid-call density change (HDMI swap,
  multi-window resize) and produce a `B`-sized bitmap that gets
  stored under an `A`-keyed filename. The capture guarantees the
  read key, the rendered bitmap dimensions, and the write key
  all agree on one resolution.
- **ProGuard rules tightened.** Removed
  `-keep public class com.bare.launcher.LauncherActivity { *; }`
  and `-keep class com.bare.launcher.LauncherActivity$* { *; }`.
  Both were over-broad. The class-name keep is already covered
  by AGP's default `-keep public class * extends android.app.Activity`,
  and the `{ *; }` member wildcards prevented R8 from
  renaming/inlining private members. The launcher uses zero
  reflection / XML inflation / Parcelable / `android:onClick`,
  so R8's standard data-flow analysis correctly tracks every
  reachable member through the existing entry points.
  Also removed the unused `-assumenosideeffects class
  android.util.Log { v/d/i }` rule (codebase has zero `Log.v/d/i`
  calls). `classes.dex` shrank from 97 KB to 78 KB (~20 %
  smaller).

### Fixed

- **Hidden apps appeared on every cold start until the user
  round-tripped the hide manager.** Cold-start ordering bug: the
  cache pre-paint inside `loadApps()` ran with an empty
  `hiddenApps` set because `loadHiddenApps()` was called AFTER
  `loadApps()`. The shelf rendered the full `appList` with no
  filter; the only paths that re-filtered later were either a
  PM-scan reconcile that detected `changed = true` (rare) or a
  user-initiated hide-manager toggle. User-reported bug; fixed
  by reordering `onCreate`.
- **Drawer auto-shifted left or right when closing the keymap
  card opened from the settings panel.** Synchronous focus-search
  side-effect: `ko.setVisibility(View.GONE)` triggered a focus
  search that landed on the closest shelf cell, whose
  `onFocusChange` listener overwrote `focusedIndex` and called
  `ensureVisible`. Fixed by showing the settings panel (which
  grabs focus on `ov`) before hiding the keymap overlay.
- **Chip width jiggle on async icon load.** See the *Changed*
  entry above for the `GONE` → `INVISIBLE` switch.
- **Stale keymap entries after package removal.** See the new
  `pruneKeyMap` helper above.
- **DPI-change race in `loadIconBlocking`.** See the
  `dp(ICON_DP)` capture above.
- **Self-assignment near-miss in PR #57's first cut.** A bulk
  `sed` replacement of `getSharedPreferences(PREFS, MODE_PRIVATE)`
  with `prefs` accidentally rewrote the initialisation line into
  `prefs = prefs;`, leaving the field uninitialised. Every
  subsequent `prefs.getString` would have NPE'd on cold start;
  R8 silently dead-code-eliminated ~33 KB of dex through the
  always-throwing-NPE paths. Caught during pre-merge dex-size
  validation (a 33 KB drop on a tiny refactor is a red flag, not
  a celebration). Shipped with the explicit initialisation
  restored — verified by `apkanalyzer dex packages` showing the
  expected 49 classes / 188 methods (vs the broken version's
  30 / 100).

### Tests / CI

- 5 unit-test classes, **93 tests, 0 failures, 0 errors**
  (was 4 classes, 63 tests pre-1.4.0; +1 class, +30 tests).
- `:app:lintDebug` — 0 errors, 23 pre-existing warnings unchanged.
- `:app:assembleRelease` — release APK 95,208 bytes (was 101,492
  in 1.3.6 — `classes.dex` shrank from 97,496 to 78,088 bytes).
- `:app:assembleDebugAndroidTest` — instrumentation smoke test
  compiles against the new helper layout.

### Versioning

- `versionCode` 17 → 18, `versionName` 1.3.6 → 1.4.0. New
  backward-compatible functionality (the cache layer is purely
  additive — every failure mode falls through to the pre-1.4.0
  PM-scan-and-decode path) — SemVer MINOR bump.

## [1.3.6] — 2026-05-30

Single-fix manifest patch that makes BareLauncher discoverable by
third-party "set default launcher" / "Launcher Manager" tools
popular on XDA, and to any other OS surface that probes for
launcher activities via the canonical
`queryIntentActivities(MAIN+LAUNCHER, MATCH_DEFAULT_ONLY)` call.

### Fixed

- **`AndroidManifest.xml` adds `CATEGORY_LAUNCHER` to the home
  activity's intent filter.** The launcher previously declared
  `MAIN`+`HOME`+`DEFAULT` (system Default-launcher picker) and
  `MAIN`+`LEANBACK_LAUNCHER` (Android TV "All apps" row) but **not**
  `MAIN`+`LAUNCHER`, which is the category third-party launcher
  managers and "open with" pickers use to enumerate installed
  launcher apps. Without it, BareLauncher was invisible to those
  tools — the user-reported bug was "the app does not appear in
  Launcher Manager from XDA when I try to set it as default
  launcher." Adding `LAUNCHER` to the same intent filter (rather
  than as a separate filter) is the canonical Android pattern;
  Google's AOSP Launcher3 ships the same combined
  `HOME`+`DEFAULT`+`LAUNCHER` filter on its main activity. The
  launcher's own `queryApps()` already self-filters by
  `getPackageName()`, so the new visibility does **not** make our
  activity appear on its own home shelf — it only affects external
  enumeration.
- **`AndroidManifest.xml` adds `CATEGORY_DEFAULT` to the leanback
  filter.** Hygiene: without `DEFAULT`, the leanback filter does
  not match `queryIntentActivities` calls that pass
  `MATCH_DEFAULT_ONLY` (the `PackageManager` default flag). Some
  third-party TV launcher-manager tools combine
  `MATCH_DEFAULT_ONLY` with a `LEANBACK_LAUNCHER` probe; without
  `DEFAULT` they would still miss our activity. Also matches the
  shape of every well-known TV launcher manifest.

### Verified

- All four other `Intent.CATEGORY_HOME` consumers in the codebase
  (the system `ACTION_MAIN`+`HOME` boot resolution, the
  `<queries>` block, the predictive-back chain, and
  `LauncherActivity` itself) continue to work unchanged — adding
  `LAUNCHER` to the same filter does not narrow the existing
  matches, only widens the set of queries that find this activity.
- `queryApps()` self-filter (`if (ai.packageName.equals(self))
  continue;`) means the launcher does not list itself on the home
  shelf despite now matching `MAIN`+`LAUNCHER` enumeration.
- Java sources, ProGuard rules, lint rules unchanged. Manifest-only
  patch.

### Versioning

- `versionCode` 16 → 17, `versionName` 1.3.5 → 1.3.6. Single bug
  fix, no behaviour change for users who already had the launcher
  set as default — SemVer PATCH bump.

## [1.3.5] — 2026-05-30

Patch release with four small, low-risk fixes that remove the
remaining per-interaction allocation, eliminate a redundant repaint
loop during the cold-start icon flood, drop dead code from
`ClockFormatter`, and make the CI smoke test deterministic on slow
emulators.

### Changed

- **`onIconLoaded` short-circuits the keymap slot-card repaint when
  the loaded package isn't bound to any remappable key.** Previously,
  while the user had the settings panel or keymap card open during a
  package broadcast or a slow icon load, every icon delivered to the
  shelf also triggered a full repaint of the 6-row slot card — even
  though the slot card only shows icons / labels for packages that
  are bound to a key. The cold-start flood is ~50 deliveries on a
  typical TV, so the worst case was ~50 redundant card repaints in a
  single second. Now `onIconLoaded` walks the in-memory `keyMap`
  (≤ 6 entries) and only repaints when the just-loaded package is
  actually rendered somewhere on the card. No behaviour change, just
  less wasted work.
- **`anchorCardUnderGear` no longer allocates per call.** The two
  `int[2]` scratch arrays used to read the gear pill's and the
  root view's on-screen positions are now instance fields
  (`anchorMbLoc` / `anchorRootLoc`), matching the existing pattern
  for `ringCellLoc` / `menuCellLoc`. This was the only remaining
  per-overlay-open allocation in the modal path. One call per
  overlay open, so the GC win is tiny — but it closes the last open
  hole in the "no allocations after onCreate" goal.
- **`ClockFormatter` drops two unused single-arg overloads.** The
  zero-default `shouldRepaint(long)` and `format(long)` methods
  existed as backwards-compat wrappers but had no callers anywhere
  in the codebase (verified by ripgrep across `main` + `androidTest`
  + `test`). Pure dead-code removal — smaller class, smaller method
  table, identical behaviour.

### Tests / CI

- **Smoke test migrated from `ActivityTestRule` + `Thread.sleep(500)`
  to `ActivityScenario` + `Instrumentation.waitForIdleSync`.** The
  old approach was deprecated since androidx.test 1.4 and was a
  documented flake source on the slow API-29 KVM emulator we run in
  CI: the 500 ms sleep was a guess that was sometimes too short on a
  cold emulator (false negatives) and always wasted on a fast device.
  `ActivityScenario` is the modern recommended primitive, and
  `waitForIdleSync` blocks until the main looper has actually
  drained — both deterministic and faster. Removed the
  `androidx.test:rules:1.6.1` dependency (no longer needed) and
  declared `androidx.test:core:1.6.1` directly (the host of
  `ActivityScenario`, previously transitive only).

### Versioning

- `versionCode` 15 → 16, `versionName` 1.3.4 → 1.3.5. Patch release,
  no user-visible behaviour change — SemVer PATCH bump.

## [1.3.4] — 2026-05-30

Patch release that finishes the gear-glyph design pass and confirms
the codebase audit. One visual change, plus a clean bill of health
across every hot-path scan we have.

### Changed

- **Gear glyph trimmed from 8 teeth to 6, with rounder tooth caps.**
  The v1.3.3 8-tooth gear read slightly busy at the dp(40) toolbar
  pill size — the teeth filled most of the angular budget, leaving
  little visible gap between them. The v1.3.4 6-tooth gear has a
  60° step instead of 45°, opening up wider angular gaps that read
  as the canonical Material / iOS / tvOS settings gear at TV viewing
  distance. Per-tooth corner radius bumped from 30 % to 45 % of the
  tooth width so each tooth reads as visibly rounded rather than
  rectangular (the user's "rounded teeth" feedback). Tooth length
  also bumped slightly (0.18r → 0.20r) so the silhouette outer
  extent grows from ~0.58r to ~0.60r — still comfortable breathing
  room inside the pill rim, but the teeth now take a meaningful
  visual share of the glyph silhouette. Body and hole sizes
  unchanged. Drawn via the same allocation-free
  `Canvas.save / rotate / drawRoundRect / restore` loop as v1.3.3,
  just iterating 6 times at 60° instead of 8 times at 45°.

### Audit

A deep pass across every source file confirmed the codebase is in
a clean, optimised state:

- **Zero per-frame allocations** in any `onDraw`. `grep -E "new
  (Rect|Paint|RectF|Matrix|Path|Canvas|String|StringBuilder|.*\\[)"`
  scoped to `protected void onDraw` blocks returns no matches.
  Every drawing primitive that a hot-path view needs (Paint,
  RectF, Path, Bitmap scratch buffers) is held as a final field
  on the View and reused across frames.
- **Zero orphaned string resources.** Cross-reference of
  `name="..."` entries in `strings.xml` against `R.string.*`
  references in Java + `@string/...` references in
  `AndroidManifest.xml` matches exactly.
- **Zero dead fields.** Every private field in
  `LauncherActivity` is either written-and-read, written by
  framework lifecycle and read by the launcher, or kept
  intentionally as a final-cleared reference for the destroy
  path's null-out.
- **Zero unused imports.** `javac --release 17 -Xlint:all` reports
  the same 3 platform-API deprecation warnings the codebase has
  carried since 1.1.x (`TRIM_MEMORY_COMPLETE`, `TRIM_MEMORY_MODERATE`,
  `Drawable.getOpacity`) and nothing else.
- **Per-row indicator selection diff is O(2)**, not O(N). The
  `ShortcutTagView.setSelectedState` short-circuit means UP/DOWN
  navigation in the keymap card invalidates exactly two rows per
  press (the row losing selection and the row gaining it),
  regardless of total row count.
- **Drop-down anchor logic deduplicated** in v1.3.3 stays
  deduplicated — `anchorCardUnderGear` is the single source of
  truth for both the settings panel and the keymap card.
- **Hot paths preserved.** Shelf scroll, focus animation, key
  dispatch, icon delivery, package-broadcast refresh, clock tick
  all execute the same code paths with the same allocation
  profile as 1.3.3.

### Versioning

- `versionCode` 14 → 15, `versionName` 1.3.3 → 1.3.4. Patch
  release, no behavioural change beyond the visual gear refresh —
  SemVer PATCH bump.

## [1.3.3] — 2026-05-30

Patch release that tightens four real-device design details on the
v1.3.2 build and folds a duplicated anchor block into one helper.

### Changed

- **Toolbar pill order swapped: `[ wifi ] [ ⚙ gear ]`.** WiFi now sits
  leftmost in the top-right cluster (was rightmost in v1.3.0 / 1.3.1
  / 1.3.2). Pressing UP from any home shelf cell still lands on WiFi —
  that wiring was already in place — and now WiFi is also the cell
  visually closest to the centre of the shelf below, so the d-pad
  path from "browsing apps" to "checking my network" is the
  shortest possible single keypress. The gear pill takes the
  right-edge slot. WiFi and gear DOWN handlers updated so "below me
  is the cell visually under me" stays consistent: WiFi DOWN lands
  on the FIRST shelf cell, gear DOWN lands on the LAST. WiFi LEFT
  wraps to the last shelf cell; gear RIGHT wraps to the first.
- **Solid filled gear glyph.** v1.3.0 / 1.3.1 / 1.3.2 used a stroke-
  only line gear: two ring strokes plus 8 short radial line segments.
  v1.3.3 replaces it with a chunky filled silhouette: 0.40r body
  disc, 8 rounded-rectangle teeth at 45° increments, 0.16r centre
  hole drawn in the pill plate colour so the cut-out reads as
  continuous against the underlying dim backdrop. Outer extent
  ~0.58r — comfortable breathing room before the pill rim. Drawn
  via `Canvas.save / rotate / drawRoundRect / restore` — zero
  per-frame allocations, no new resources.
- **Keymap card Menu / Subtitle indicator glyphs tightened.** The
  v1.3.2 hamburger and CC were drawn at ~0.78 of the dp(11)
  container — visually larger and heavier than the colour discs
  next to them, breaking the "small and symmetric" goal. v1.3.3
  scales them to match the colour disc's visual footprint:
  hamburger lines span ~64 % of the container (matching the dot's
  diameter); CC text size shrinks to 0.62 of container height; both
  are centred via the actual rendered glyph bounds (`getTextBounds`
  with a per-View cached `Rect`) instead of the typographic
  baseline metrics, which sat slightly low.
- **Keymap card glyph colour inverts on row selection.** The v1.3.2
  hamburger and CC were always drawn warm white. When the row was
  selected (bright frosted-white pill background), the white glyph
  blended into the white pill and disappeared — the
  "white-selector-blends, icon not visible" issue. v1.3.3 flips the
  glyph colour to near-black when the row is selected (matching the
  row label's selected colour). Colour discs ignore the selection
  state — saturated red / green / yellow / blue read on either
  backdrop, and inverting them would change their identity. New
  `ShortcutTagView` inner class holds a `selectedState` bool;
  `refreshKeymapRows` calls `setSelectedState` on each row's
  indicator, which short-circuits when the state hasn't changed.

### Audit

- **Drop-down anchor logic factored to a single helper.**
  `showSettingsPanel` and `showKeymapOverlay` had ~25 lines of
  identical anchor math: `getLocationOnScreen` for both the gear
  pill and root, compute the gear's bottom-right corner, derive
  top/right margins on the card. Folded into
  `anchorCardUnderGear(card, defaultTop, defaultRight)` — both
  call sites now just hand the card and a fallback margin pair
  to the helper.
- **Allocation hygiene preserved.** The new `ShortcutTagView`
  carries one cached `Rect` for `Paint.getTextBounds` so the
  keymap card's CC row stays zero-alloc per draw (without a
  cached Rect, `getTextBounds` allocates internally). The new
  filled gear's 8-tooth loop uses `Canvas.save/rotate/restore`
  instead of a scratch `Matrix` to keep the gear render
  allocation-free.
- **Removed the old `drawShortcutGlyph` static helper signature**
  in favour of one that accepts the selected state and the
  cached Rect. The old signature took `Paint` only and rendered
  the glyph the same way regardless of selection.

### Versioning

- `versionCode` 13 → 14, `versionName` 1.3.2 → 1.3.3. Patch release,
  no behavioural change for users who don't open the keymap card or
  look at the toolbar pills closely — SemVer PATCH bump.

## [1.3.2] — 2026-05-30

Patch release fixing two real-device bugs in v1.3.1 and a pair of
design-pass refinements requested after the unified settings panel
shipped.

### Fixed

- **Back from "Manage hidden apps" no longer flashes the Button
  shortcuts list.** The v1.3.1 close path called `hideKeymapOverlay`,
  which reset the keymap card to SLOTS mode at the *top* of the
  method — that swap fired BEFORE the 110 ms close animation, so the
  user briefly saw the slot column visible behind the fading-HIDE
  card before the card finished animating out. v1.3.2 splits the
  close path in two: when the user is transitioning back to the
  settings panel (the `keymapOpenedFromSettings` case),
  `hideKeymapOverlay` snap-closes the card immediately (no animation,
  no SLOTS reset) and synchronously opens the settings panel. The
  shared backdrop stays solid throughout, so the dim level never
  flickers. The home → keymap → home path keeps the original animated
  close.
- **Settings panel cursor returns to the row you came from.** v1.3.1
  always reset `settingsSelectedRow = 0` in `showSettingsPanel`, so
  drilling into "Button shortcuts" and pressing Back landed back on
  "Manage hidden apps" instead. v1.3.2 adds a `pendingSettingsCursor`
  field — set in `activateSettingsRow` before the keymap-card hand-off,
  consumed in `showSettingsPanel`, reset to 0 immediately so the next
  fresh open from the gear pill still starts at row 0. Cursor now
  reads naturally: `gear → row 0 → click "Button shortcuts" → keymap
  card → BACK → row 1 → BACK → home → gear → row 0 again`.

### Changed

- **Settings panel rows are label-only — chevrons removed.** The four
  drill-through rows (Manage hidden apps, Button shortcuts, Set
  wallpaper, System Settings) no longer render the `›` indicator. The
  panel reads as a clean vertical list of action labels and shrinks
  tighter around the longest label. `refreshSettingsRows`'
  `instanceof TextView` guard handles the missing-indicator case
  cleanly (null short-circuits). The Show clock toggle row keeps its
  ✓ indicator since it carries functional state, not navigation
  affordance.
- **Keymap card rows show a glyph indicator in every slot.** v1.3.0 /
  v1.3.1 had a coloured disc next to the four colour keys (Red /
  Green / Yellow / Blue) and a *transparent* placeholder next to
  Menu and Subtitle so the name column still aligned. v1.3.2 makes
  every row carry a real visual indicator: Menu shows a 3-line
  hamburger glyph, Subtitle shows a "CC" closed-captions badge, all
  drawn in `Canvas` with the same allocation-free `Paint` the colour
  discs use. New `SHORTCUT_GLYPHS` array packs the per-row indicator
  kind alongside `SHORTCUT_TAGS` (the colour). Container size grew
  from `dp(7)` to `dp(11)` so the hamburger and CC stay legible at
  TV viewing distance — the dot variant scales its drawn radius down
  by 0.64 to keep the visual disc size identical to v1.3.1.

### Audit

- Removed the dead `settingsShowClockCheck` TextView field. v1.3.0
  cached it for the toggle handler with the comment "so toggling
  repaints only that one indicator without scanning child indices,"
  but `refreshSettingsRows` actually walks the row's children by
  index (`row.getChildAt(1)`) — the cached field was set on build
  and never read. Removing it shrinks the field-clear list in
  `onDestroy` by one line and removes one paragraph of misleading
  documentation.
- New `drawShortcutGlyph` static helper consolidates the dot /
  hamburger / CC drawing into one allocation-free method, called
  from each row's tiny anonymous `View` subclass. Six rows × one
  helper call per onDraw, zero per-draw allocations.

### Versioning

- `versionCode` 12 → 13, `versionName` 1.3.1 → 1.3.2. Patch release,
  no behavioural change for users who don't open the keymap card —
  SemVer PATCH bump.

## [1.3.1] — 2026-05-30

Patch release fixing four real-device issues found after v1.3.0 shipped,
plus an audit pass that tidies a couple of small things.

### Fixed

- **Settings panel auto-fits its content width.** v1.3.0 used a fixed
  `dp(252)` card width, which left ~70 dp of dead space to the right
  of short labels like "Show clock". The card is now `WRAP_CONTENT`
  and a post-build measure equalises every row to the widest one
  (same pattern the keymap card has used since 1.1.x), so the panel
  hugs the longest label + chevron with no extra space and the
  chevron column still aligns vertically across rows.
- **Back from "Manage hidden apps" returns directly to the settings
  panel, not to the keymap card's Button-shortcuts list.** The user
  reached HIDE from the settings panel, never opened SLOTS, and
  pressing Back was incorrectly dropping them into SLOTS as if they
  had drilled in from the slot list. New flag
  `hideManagerSkipSlotsOnExit` short-circuits that return path when
  HIDE was entered from the panel, so the back-stack reads
  `gear → settings → hide apps → BACK → settings → BACK → home` in
  a clean single press per step.
- **One dim across the whole modal flow, no re-dim flicker.** v1.3.0
  gave the settings panel and the keymap card their own
  `setBackgroundColor(0x33000000)` backdrops. Transitioning
  settings → keymap meant the panel's backdrop animated out while
  the keymap card's backdrop animated in, briefly compositing
  ~0x5C-black over the wallpaper and reading as "the screen just
  got darker for half a second". The dim is now provided by a
  single shared `overlayBackdrop` view at root z-order. `show*`
  methods call `ensureOverlayBackdropVisible` (idempotent — no-op
  when already up); `hide*` methods call
  `dismissOverlayBackdropIfIdle` (only fades the backdrop when no
  other overlay is logically open, including a queued re-open via
  `keymapOpenedFromSettings`). Net effect: dim fades in once on the
  first overlay, stays at constant 20 % alpha across every
  in-flow transition, fades out once when the last overlay closes.
- **Gear glyph is smaller and sits comfortably inside the pill.**
  v1.3.0 sized the gear at body radius `0.66r` + `0.20r` teeth,
  reaching ~0.93r of the pill — visually almost rim-to-rim. Tuned
  down to body `0.56r` + `0.14r` teeth + `0.13r` stroke, outer
  extent ~0.77r. Same 8-tooth gear shape, ~17 % smaller relative
  to the pill, ~23 % breathing room before the rim.

### Audit

- Removed a dead `toothW` constant inside
  `AppleStyle.drawGearGlyph` that v1.3.0 declared "kept for design
  intent" but didn't actually use. The unreachable
  `if (toothW < 0f)` line that prevented the unused-local-variable
  warning is gone with it.
- Added a `destroyed` guard on the 60 ms `postDelayed` that
  re-opens the settings panel after the keymap card closes. The
  lambda was already safe (settings re-open short-circuits when
  `root == null`) but the explicit guard makes the intent obvious
  and saves the unnecessary `buildSettingsPanel` no-op call when
  the activity has been torn down inside the 60 ms window.
- Added a `destroyed` guard at the top of both
  `showSettingsPanel` and `showKeymapOverlay` so a stale callback
  routed in from a focus listener or animation cannot resurrect
  an overlay during teardown.
- The `exitHideManager` "land focus on the manage-row" logic
  pre-1.3.0 set `keymapSelectedRow = SHORTCUT_LABELS.length` —
  index 6, which was the v1.2.x manage row. That row no longer
  exists; the index now resolves to nothing. Updated to
  `keymapSelectedRow = 0` so the in-keymap-card SLOTS-from-HIDE
  fallback (an unreachable code path under the new
  hide-from-settings-only flow, but kept defensively) lands on a
  valid row.

### Versioning

- `versionCode` 11 → 12, `versionName` 1.3.0 → 1.3.1. Bug-fix
  release, no breaking changes — SemVer PATCH bump.

## [1.3.0] — 2026-05-30

Top-right toolbar consolidation and the launcher's first user-facing
preference toggle. The wallpaper pill is gone, the mapper "sliders"
glyph becomes a gear, and a single dropdown panel under the gear pill
now hosts every non-daily-frequent action: hide apps, button
shortcuts, set wallpaper, show-clock toggle, and system Settings.
Net effect: the home screen drops from three top-right pills to two,
the daily WiFi action stays a single click on the rightmost edge,
and every config surface lives in one discoverable place.

### Added

- **Unified settings panel under the gear pill.** Short-press the
  gear opens a dropdown card with five rows in this order:
  *Manage hidden apps* › / *Button shortcuts* › / *Set wallpaper* › /
  *Show clock* ✓ / *System Settings* ›. Drill-throughs (the four
  rows with `›`) close the panel before launching their next
  surface; the *Show clock* row toggles in place so the user can
  flip it without leaving the panel. The *Button shortcuts* and
  *Manage hidden apps* rows drill into the existing keymap card;
  pressing **Back** from inside that card returns the user to the
  settings panel rather than dropping them at the home shelf, so a
  deep `gear → settings → button shortcuts → bind a key → back`
  gesture lands exactly where the user left off in the panel.
  Visual language matches the keymap card (deep slate plate + 1 dp
  white rim, drop-down animation pivoted at the gear's top-right
  corner). 252 dp wide, ~5 rows tall — actually smaller than the
  pre-1.3.0 keymap card.
- **Show clock toggle.** Default on (existing installs see no
  behaviour change). When on, the clock pill renders with a
  locale-aware short day-of-week prefix and the time:
  `Sat · 12:34 PM`. The day prefix uses
  `Calendar.getDisplayName(DAY_OF_WEEK, SHORT, Locale.getDefault())`
  so every system language renders correctly without changes to the
  launcher's English-only resource bundle. When off, the pill is
  hidden and the minute tick is *not scheduled* — zero CPU cost
  per minute on installs that opt out, matching the v1.3.0 design
  contract that any new feature must be free when unused. The day
  toggle is intentionally bundled with the time toggle: a real
  user wanting the time but explicitly *not* wanting the day name
  is hypothetical, so a single control covers both.
- **Long-press the gear pill → system Settings.** The most common
  destination from the panel and the muscle-memory shortcut moved
  over from the WiFi pill so it sits next to the gear's short-press
  ("open panel") gesture. WiFi long-press becomes unbound — a
  reserved slot for a future power-user shortcut without committing
  to a feature now.
- **Tap-outside-the-card dismisses the settings panel** with a 20%
  black backdrop dim, identical to the keymap card and reorder-mode
  context menu. TV-remote users get the same behaviour via the
  Back button / predictive-back gesture.

### Changed

- **Top-right toolbar drops from three pills to two.** The wallpaper
  pill is removed entirely; its action ("set wallpaper from a
  storage picker") now lives at `Settings → Set wallpaper`. The
  remaining two pills are `[ ⚙ gear ] [ wifi ]` with the WiFi pill
  flush at the rightmost edge so its physical position never moves
  for users with muscle memory from v1.2.x. Total horizontal
  footprint shrinks from ~132 dp to ~88 dp.
- **Mapper sliders glyph becomes a gear.** The pill is no longer the
  "remap remote buttons" entry — it's the unified settings entry —
  so the visual symbol updates to match. The gear is drawn entirely
  with `Canvas` primitives via the new
  `AppleStyle.drawGearGlyph(canvas, cx, cy, r, color, stroke)`
  helper: 8 teeth, body ring, inner hole, all proportional to the
  pill radius. No new vector or raster resources.
- **WiFi pill long-press is now unbound.** The system-Settings
  shortcut moved to the gear pill (where it sits next to its panel
  row, the most common destination). Importantly, no
  `OnLongClickListener` is registered on the WiFi pill — registering
  one that returns `true` would *swallow* long-press events; leaving
  the listener absent makes long-press a clean no-op and short-press
  still fires on key UP / touch UP as before.
- **Keymap card is now strictly key-binding territory.** The
  v1.2.x "Manage hidden apps" 7th row + hairline divider that used
  to sit at the bottom of the slot column moved into the unified
  settings panel. The HIDE sub-mode the manage row used to enter
  still exists and is reachable via `Settings → Manage hidden apps`.
  `handleKeymapSlotsKey` row count drops from
  `SHORTCUT_LABELS.length + 1` back to `SHORTCUT_LABELS.length`,
  and the dedicated `keymapManageRow` paint branch in
  `refreshKeymapRows` is gone.
- **WiFi pill's d-pad navigation updated for its new rightmost
  position.** `DOWN` now lands on the *last* shelf cell (taking
  over the wallpaper pill's behaviour, since "below me is the cell
  visually under me" stays consistent). `RIGHT` wraps to the *first*
  shelf cell — symmetric with the gear's `LEFT` wrap to the last
  shelf cell. `LEFT` focuses the gear (the only neighbour to the
  left now).

### Performance

- **Show clock = off costs zero per minute.** The minute-aligned
  `clockTick` `postDelayed` chain is never started when the toggle
  is off. `tickClock` short-circuits before any allocation,
  `Calendar` mutation, or `Spannable` work. The `clockView`
  `setVisibility(GONE)` is set in `buildLayout` (avoiding a
  one-frame visible-then-hidden flash on cold start) and reasserted
  on every `onResume` via `startClock`'s opt-out branch. Toggle
  flip from off → on resets `clockFmt` so the next paint runs
  unconditionally; toggle flip from on → off calls `stopClock`,
  removing the pending callback from the looper queue.
- **Settings panel is lazy-built** on first
  `showSettingsPanel()` — same pattern as the keymap card and
  reorder-mode context menu. Cold-start view-tree work pays only
  for what the user actually opens. The panel itself is ~12 view
  allocations, 5 click listeners, 1 backdrop. Re-used across
  opens, torn down on activity destroy.
- **Wallpaper pill removal saves a `View` + a `Path`-cached
  landscape-glyph onDraw.** Every focus-change paint on the
  toolbar dropped one cell from its work; layout-pass cost in
  `buildLayout` shrinks by ~30 lines of view setup.
- **Gear glyph is allocation-free per draw.** The 8 tooth angles
  are computed inline via `Math.cos`/`Math.sin` (HotSpot inlines
  both on x86 and arm64); the caller-owned `stroke` paint is
  reused across paints with only colour / width / cap / join
  mutated. No per-frame allocations.

### Localisation

- **Day-of-week prefix is locale-aware.** Uses
  `Calendar.getDisplayName(DAY_OF_WEEK, SHORT, Locale.getDefault())`
  so a Spanish-locale device renders `Sáb · 12:34 PM` and a
  Japanese-locale device renders `土 · 12:34 PM` — without any
  changes to the launcher's English-only `strings.xml`. The middle
  separator (`·`, U+00B7) is locale-neutral. Falls back gracefully
  to the time-only render if the platform ever returns null from
  `getDisplayName` (some stripped-down ROMs have been observed
  shipping with broken `DateFormatSymbols`).

### Versioning

- `versionCode` 10 → 11, `versionName` 1.2.2 → 1.3.0. Feature
  work, no breaking changes, no behavioural change for anyone who
  doesn't open the new settings panel.

## [1.2.2] — 2026-05-30

Two related fixes in the hide-app drawer / keymap-picker management
UIs. The first restores icons to chips that were rendering label-only
for previously hidden apps. The second closes a separate stale-cache
bug exposed by the first: chip strips were not being invalidated when
the user reordered apps on the home shelf, so chip *i* could carry
the OLD label / icon while the toggle path resolved the package via
the NEW `appList[i]` — selecting a chip showing app A could end up
toggling app B. Both bugs are scoped to the management UIs; the home
shelf itself was always correct.

### Fixed

- **Reorder swap no longer desyncs the hide-manager / keymap-picker
  chip strips.** `RecyclingShelfView.swapWithNeighbour` mutates
  `appList` in place to mirror a home-shelf drag-and-drop, but the
  hide-manager and keymap-picker chip strips are size-cached
  (`keymapHideBuiltSize` / `keymapPickerBuiltSize` == `appList.size()`
  → skip rebuild). A reorder leaves the size unchanged, so the
  cache check passed and the chip strip survived from before the
  swap. Chip *i* still carried the old label and icon, but
  `toggleSelectedHide` and the keymap-picker commit path resolved
  the package via `appList[i]` at the *new* position. The
  user-visible symptom was "I select chip showing app A, app B
  gets toggled" for any pair the user just rearranged on the home
  shelf. The package-broadcast handler already invalidates these
  caches on install / uninstall / replace; reorder is the third
  class of `appList` mutation that needs the same nudge. The
  row-equalize flag is also flipped because the slot-row miniatures
  rendered alongside the slot list can land at a different
  equalised width when the underlying app order shifts.
- **Hide-manager / keymap-picker chips no longer render icon-less for
  previously hidden apps.** Root cause was lifecycle-scoped: the
  `iconCache` LRU is populated lazily, and the only writer path
  (`setApps(displayed)` → `preWarmIcon` per visible cell) ran
  exclusively over the *filtered* shelf list. Hidden apps were
  removed from the shelf upstream, so their bitmaps never reached
  `iconCache`. `buildHideChips` and `rebuildPickerChips` then read
  `iconCache` at chip-build time, found a miss, and emitted the
  chip with `ImageView.setVisibility(GONE)`. Worse: each strip
  cached its built children and only rebuilt when `appList.size()`
  changed, so the icon-less chip persisted across hide/unhide
  cycles for the lifetime of the activity. Fix is in
  `LauncherActivity.applyShelfApps`: the loop that filters hidden
  apps off the shelf now also kicks `preWarmIcon(a)` for the hidden
  ones, keeping `iconCache` complete for every installed app
  regardless of whether the shelf renders it. Same fix path
  benefits the keymap slot-row icon miniatures (which read from
  `iconCache` for the bound package).
- **`enterHideManager` / `enterAppPicker` top up icons on cached
  chip strips.** A chip built before its bitmap was loaded would
  otherwise stay icon-less even after `iconCache` later gained the
  entry, because the size-based build cache short-circuits the
  rebuild. Both entry points now run a cheap allocation-free pass
  (`refreshHideChipIcons` / `refreshPickerChipIcons`) that walks
  the existing children, identifies the `ImageView`s still flagged
  `GONE`, and resolves them against the current `iconCache`. The
  pass is `O(N)` over the strip and short-circuits on chips that
  already have their bitmap.
- **Icon-delivery callbacks live-update any open chip strip / slot
  row.** New `onIconLoaded(pkg, bitmap)` hook, invoked from both
  `preWarmIcon` and `loadIconAsync`'s `runOnUiThread` block once
  the bitmap is in cache and shelf delivery is complete. The hook
  early-outs in a single field-read + visibility check when the
  keymap overlay isn't on screen (the common case) so the icon-
  flood path on cold start pays effectively nothing. When the
  overlay is open, the hook resolves the package to its
  `appList` index, updates the matching chip's `ImageView`, and
  for slot mode triggers a `refreshKeymapRows` repaint. Net effect
  for the user: hidden-app icons populate live in the management
  UIs as the cache fills, instead of requiring an overlay
  close-and-reopen cycle.

### Versioning

- `versionCode` 9 → 10, `versionName` 1.2.1 → 1.2.2.

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
