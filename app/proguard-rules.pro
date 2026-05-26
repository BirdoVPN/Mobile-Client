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

# Sentry — keep for proper stack traces
-keep class io.sentry.** { *; }
-dontwarn io.sentry.**

# ── Xray (libXray gomobile binding) ──────────────────────────────
# libXray uses gomobile reflection bindings — keep all public methods
-keep class libXray.Libxray { *; }
-keep class libXray.** { *; }
-dontwarn libXray.**

# ── Rosenpass (post-quantum key exchange) ─────────────────────────
# Keep native JNI class if using Rosenpass native library
-keep class app.birdo.vpn.service.RosenpassNative { *; }
-dontwarn app.birdo.vpn.service.RosenpassNative
