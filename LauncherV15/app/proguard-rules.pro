-keep public class com.bare.launcher.LauncherActivity { *; }
-keep class com.bare.launcher.LauncherActivity$* { *; }
-keepattributes *Annotation*
-dontwarn **
-dontnote **
# Strip debug logs
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
