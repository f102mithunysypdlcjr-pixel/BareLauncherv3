plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "com.bare.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bare.launcher"
        minSdk        = 21
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0.0"
        resourceConfigurations += listOf("en")
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
    }

    dependenciesInfo {
        includeInApk    = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = false
        resValues   = false
    }

    lint {
    abortOnError = false
    checkReleaseBuilds = false
}
}

// Block Kotlin from entering via any transitive dep
configurations.all {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "androidx.core", module = "core-ktx")
}

dependencies {
    // ZERO external dependencies — pure Android SDK only
}
