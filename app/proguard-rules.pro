# Birdo VPN Android — Production ProGuard Rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Runtime integrity checks and release verification depend on these constants.
-keep class app.birdo.vpn.BuildConfig { *; }

# ── Strip all Log.* calls from release builds ─────────────────────
# This removes ALL logging in production — no sensitive data in logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Kotlin Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.birdo.vpn.**$$serializer { *; }
-keepclassmembers class app.birdo.vpn.** {
    *** Companion;
}
-keepclasseswithmembers class app.birdo.vpn.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WireGuard — keep GoBackend for native reflection (wgTurnOn/Off/GetSocket)
-keep class com.wireguard.android.backend.GoBackend { *; }
-keep class com.wireguard.config.** { *; }
-keep class com.wireguard.crypto.** { *; }

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-keep,allowobfuscation interface * extends retrofit2.Call

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.platform.** { *; }

# Google Tink / Security-Crypto (used by EncryptedSharedPreferences)
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi

# Sentry
#
# The blanket `-keep class io.sentry.** { *; }` that used to live here produced
# 14,384 of the 17,829 total R8 seeds — 80.7% of everything R8 was forbidden to
# touch — and left 1,239 io/sentry class descriptors unobfuscated in the DEX,
# against 107 for all of app.birdo.
#
# It was also unnecessary. The Sentry AAR ships complete, full-mode-aware
# consumer rules of its own: an NDK Class.forName keep, a DebugImage JNI keep, a
# native-methods keep, an enum keep, -keeppackagenames, and keepnames for every
# Integration. Those are exactly what stack traces need; the blanket rule added
# nothing except forbidding R8 from optimising the rest of the library.
#
# -dontwarn is retained: it suppresses build noise and keeps nothing.
-dontwarn io.sentry.**

# ── Xray ──────────────────────────────────────────────────────────
# The libXray gomobile keeps that used to live here were DEAD CONFIGURATION:
# the AAR is not in the build. There is no app/libs directory and no committed
# jniLibs; build.gradle.kts documents the AAR as absent, and R8 reported ZERO
# seeds for libXray. XrayManager's Class.forName therefore always throws and the
# code always takes the packaged-binary fallback path.
#
# Rules for a dependency that is not present cost nothing at runtime but are
# actively misleading: they imply a binding exists. If the AAR is ever added
# back, restore the keeps WITH it rather than leaving them here in advance.
-dontwarn libXray.**

# ── Rosenpass (post-quantum key exchange) ─────────────────────────
# Keep native JNI class if using Rosenpass native library
-keep class app.birdo.vpn.service.RosenpassNative { *; }
-dontwarn app.birdo.vpn.service.RosenpassNative
