plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "com.bare.launcher"
    // API 36 (Android 16). AGP 8.10+ supports compiling against API 36 —
    // see https://developer.android.com/build/releases/agp-8-10-0-release-notes
    // "The maximum API level that Android Gradle Plugin 8.10 supports is API
    // level 36." We're on 8.10.1 in libs.versions.toml so no AGP bump needed.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bare.launcher"
        minSdk        = 26
        // targetSdk = 36 to opt into Android 16 platform behaviours (and to
        // satisfy Play Store's evergreen target-API requirements). The
        // launcher does not use any APIs that changed semantics between
        // 35 and 36; the bump is a target-only change.
        targetSdk     = 36
        // 1.2.0: lower minSdk floor from 30 (Android 11) to 26 (Android 8).
        // Performance is independent of minSdk on Android — version-gated
        // branches via Build.VERSION.SDK_INT are evaluated by ART's branch
        // predictor in roughly one CPU cycle, and the kernel + framework
        // libraries on a device running Android 14 are unchanged regardless
        // of the APK's minSdk. The expanded floor unlocks roughly 25% more
        // of the Android-TV install base — Mi Box S, older Fire TV sticks,
        // many 2018-2020 Amlogic / Allwinner TV boxes — which is exactly
        // the hardware whose stock launcher is the kind of bloated thing
        // BareLauncher exists to replace.
        //
        // Why API 26 and not lower: Adaptive Icons were introduced at API
        // 26, and IconRenderer relies on AdaptiveIconDrawable as its
        // primary path. Lowering further would force a second rendering
        // strategy with a worse fallback for legacy bitmap icons. Raising
        // higher (API 28 / Android 9) would cut off the Mi-Box-S /
        // older-Fire-TV segment that's still in active daily use.
        //
        // Code impact: hideSystemUI now branches on R (API 30) — modern
        // WindowInsetsController path on R+, legacy SystemUiVisibility
        // flag path on API 26-29. Every other modern API surface in the
        // codebase was already correctly gated for TIRAMISU (API 33) or
        // earlier — predictive back, RECEIVER_NOT_EXPORTED, ResolveInfoFlags
        // — so the rest of the source compiles cleanly against the new
        // floor without further conditional code.
        //
        // 1.1.5: app-shelf de-dup fix + device-aware activity selection.
        // Apps that ship BOTH a phone-style CATEGORY_LAUNCHER activity and
        // a TV-style CATEGORY_LEANBACK_LAUNCHER activity used to appear
        // twice on the shelf — the dedupe key was "package/activity" so
        // the two distinct activity names of the same package both
        // passed. Dedupe is now by package name only, and the query
        // ORDER is chosen at runtime: LEANBACK-first on TV, LAUNCHER-
        // first elsewhere, so the activity that wins the dedupe race
        // matches the device's UI tuning. Phone-only and TV-only apps
        // continue to surface because both categories are still queried
        // — every installed launchable app (user, system, sideloaded)
        // shows up exactly once.
        //
        // 1.1.4: pre-public-release audit pass (5 fixes).
        // Wallpaper sub-sampling now caps memory on every aspect ratio
        // (calcSampleSize uses || not && — extreme-aspect panoramas could
        // previously skip sub-sampling and burn ~8 MB of bitmap memory
        // CENTER_CROP scaled away). Home-screen clock refreshes
        // immediately on time / timezone / date-changed broadcasts
        // (was up to 60 s stale after a flight, train, or DST
        // transition). addApps null-guards ActivityInfo.packageName so
        // stripped-down TV ROMs with malformed ResolveInfo don't NPE
        // their way into a blank shelf. CHANGELOG.md de-duplicated and
        // [1.1.3] section added so the tag-triggered release pipeline
        // emits the correct notes. Repository .gitignore added.
        //
        // 1.1.3: clock pill + wifi long-press + wallpaper layer hygiene.
        // The home-screen clock now wears the same dark-glass plate as
        // the toolbar pills (capsule shape, 1 dp white rim) and shrinks
        // from 44 sp + heavy shadow to a compact 22 sp pill that sits
        // symmetric to the toolbar across the top edge. WiFi pill gains
        // a long-press shortcut to general system Settings — short click
        // still opens WiFi/network settings exactly as before. Wallpaper
        // ImageViews drop their permanent LAYER_TYPE_HARDWARE which was
        // burning ~16 MB at 1080p / ~64 MB at 4K of GPU FBO continuously
        // for a 200 ms cross-fade. Also removes a stray pkgReloadRunnable
        // that could survive onDestroy() in the looper queue.
        // Bumped 22 → 23: 1.4.5 is a focused polish pass from the deep
        // post-1.4.4 audit. Three changes, all non-behavioural on the
        // happy path:
        //   • Settings-panel KEY_UP symmetry. The panel's dispatchKeyEvent
        //     branch used to swallow the UP edge of every non-DOWN key —
        //     including the device-control keys (volume / mute / power /
        //     media) that its own DOWN path already lets through. It now
        //     mirrors the keymap overlay's {@code isLetThroughKey}
        //     contract so AudioService / PowerManager see a balanced
        //     DOWN+UP pair on the rare ROMs (HDMI-CEC volume bridges,
        //     some set-top remotes) that route both edges to user space
        //     while the settings panel is open.
        //   • Per-AppInfo ellipsised-label memo. A shelf cell's label
        //     truncation is a pure function of constant inputs (cell
        //     width budget, label text size, typeface), so it is computed
        //     once per app and cached on the model instead of being
        //     re-measured + re-allocated (ellipsize → CharSequence +
        //     String) every time a recycled cell scrolls back onto an
        //     already-seen app during a fling. Cleared on a density /
        //     font-scale change so the truncation point stays correct.
        //   • Dropped the {@code android:roundIcon} set — the manifest
        //     attribute, the anydpi-v26 round adaptive XML, and the five
        //     round PNG fallbacks. On minSdk 26 every device resolves the
        //     adaptive {@code ic_launcher} and the platform derives the
        //     round / squircle / teardrop mask from it, so a separate
        //     round set was pure dead weight. Shrinks the source tree and
        //     the debug APK (the release APK already stripped them via
        //     shrinkResources). render_launcher_icons.py updated to match.
        //
        // SemVer PATCH bump — bug fix + hot-path perf + dead-resource
        // cleanup only. No new features, no API changes.
        //
        // Bumped 19 → 22: 1.4.4 ships three waves of post-1.4.1 audit
        // fixes. Each was a separate merged PR; they're collapsed into a
        // single versionCode bump because the intermediate states never
        // shipped to users.
        //
        // 1.4.2 (PR #61, hot-path perf — versionCode 20 conceptually):
        //   • IconRenderer.needsFill replaced the 290 KB
        //     Bitmap.copyPixelsToBuffer with 12 direct getPixel calls.
        //     ~25× faster per icon (12 × ~100 ns JNI vs ~30 µs memcpy)
        //     AND eliminated the per-worker ~290 KB ThreadLocal byte
        //     buffer (workers × 290 KB = ~870 KB – 1.2 MB of held
        //     memory across the icon-executor pool, gone).
        //   • IconRenderer.renderDrawable non-BitmapDrawable path
        //     collapsed from 2 transient bitmaps to 1 by drawing
        //     directly at target size via setBounds. Saves ~290 KB
        //     transient ARGB_8888 allocation per such icon at xxxhdpi.
        //   • iconExecutor pool sizing raised from
        //     Math.max(2, cores − 1) to Math.max(2, cores). On a
        //     4-core TV this is a ~25 % wall-clock reduction in
        //     cold-start icon flood (333 ms → 250 ms). Also
        //     defensively avoids the latent IllegalArgumentException
        //     that core=2 > max=1 would have triggered on a
        //     hypothetical 1-core device.
        //   • positionRing caches the activity root's screen location
        //     across calls. Saves one full View.getLocationOnScreen
        //     walk per call — at 60 Hz × 150 ms focus animation that's
        //     ~9 view-tree walks per focus event. Invalidated on
        //     onConfigurationChanged.
        //   • AppListCache.readFile uses Files.readAllBytes single-shot
        //     instead of the BufferedReader + char[] + StringBuilder
        //     loop. Faster cold-start cache pre-paint, fewer transient
        //     allocations.
        //
        // 1.4.3 (PR #62, paused-CPU savings — versionCode 21 conceptually):
        //   • Package broadcast receiver no longer schedules the
        //     400 ms-delayed loadApps reconcile while the activity is
        //     paused. New uiPaused field tracks pause state; the
        //     existing pkgChangedWhilePaused flag still triggers a
        //     reconcile in onResume so no broadcast is missed — the
        //     work is just deferred to when the user is actually
        //     looking. Saves ~50–250 ms of CPU per background package
        //     update + reduces process-LRU pressure (the pending
        //     looper message no longer pins the activity past the
        //     broadcast).
        //   • pkgReloadRunnable clears pkgChangedWhilePaused at the
        //     start so a successful in-foreground reconcile leaves the
        //     flag in the right state for the next pause/resume cycle.
        //     Eliminates a redundant onResume reload.
        //   • pkgReloadRunnable reused for the trim-memory and
        //     rejected-execution retry posts (was a fresh
        //     {@code this::loadApps} method-reference allocation per
        //     call). All three deferred-loadApps paths now share one
        //     cancellable Runnable.
        //   • Deleted the unused legacy
        //     {@code shutdown(ExecutorService)} helper retained from
        //     1.4.1 with @SuppressWarnings("unused").
        //
        // 1.4.4 (PR #65, defensive guards — versionCode 22):
        //   • getSystemService(ACTIVITY_SERVICE) null guard with a
        //     64 MB heap-class fallback. Stripped TV firmware (some
        //     Wear OS / IoT-derived ROMs that ended up running on
        //     cheap TV boxes) have been observed missing the service;
        //     without the guard the launcher NPEs in initCaches and
        //     dies on launch, leaving the user with a black home
        //     screen and no recovery short of factory reset.
        //   • WallpaperController.loadSystem catches OutOfMemoryError.
        //     wpDrawable allocates a screen-sized ARGB_8888 bitmap
        //     (8 MB at 1080p, 32 MB at 4K) which can OOM on
        //     memory-constrained TV boxes. Without the catch, the OOM
        //     propagates, kills the wallpaper executor thread, and the
        //     wallpaper never appears for the rest of the session.
        //   • WallpaperController.writeSnapshotBestEffort catches OOM.
        //     Bitmap.compress on a 1080p ARGB source needs ~10–20 MB
        //     of transient encoder workspace; can OOM under
        //     post-decode peak heap pressure on the same boxes.
        //   • IconDiskCache.writeSync catches OOM. Same shape as
        //     above for the per-icon disk-cache writes during the
        //     cold-start icon flood.
        //
        // SemVer PATCH bumps throughout — bug fixes, performance
        // improvements, and defensive hardening only. No new features,
        // no API changes, no resource changes. Release APK 95.7 KB →
        // 95.8 KB across the three releases (essentially unchanged).
        //
        // Bumped 18 → 19: 1.4.1 ships two PR cycles' worth of post-1.4.0
        // audit corrections.
        //
        // PR #59 (correctness + robustness):
        //   • Wallpaper bitmap recycle race fixed in
        //     WallpaperController.crossfade — the old / previous-back
        //     bitmaps now recycle via View.postOnAnimation so they
        //     land AFTER the next display-list refresh, not
        //     synchronously inside the same UI runnable as the
        //     setImageBitmap that scheduled it. On SkiaGL TV ROMs
        //     this eliminates the rare "Canvas: trying to use a
        //     recycled bitmap" crash mid-cross-fade.
        //   • onDestroy parallel shutdown. Pre-1.4.1 sequentially
        //     shut and awaited four executors with a 300 ms cap each
        //     — worst-case 1.2 s of UI-thread block visible as a
        //     stutter when navigating away. Each executor now splits
        //     into beginShutdown / awaitShutdown phases; the
        //     activity drains all four against a single shared
        //     300 ms wall-clock deadline.
        //   • Reconcile-time ResolveInfo grafting. Cache-sourced
        //     AppInfos carry ri = null (ResolveInfo isn't
        //     serialisable); the no-change reconcile branch now
        //     grafts ri from freshFinal[i] onto appList[i] so
        //     subsequent icon loads use the faster ri.loadIcon path.
        //     AppInfo.ri changed from final to volatile non-final.
        //   • Chip-strip rebuild during open keymap overlay so a
        //     package broadcast that lands while the user is inside
        //     PICKER / HIDE doesn't desynchronise chip-package
        //     mapping from appList.
        //   • Several smaller fixes: TRIM_MODERATE / BACKGROUND no
        //     longer clear iconInflight (was orphaning executor
        //     tasks); RejectedExecutionException in loadApps retries
        //     via uiHandler; pendingScrollIdx clamps against
        //     displayed-list size when hide-apps filters; iconCache
        //     / iconDiskCache / iconExecutor / appExecutor declared
        //     volatile.
        //
        // PR #60 (performance + hygiene — this PR):
        //   • IconRenderer plate-path collapsed from 2 transient
        //     ARGB_8888 bitmaps to 1 via PorterDuff.Mode.SRC_ATOP.
        //     ~290 KB transient saved per plate-path icon at
        //     xxxhdpi. Cold-start icon flood transient peak heap
        //     drops measurably. Pixel-equivalent to the previous
        //     "drawCircle + drawBitmap + clipToCircle" pipeline.
        //   • applyShelfApps reuses an instance-level scratch
        //     ArrayList — saves one allocation per package broadcast
        //     / hide-toggle when hidden_apps is non-empty.
        //   • equalizeKeymapRowWidths drops the redundant
        //     restore-prevW step (saves 6 setLayoutParams cascades
        //     per re-equalize).
        //   • CrashLogger uses static DateTimeFormatter instead of
        //     per-crash SimpleDateFormat (thread-safe, allocation-
        //     free per crash).
        //   • crossfade gains a top-of-method destroyed check.
        //   • lint.xml suppressions for 6 structurally-required
        //     warnings — drops warning count from 23 to 11.
        //   • Gradle wrapper 8.14.1 → 8.14.5; androidx.test family
        //     bumped to current. AGP 9.x deferred (breaking).
        //
        // SemVer PATCH bump — bug fixes and performance
        // improvements only, no new features or API changes.
        //
        // Bumped 17 → 18: 1.4.0 introduces the cold-start cache layer.
        // Three on-disk caches — wallpaper snapshot
        // ({@code filesDir/wallpaper.snap}), app list
        // ({@code filesDir/applist.cache}), and per-package icons
        // ({@code filesDir/icons/{pkg}-{px}.icn}) — paint the wallpaper,
        // the app shelf, AND the icons in the very first frame on every
        // cold start after the first. HARDWARE bitmap config on the
        // wallpaper saves 8–32 MB of Java heap (lives in graphics
        // memory instead). Resolution-keyed icon files self-invalidate
        // on DPI / display-mode changes (filename mismatch on the new
        // density → cache miss → fresh decode at the new size). All
        // four caches use atomic tmp + rename writes so a process kill
        // mid-write never leaves a corrupt file behind. ProGuard rules
        // tightened — removed the v1.x.x over-broad
        // {@code -keep ... { *; }} directives that were preventing R8
        // from minifying private members; classes.dex shrank from 97 KB
        // to 78 KB (~20 % smaller). Six bugs caught and fixed across
        // the deep audit pass — see CHANGELOG. New {@code AppListCacheTest}
        // unit-test class adds 30 JVM tests covering parse rejection /
        // success / round-trip / label sanitisation paths. New
        // backward-compatible functionality (the cache layer is
        // additive — every failure mode falls through to the
        // pre-v1.4.0 PM-scan-and-decode path) — SemVer MINOR bump.
        //
        // Bumped 16 → 17: 1.3.6 is a single-fix manifest patch that
        // makes the launcher discoverable by third-party "set default
        // launcher" / "Launcher Manager" tools popular on XDA. The
        // launcher's HOME activity declared MAIN+HOME+DEFAULT and
        // MAIN+LEANBACK_LAUNCHER but NOT MAIN+LAUNCHER — and
        // queryIntentActivities(MAIN+LAUNCHER, MATCH_DEFAULT_ONLY) is
        // exactly how those tools enumerate installed launcher apps.
        // The fix adds CATEGORY_LAUNCHER to the existing HOME filter
        // (the canonical Android pattern, matching AOSP Launcher3),
        // and CATEGORY_DEFAULT to the LEANBACK filter so the same
        // MATCH_DEFAULT_ONLY queries work on TV. Manifest-only
        // change, zero Java touched. Patch release — SemVer PATCH bump.
        //
        // Bumped 15 → 16: 1.3.5 is a small performance + cleanup patch.
        // (1) onIconLoaded now short-circuits the keymap-card slot
        // repaint when the just-loaded package isn't bound to any
        // remappable key — eliminates ~50 wasted full-card repaints
        // during the cold-start icon flood when the user has the
        // settings panel / keymap card open behind a slow load.
        // (2) anchorCardUnderGear's two int[2] scratch arrays are
        // promoted to instance fields, removing the only remaining
        // per-overlay-open allocation. (3) ClockFormatter drops two
        // long-uncalled single-arg overloads (shouldRepaint(long) /
        // format(long)) — pure dead-code removal, no behaviour change.
        // (4) The instrumentation smoke test migrates from the
        // deprecated ActivityTestRule + Thread.sleep(500) pattern to
        // ActivityScenario + waitForIdleSync, which removes a real
        // CI flake source on slow KVM emulators and lets us drop the
        // androidx.test:rules dependency. Patch release, no
        // user-visible behaviour change — SemVer PATCH bump.
        //
        // Bumped 14 → 15: 1.3.4 trims the gear glyph from 8 teeth to 6
        // (per the "6 rounded teeth" design feedback — wider angular
        // gap reads as the canonical Material / iOS settings gear at
        // TV viewing distance) and bumps the per-tooth corner radius
        // from 30 % to 45 % of tooth width so the teeth read as
        // visibly rounded rather than rectangular. Audit pass
        // confirmed zero per-frame allocations across every onDraw
        // (toolbar pills, gear glyph, ring, cells, clock, wallpaper),
        // no orphaned string resources, no dead fields, no unused
        // imports — only the platform-API deprecation noise the
        // codebase has carried since 1.1.x. Patch release, no
        // behavioural change beyond the visual gear refresh —
        // SemVer PATCH bump.
        //
        // Bumped 13 → 14: 1.3.3 swaps the WiFi and gear pill positions
        // (WiFi now sits leftmost in the cluster so it lines up with
        // the home shelf's centre-of-mass and is one UP-press away
        // from any cell), replaces the v1.3.0 stroke-only line gear
        // with a solid filled gear glyph (per the "more monochrome
        // solid not line" design feedback), tightens the keymap card's
        // Menu / Subtitle row glyphs (they're now visually symmetric
        // with the colour discs and invert their colour when the row
        // is selected so the bright frosted-white selection pill no
        // longer hides them), and folds the duplicated drop-down
        // anchor logic in the settings panel and the keymap card into
        // one shared helper. Patch release, no behavioural change for
        // users who don't open the keymap card or look at the toolbar
        // pills closely — SemVer PATCH bump.
        //
        // Bumped 12 → 13: 1.3.2 ships two real-device bug fixes from
        // v1.3.1 (the "Button shortcuts flashes during back-from-Hide"
        // race in hideKeymapOverlay's reset-to-SLOTS-at-the-top logic;
        // the settings panel cursor resetting to row 0 instead of
        // restoring the row the user came from), plus two design-pass
        // refinements (panel chevrons removed for a label-only list,
        // and Menu / Subtitle keymap rows now carry a 3-line hamburger
        // and "CC" glyph respectively so every row has a real indicator
        // instead of a transparent dot placeholder). Patch release, no
        // behaviour change for users who don't open the keymap card —
        // SemVer PATCH bump.
        //
        // Bumped 11 → 12: 1.3.1 patches four issues from v1.3.0 found in
        // real-device testing — settings panel right-side dead space
        // (now WRAP_CONTENT auto-fit), Back from "Manage hidden apps"
        // landing in the keymap SLOTS list (now skips to the panel
        // directly via hideManagerSkipSlotsOnExit), the dim flickering
        // when transitioning settings → keymap (now a single shared
        // backdrop view), and the gear glyph being too rim-to-rim
        // inside its pill (proportions tuned smaller). Bug-fix only,
        // no behaviour change for healthy users — SemVer PATCH bump.
        //
        // v1.3.0: top-right toolbar consolidation + Show clock toggle.
        // The wallpaper pill is gone — its action moved into a unified
        // gear-pill settings panel that also hosts Manage hidden apps,
        // Button shortcuts, Set wallpaper, Show clock, and System
        // Settings. The previous mapper "sliders" glyph becomes a gear
        // since the same pill is now the entry point for every config
        // surface, not just keymapping. The home screen drops from
        // three top-right pills to two without losing any feature, and
        // the daily-frequent WiFi action stays a single click on the
        // rightmost edge. Long-press → System Settings moves from the
        // WiFi pill to the gear pill (most common destination from the
        // panel; WiFi long-press is now unbound, reserved for a future
        // power-user shortcut). The home-screen clock gains a "Show
        // clock" toggle bundled with a locale-aware short day-of-week
        // prefix (e.g. "Sat · 12:34 PM"). Toggle off hides the pill
        // entirely and stops the minute tick — zero CPU cost when the
        // user opts out. Default is on so existing installs see no
        // behaviour change at upgrade time. Bumped 10 → 11: feature
        // work, no breaking changes, SemVer MINOR.
        //
        // Bumped 9 → 10: 1.2.2 ships a single targeted bug fix — the
        // hide-app drawer (and keymap picker / slot rows) was rendering
        // empty icons for previously hidden apps because their bitmaps
        // never landed in iconCache. Pure bug fix, no new features, no
        // breaking changes — SemVer PATCH bump.
        //
        // Bumped 23 → 24: 1.4.6 is a targeted bug fix for icon cache
        // invalidation when apps are updated. The package broadcast
        // receiver now ensures icon cache entries are properly cleared
        // on ACTION_PACKAGE_REPLACED to prevent stale icons from
        // persisting after app updates. Pure bug fix, no new features —
        // SemVer PATCH bump.
        // 1.4.8: app-drawer UX pass — hidden-apps icon/label fix, drawer
        // hide-refocus fix, instant grid moves, divider + label + background
        // polish, drawer-close cross-fade, and a compact hidden-apps panel.
        // No new features, no API changes — SemVer PATCH bump.
        // 1.4.9: About card (version + Ko-fi QR + Downloader code + GitHub
        // release QR + credit), 3-state clock (full → time-only → off) with a
        // bigger plate-less clock + day/date line, minimal floating Wi-Fi/gear
        // icons, and a touch more spacing between app tiles.
        //
        // Bumped 27 → 28: 2.0.0 — the first public release of the redesigned
        // launcher. SemVer MAJOR bump: the home screen is now a TV-style
        // banner-tile favourites row with a pull-down app drawer (the legacy
        // single-row icon shelf is gone), which is a large enough UX change to
        // warrant a major version even though the upgrade is transparent
        // (favourites seed from the user's existing order and KEY_APP_ORDER
        // stays the single source of truth).
        //
        // Rolls up every unreleased increment since the last public tag
        // (v1.4.7): 1.4.8 (drawer UX pass), 1.4.9 (About card + 3-state clock
        // + floating Wi-Fi/gear icons), and the 1.5.x drawer/banner overhaul
        // (home favourites row + pull-down drawer + banner tiles + unified
        // Move flow), plus the About browser-link / Wi-Fi-cleanup / post-
        // update banner-refresh pass. See CHANGELOG.md [2.0.0] for the
        // user-facing summary.
        //
        // Bumped 28 → 29: 2.1.0 adds hardware TV inputs (HDMI / AV /
        // component) as first-class app-like tiles via the platform TV Input
        // Framework — they sort, place, move, hide, and bind exactly like
        // apps, launch by switching to the passthrough source, and need NO
        // special permission. A pure no-op on devices without TV inputs
        // (streaming sticks, most boxes). SemVer MINOR — additive feature.
        versionCode   = 29
        versionName   = "2.1.0"
        resourceConfigurations += listOf("en")

        // Instrumentation test runner. Required so :app:connectedDebugAndroidTest
        // (and any local emulator run) can find and execute the smoke test
        // under src/androidTest. JUnit 4 runner shipped with androidx.test.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI cap for the shipped APK.
        //
        // Today the launcher is pure Java with zero native dependencies, so
        // the produced APK has no `lib/` folder and a single binary already
        // runs on every Android ABI. This `abiFilters` block is therefore a
        // no-op against the current dependency set — but it is a defensive
        // cap: if a future transitive dependency ever brings in a native
        // library, R8 / AGP will package only the ABIs listed here.
        //
        // ABIs we cap to:
        //   armeabi-v7a — 32-bit ARM (legacy Android TV boxes, ancient
        //                 phones, the long tail of Amlogic / Allwinner
        //                 hardware that BareLauncher specifically wants
        //                 to support after the 1.2.0 minSdk-26 expansion).
        //   arm64-v8a   — 64-bit ARM (every modern phone, tablet, TV box,
        //                 streaming stick — the dominant Android ABI).
        //   x86_64      — 64-bit x86. Two reasons it MUST stay in the cap:
        //                 (1) Chromebooks running ARC++ and the small
        //                 segment of x86 Android tablets are real
        //                 production targets; cutting them off would be
        //                 a regression. (2) AGP's connectedAndroidTest
        //                 task uses this list to decide which connected
        //                 devices count as "compatible" — it intersects
        //                 the device's reported ABIs with this filter.
        //                 GitHub-Actions emulators run on x86_64 hosts,
        //                 so the AVD's reported ABIs are [x86_64, x86]
        //                 (and on API 30+ also arm64-v8a via Google's
        //                 binary translation). Without x86_64 in this
        //                 filter, API 26-29 emulators are flagged
        //                 incompatible and the smoke test fails with
        //                 "Found 1 connected device(s), 0 of which were
        //                 compatible." API 30+ accidentally worked
        //                 before because the arm64 binary-translation
        //                 layer ships in 30+ system images only — see
        //                 https://developer.android.com/studio/run/emulator-acceleration#binary-translation
        //                 for the version boundary.
        // ABIs we DON'T cap (would still bloat the APK if any native dep
        // ever arrived): x86 (32-bit, dead in 2026), riscv64 (no Android
        // TV hardware ships it yet), mips* (long discontinued).
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Strip all non-code bloat — no Kotlin, no META-INF noise
    packaging {
        resources {
            excludes += setOf(
                "kotlin/**",
                "META-INF/**",
                "DebugProbesKt.bin",
                "**.properties"
            )
        }
        // Launcher has zero native libs — disable legacy extraction
        jniLibs {
            useLegacyPackaging = false
        }
    }

    dependenciesInfo {
        includeInApk    = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = false
        resValues   = false
    }

    // Lint is now a real quality gate.
    //
    // History: lint was previously fully disabled (abortOnError = false,
    // checkReleaseBuilds = false). That meant the build silently ignored
    // every static-analysis warning Android offered. We now run lint on
    // every build and FAIL on errors. Specific issues that we cannot
    // fix in one quality pass without behavioural risk are surfaced as
    // warnings rather than errors via lintConfig (see lint.xml at the
    // module root). New issues introduced by future changes will fail
    // the build, which is what a quality gate should do.
    lint {
        abortOnError       = true
        checkReleaseBuilds = true
        warningsAsErrors   = false
        // Skip dependencies — there are none, but explicit beats implicit.
        checkDependencies  = false
        // Generate machine-readable reports the CI lint job uploads.
        textReport         = true
        htmlReport         = true
        xmlReport          = true
        lintConfig         = file("lint.xml")
        // SyntheticAccessor: this codebase intentionally uses inner classes
        // (RecyclingShelfView, CellView) that touch outer-class fields. The
        // synthetic accessors lint flags are a real cost on Dalvik but
        // negligible on ART (minSdk 26 ⇒ ART always — ART has been the
        // default Android runtime since API 21 / Lollipop). Disable rather
        // than restructure, which would be far more invasive than the
        // saving justifies.
        disable += setOf("SyntheticAccessor")
    }
}

