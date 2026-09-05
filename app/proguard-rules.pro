# Birdo VPN Android — production R8 configuration
#
# == How to read (and change) this file ===============================
#
# R8 FULL MODE IS ALREADY ON. It is not something to switch on here.
# AGP 8.11.2 defines android.enableR8.fullMode as BooleanOption.FULL_R8 with a
# default of `true` and FeatureStage.Supported — read out of the AGP jar itself
# (javap on com/android/build/gradle/options/BooleanOption.class: the FULL_R8
# constructor pushes `iconst_1` for defaultValue), not out of documentation —
# and nothing in this repo sets it to false. Every rule below is written FOR
# full mode, where R8 additionally strips annotations from classes it does not
# keep and assumes an interface has no subtypes it cannot see.
#
# AGP's own proguard-android-optimize.txt is applied FIRST (see the release
# buildType in app/build.gradle.kts) and already supplies these. Never restate
# them here:
#   -keepattributes AnnotationDefault, EnclosingMethod, InnerClasses,
#                   RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations,
#                   RuntimeVisibleTypeAnnotations, Signature
#   -keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
#   -keepclassmembers enum * { public static **[] values(); public static ** valueOf(String); }
#   -keepclassmembers class * implements android.os.Parcelable { public static final ** CREATOR; }
#
# LIBRARY CONSUMER RULES ARE APPLIED AUTOMATICALLY and are almost always better
# than a hand-written copy: they are written by the library author, updated with
# the library, and scoped by -if/@annotation instead of by package wildcard.
# Retrofit, OkHttp, kotlinx.serialization and Sentry all ship them. Before
# adding a keep for a third-party library, read
# app/build/outputs/mapping/release/configuration.txt — R8 writes the fully
# merged configuration there, naming the source file of every rule.
#
# EVERY -keep that remains says WHY it cannot be narrowed. A keep without a
# reason is a defect: a subtree R8 may never touch, forever, that nobody after
# you will dare delete.
#
# The keeps that carry the VPN connect path and the JSON path are ASSERTED
# against the real minified DEX by scripts/check_r8_keeps.py, which CI runs on
# every pull request and again on both shipped artifacts. That script exists
# because breakage here is otherwise INVISIBLE: see the android.util.Log note.

# -- Attributes -------------------------------------------------------
# *Annotation* is BROADER than AGP's default (which keeps only the Runtime*
# ones) because it also keeps RuntimeInvisibleAnnotations. It is retained
# deliberately and conservatively: this is the one class of change that
# compiles, passes CI — unit tests run against UNMINIFIED classes — and then
# throws SerializationException on a user's device. An attribute keep seeds no
# classes, so it costs essentially nothing to leave in place; do not "clean it
# up" without a device test against the live API.
-keepattributes *Annotation*

# Sentry and Play Console both symbolicate from mapping.txt, which is useless
# without the line table. -renamesourcefileattribute collapses the file name so
# the attribute costs one string for the whole app rather than one per class.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Runtime integrity checks and release verification depend on these constants.
-keep class app.birdo.vpn.BuildConfig { *; }

