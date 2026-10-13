# kotlinx.serialization keeps its own generated serializers via consumer rules.
# Strip all android.util.Log calls from release builds so nothing can leak to logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
