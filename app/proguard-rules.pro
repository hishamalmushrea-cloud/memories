# Keep Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# kotlinx.serialization keeps its own metadata; the plugin adds the rules it needs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# Supabase / Ktor run on OkHttp; silence optional-dependency notes.
-dontwarn org.slf4j.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Never log user content or coordinates in release builds.
-assumenosideeffects class com.memorymap.util.MmLog {
    public static void d(...);
    public static void v(...);
}
