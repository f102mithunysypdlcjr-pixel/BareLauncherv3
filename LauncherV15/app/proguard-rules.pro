# BareLauncher ProGuard / R8 rules.
#
# AGP wires `proguard-android-optimize.txt` as the BASE rules (see
# build.gradle.kts → release.proguardFiles). Those defaults already
# cover everything our launcher needs to expose to the framework:
#
#   - `-keep public class * extends android.app.Activity` keeps
#     LauncherActivity by name (the manifest reference resolves
#     against this).
#   - `-keep public class * extends android.content.BroadcastReceiver`,
#     `extends android.app.Service`, etc. — none used by us, but
#     defensive defaults.
#   - `-keepclasseswithmembers class * { public <init>(android.content.Context,
#     android.util.AttributeSet); }` keeps every XML-inflatable View
#     constructor — irrelevant here since every view is constructed
#     programmatically.
#   - `-keepclassmembers class * extends android.app.Activity { public
#     void *(android.view.View); }` keeps `android:onClick=` handler
#     methods — none in our codebase, but harmless.
#   - `native <methods>`, `Parcelable.CREATOR`, `enum values()/valueOf()`
#     — none used by us; harmless.
#
# What this file adds:
#   - Verbose R8 output (mapping, kept classes, warnings).
#   - Annotation retention for stack-trace clarity.
#   - Stripped Log.v/d/i in release.
#   - Targeted `-dontwarn` for test-classpath references R8 sees but
#     cannot resolve in production.
#
# What this file used to declare and no longer does (v1.4.0):
#   `-keep public class com.bare.launcher.LauncherActivity { *; }`
#   `-keep class com.bare.launcher.LauncherActivity$* { *; }`
#
# Both were over-broad. The class-name keep is already covered by
# `extends android.app.Activity` in the AGP defaults, and the
# `{ *; }` member wildcard prevented R8 from renaming/inlining
# private fields and methods that nothing reflects against — pure
# missed APK-size win. The launcher uses zero reflection, no XML
# inflation, no Parcelable, no Serializable, no `android:onClick`,
# so R8's standard data-flow analysis correctly tracks every
# reachable member through the existing entry points.

-verbose

# Annotations on shipped classes are useful for stack traces / debugging.
# {@code @Override} is harmless to keep; {@code @SuppressWarnings} is
# irrelevant at runtime; the cost is a few bytes of metadata.
-keepattributes *Annotation*

# Strip verbose / debug / info logging in release builds. Warning and
# error logs ARE preserved — those signals matter in production.
# {@code CrashLogger} writes {@code Log.e} for uncaught exceptions; that
# path is intentionally not stripped.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# ── Targeted -dontwarn: missing-class noise that the platform handles ─
# Each entry has a one-line justification so future maintainers know
# whether it can be removed. Anything NOT listed here will surface as a
# real warning during R8 — which is the whole point of NOT having a
# blanket `-dontwarn **` rule.

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