// Block Kotlin from entering via any transitive dep — applies to the
// PRODUCTION classpaths only. Test classpaths are exempt so androidx.test
// can pull its dependencies (which transitively include Kotlin runtime
// stubs in newer versions). The Kotlin runtime stays out of the released
// APK because androidTestImplementation produces a separate test APK.
configurations.matching {
    val n = it.name
    !n.contains("Test", ignoreCase = true) &&
            !n.startsWith("androidTest") &&
            !n.startsWith("test")
}.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "androidx.core", module = "core-ktx")
}

dependencies {
    // ZERO external dependencies in the production APK — pure Android SDK only.

    // Local JVM unit tests (run on the host, no emulator). Tiny, only
    // the launcher's pure-Java helpers (e.g. AppOrder) are exercised here.
    testImplementation("junit:junit:4.13.2")

    // Instrumentation tests (require emulator/device). The smoke test
    // boots LauncherActivity and verifies basic layout. Compiles in every
    // build so a structural break to the activity fails CI even when no
    // emulator runs the test.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // androidx.test:core hosts ActivityScenario / ActivityScenarioRule —
    // the modern, deterministic replacement for the deprecated
    // ActivityTestRule API. Pulled in transitively by ext:junit but
    // declared explicitly here because the smoke test imports
    // ActivityScenario directly; surfacing the dep makes the test's
    // contract self-evident in this file.
    androidTestImplementation("androidx.test:core:1.7.0")
}
