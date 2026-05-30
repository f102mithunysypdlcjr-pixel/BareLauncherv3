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
        // Bumped 9 → 10: 1.2.2 ships a single targeted bug fix — the
        // hide-app drawer (and keymap picker / slot rows) was rendering
        // empty icons for previously hidden apps because their bitmaps
        // never landed in iconCache. Pure bug fix, no new features, no
        // breaking changes — SemVer PATCH bump.
        versionCode   = 10
        versionName   = "1.2.2"
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
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
