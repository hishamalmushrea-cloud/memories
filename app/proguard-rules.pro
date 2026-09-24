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
#
# MmLog is a Kotlin `object`, so d() and v() compile to *instance* methods on
# the singleton rather than to statics. A `public static void d(...)` signature
# therefore matches nothing and strips nothing; the rules below are written
# without `static` on purpose. SecurityRegressionTest asserts they stay that way.
-assumenosideeffects class com.memorymap.util.MmLog {
    public *** d(...);
    public *** v(...);
}
