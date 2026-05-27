# R8 already knows how to keep Compose, AndroidX lifecycle, and our code.
# We don't use reflection in app code, so no extra rules are needed.

# Strip log calls in release builds — tightens the APK and avoids leaking
# internal protocol bytes via `adb logcat`.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
