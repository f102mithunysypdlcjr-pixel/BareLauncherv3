# BareLauncher ProGuard / R8 rules.
#
# This file is ADDITIONAL to proguard-android-optimize.txt (which the build
# wires in via getDefaultProguardFile). The defaults already cover the
# common Android keeps; we only need to declare what's specific to this
# launcher.
#
# Verbosity: -verbose surfaces useful info during R8 (mapping, kept
# classes, warnings) and is cheap. We do NOT silence everything via
# -dontwarn ** anymore — that hid real warnings. Targeted -dontwarn
# entries below cover the small set of platform classes the SDK
# references but does not always ship.

-verbose

# ── Keep the launcher entry point and its inner classes ─────────────
# Required because these are referenced by name (intent-filter, manifest).
# `keep` (not keepclassmembers) is correct here — we need both class and
# member preservation so reflection-style lookups (PackageManager queries,
# theme resolution) work after R8 obfuscation.
-keep public class com.bare.launcher.LauncherActivity { *; }
-keep class com.bare.launcher.LauncherActivity$* { *; }

# Annotations on shipped classes are useful for debugging crash reports
# (e.g. @Override is harmless to keep, @SuppressWarnings is irrelevant).
-keepattributes *Annotation*

# Strip verbose / debug / info logging in release builds. Warning and
# error logs ARE preserved — those signals matter in production.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── Targeted -dontwarn: missing-class noise that the platform handles ─
# Each entry has a one-line justification so future maintainers know
# whether it can be removed. Anything NOT listed here will surface as a
# real warning during R8 — which is the whole point of removing the
# blanket -dontwarn ** rule that was here before.

# androidx.test.* is referenced by the androidTest source set only and
# is not on the production runtime classpath; R8 sees the references via
# androidTest scope and emits "missing class" notes. Safe to ignore.
-dontwarn androidx.test.**

# JUnit lives only in the test classpaths.
-dontwarn org.junit.**
-dontwarn junit.**

# JetBrains internal annotations (NotNull / Nullable) sometimes leak
# transitively even though we exclude Kotlin entirely.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlin.**
