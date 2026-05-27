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
        // 1.1.2: minimal toolbar pass + executor-flag hardening. Shrinks
        // the wifi/mapper/wallpaper pill cluster (52→40 dp box, tighter
        // margins, gentler focus pop), softens the mapper "sliders" glyph
        // for the smaller plate, and hardens loadApps / loadIconAsync /
        // addApps against ROM-level edge cases that could strand inflight
        // flags or NPE on null ResolveInfo labels. Color philosophy is
        // unchanged — every plate paint and rim paint is identical.
        versionCode   = 4
        versionName   = "1.1.2"
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
