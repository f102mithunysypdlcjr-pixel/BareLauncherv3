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
        minSdk        = 30
        // targetSdk = 36 to opt into Android 16 platform behaviours (and to
        // satisfy Play Store's evergreen target-API requirements). The
        // launcher does not use any APIs that changed semantics between
        // 35 and 36; the bump is a target-only change.
        targetSdk     = 36
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
        versionCode   = 7
        versionName   = "1.1.5"
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
        // library, R8 / AGP will package only `armeabi-v7a` (32-bit ARM,
        // legacy Android TV boxes) and `arm64-v8a` (every modern phone /
        // tablet / TV). x86 and x86_64 binaries — which we do not test
        // against — can never bloat the APK without an explicit edit here.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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
        // negligible on ART (minSdk 30 ⇒ ART always). Disable rather than
        // restructure, which would be far more invasive than the saving
        // justifies.
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