# ── Strip all Log.* calls from release builds ─────────────────────
# This removes ALL logging in production — no sensitive data in logcat.
#
# SCOPE IS LOAD-BEARING: this rule names android.util.Log and NOTHING else, and
# it must stay that way. Log.e/Log.wtf being stripped is precisely why a failure
# on the VPN data path leaves no local trace (issue #357) — Sentry is the only
# remaining signal. Adding io.sentry.** (or Sentry.captureException) to an
# -assumenosideeffects rule would delete that signal too and, as with the Log
# calls, would do it silently: R8 does not report what it removed, the build
# stays green, and the app reports nothing forever.
#
# Verified 2026-09-05: io.sentry appears in this file only under -dontwarn
# (below), which keeps nothing and removes nothing.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# -- kotlinx.serialization --------------------------------------------
#
# kotlinx-serialization-core-jvm ships full-mode-aware consumer rules and AGP
# applies them (META-INF/com.android.tools/r8/*.pro; visible in
# configuration.txt). Scoped to classes that actually carry @Serializable, they
# cover:
#   * the Companion FIELD and the serializer() METHOD on it — which is the
#     reflective path Retrofit's kotlinx converter takes for every response
#     body (getDeclaredField("Companion") -> getDeclaredMethod("serializer"));
#   * re-attaching @Serializable itself, which full mode would otherwise strip
#     from a non-kept class, turning a sealed-class serializer into a
#     PolymorphicSerializer;
#   * INSTANCE and serializer() on serializable objects;
#   * the ProGuard-optimiser workaround on $$serializer.descriptor;
#   * -dontnote kotlinx.serialization.**.
#
# What used to be here instead was scoped by PACKAGE, not by annotation:
#   -keepclassmembers class app.birdo.vpn.**   { *** Companion; }
#   -keepclasseswithmembers class app.birdo.vpn.** { KSerializer serializer(...); }
# It froze the Companion of every class in the app whether serializable or not
# — 156 seeds measured in seeds.txt from a real local :app:assembleRelease off
# main — plus a duplicate pair aimed at kotlinx.serialization.json.** (23
# seeds) that the library already covers. Deleted.
#
# This app has NO polymorphic serialization: there is no SerializersModule, no
# @Polymorphic and no JsonContentPolymorphic anywhere in app/ or shared/. That
# is what makes the library's own rules sufficient. If polymorphic
# serialization is ever introduced this paragraph stops being true and these
# rules need revisiting.
#
# The one rule kept is the generated $$serializer classes. The library does NOT
# keep these — it relies on them being statically reachable from the kept
# Companion.serializer(). That reasoning is sound and this rule is, strictly,
# belt-and-braces. It stays because the failure it guards is the one this repo
# has actually been bitten by: a serialization break compiles, passes the unit
# tests (which run on unminified classes) and throws only at runtime, in
# release, on a user's device. Measured cost: 468 seeds. If you want that back,
# delete this rule ON ITS OWN, in its own commit, and device-test a real
# minified build against the live API before merging.
-keep,includedescriptorclasses class app.birdo.vpn.**$$serializer { *; }

# -- WireGuard --------------------------------------------------------
#
# !! DO NOT TOUCH THE NEXT RULE. It is the single line between R8 and a VPN
# that cannot connect for anybody.
#
# WgNative.kt resolves the entire data path by string:
#     Class.forName("com.wireguard.android.backend.GoBackend")
#     cls.getDeclaredMethod("wgTurnOn", String, int, String)
# and libwg-go.so binds those methods by static JNI name mangling — the
# exported symbols are literally
# Java_com_wireguard_android_backend_GoBackend_wgTurnOn and friends — so the
# package, the class name AND the method names must all survive verbatim.
# Nothing in the app references GoBackend statically; the six natives are
# `private static native` (verified with javap on the AAR's classes.jar); and
# com.wireguard.android:tunnel:1.0.20260102 ships NO consumer rules at all (the
# AAR contains no proguard.txt — verified). Without this rule R8 removes the
# members, and plausibly the class.
#
# getDeclaredMethod is invisible to R8 in EVERY mode. Do not "tighten" this to
# -keepnames, -keepclasseswithmembernames or -keep,allowshrinking: all three
# permit member removal and would compile, pass CI, ship, and brick the VPN.
# scripts/check_r8_keeps.py asserts the class and its methods survive unrenamed
# in the shipped DEX.
-keep class com.wireguard.android.backend.GoBackend { *; }

# com.wireguard.config.** and com.wireguard.crypto.** were blanket-kept here:
# 245 + 80 = 325 seeds, measured in seeds.txt from a real local release build.
# They are not reached reflectively. javap -c over every class in those two
# packages inside the tunnel AAR finds exactly ONE reflective call site,
# InetEndpoint's static initialiser resolving
# java.net.InetAddress.parseNumericAddress — a PLATFORM method, which R8 never
# renames. There are no native methods in either package (0, by javap), the app
# calls Config.Builder / Interface / Peer / Key / KeyPair statically from
# BirdoVpnService, WireGuardConfigBuilder and BirdoRepository, and none of those
# types is @Serializable. Static reachability keeps exactly what is used, so the
# blanket keeps were deleted. AGP's default enum rule still covers
# Key.Format / BadConfigException.Reason valueOf().

# -- Retrofit / OkHttp ------------------------------------------------
#
# Retrofit's hand-written block was deleted. retrofit-2.12.0.jar ships
# META-INF/proguard/retrofit2.pro, AGP applies it (visible in
# configuration.txt), and it is strictly better than what was here — read out
# of the jar, not assumed. It supplies -keepattributes Signature, InnerClasses,
# EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
# and AnnotationDefault; a -keepclassmembers,allowshrinking,allowobfuscation
# member rule for @retrofit2.http.* methods; the full-mode-specific
# `-if interface * { @retrofit2.http.* <methods>; } -keep,allowobfuscation
# interface <1>` pair that stops R8 replacing Proxy-created interface values
# with null; a keep for kotlin.coroutines.Continuation and for the generic
# return types full mode would otherwise strip; and retrofit2.Response.
#
# The local copy was a HARD -keepclasseswithmembers (no allowshrinking, no
# allowobfuscation), so it froze the API interfaces outright, plus an
# `interface * extends retrofit2.Call` rule that matches nothing in this app —
# there is no Call subtype anywhere in app/ or shared/.
#
# -keepattributes Signature is in AGP's default set; Exceptions was never
# needed (Kotlin has no checked exceptions and Retrofit reflects on Signature,
# not Exceptions). Both were restatements.
#
# `-keep class okhttp3.internal.platform.** { *; }` was deleted: 292 seeds
# hard-keeping OkHttp's entire platform layer (Conscrypt, BouncyCastle,
# OpenJSSE, Jdk8/Jdk9 adapters — nearly all dead on Android). Platform
# selection is Platform.findPlatform() calling Android10Platform /
# AndroidPlatform.buildIfSupported() STATICALLY; the reflection inside those
# targets android.* platform classes, not okhttp's own. OkHttp's OWN consumer
# rules keep none of it: the applied okhttp-release/proguard.txt contains
# -dontwarn okhttp3.internal.platform.**, -dontwarn org.conscrypt.**,
# -dontwarn org.bouncycastle.** and nothing else about that package. Measured
# afterwards in mapping.txt: 24 platform classes survive on static reachability
# alone — Platform, PlatformRegistry, AndroidPlatform, Android10Platform,
# Android10SocketAdapter, AndroidCertificateChainCleaner and the rest of the
# real Android path — while 44 members are removed. The selection path is intact
# and only the dead providers went.
#
# !! Note for whoever reads this next: the declared okhttp is 4.12.0 but
# releaseRuntimeClasspath resolves okhttp 5.2.1, pulled up by
# io.ktor:ktor-client-okhttp and okhttp-android (verified in
# app/gradle.lockfile: 4.12.0 appears only on debugAndroidTestCompileClasspath).
# The consumer rules that actually get applied are okhttp 5's, and they differ
# from 4.x's — 4.x carried a -keepnames for
# okhttp3.internal.publicsuffix.PublicSuffixDatabase and 5.x does not. Read the
# applied rules out of configuration.txt, not out of the 4.12.0 jar.
#
# The -dontwarn lines stay. They keep NOTHING; they exist because AGP enforces
# android.r8.failOnMissingClasses (BooleanOption R8_FAIL_ON_MISSING_CLASSES,
# default true, Enforced since AGP 8.0), so an unreferenced optional provider
# would otherwise fail the build outright.
-dontwarn okhttp3.**
-dontwarn okio.**

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

# Stack-trace readability under R8.
#
# `-keepattributes SourceFile,LineNumberTable` + `-renamesourcefileattribute`
# at the top of this file keep LINE NUMBERS in release stack traces, so a
# Sentry event always points at a real line. Class and method names are still
# obfuscated, which is intended — restoring them is a job for the mapping file,
# not for keep rules, because every keep rule is code R8 may not optimise.
#
# app/build/outputs/mapping/release/mapping.txt is uploaded as a CI artifact by
# the `release` job ("Upload Mapping File"). Uploading it to Sentry so traces
# de-obfuscate in the UI needs an ORG-scoped auth token plus org/project slugs —
# credentials the SENTRY_DSN secret does not provide and a DSN cannot grant.
# That step is therefore deliberately NOT wired here; docs/SENTRY-SETUP.md
# carries the optional recipe and the exact secrets it would need.

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
