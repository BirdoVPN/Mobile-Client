import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Load signing properties from local.properties (not committed to VCS)
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

// Load version from version.properties for centralized version management
val versionProps = Properties()
val versionPropsFile = rootProject.file("version.properties")
if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { versionProps.load(it) }
}
val vMajor = versionProps.getProperty("VERSION_MAJOR", "1").toInt()
val vMinor = versionProps.getProperty("VERSION_MINOR", "0").toInt()
val vPatch = versionProps.getProperty("VERSION_PATCH", "0").toInt()
val computedVersionCode = vMajor * 10000 + vMinor * 100 + vPatch
val computedVersionName = "$vMajor.$vMinor.$vPatch"
val signingCertFingerprint = (project.findProperty("birdoSigningCertFingerprint") as String?)
    ?: localProperties.getProperty("BIRDO_SIGNING_CERT_FINGERPRINT")
    ?: System.getenv("BIRDO_SIGNING_CERT_FINGERPRINT")
    ?: ""

// Google Play distribution flag. When true (CI builds the AAB with
// -PplayBuild=true) the app sells subscriptions through GOOGLE PLAY BILLING and
// steers nowhere else, per Google Play's Payments policy. When false (the
// direct/sideload APK distributed via GitHub, and the F-Droid build) Play
// Billing cannot work at all — the Play Store will not sell to an app it did
// not install — so the rail is disabled and the existing web-billing links are
// kept, which is allowed because those channels are not bound by Play policy.
//
// This flag used to mean "hide all purchase steering and show premium tiers as
// a read-only feature comparison". That was the pre-IAP compromise; the Play
// build now has a real in-app purchase path, so the comparison-only mode is
// gone. External LINK-OUT steering is a separate thing and still requires the
// playExternalOffers flag below.
val isPlayBuild = ((project.findProperty("playBuild") as String?) ?: System.getenv("BIRDO_PLAY_BUILD"))
    ?.toBoolean() ?: false

// Store-screenshot capture flag. When a DEBUG build is assembled with
// -PallowScreenshots=true, MainActivity skips FLAG_SECURE so the Play Store
// listing screenshots can be captured on an emulator/device. It is double-gated
// by BuildConfig.DEBUG in MainActivity, so a RELEASE build can never ship with
// screenshots enabled regardless of this value. Default false.
val allowScreenshots = ((project.findProperty("allowScreenshots") as String?)
    ?: System.getenv("BIRDO_ALLOW_SCREENSHOTS"))?.toBoolean() ?: false

// Frame-timing HUD flag. The JankStats-backed globe perf overlay
// (app.birdo.vpn.perf) is always on in DEBUG builds; this flag additionally
// switches it on in a REAL, minified release build, which is the only way to
// get frame numbers that mean anything — a debug build is unminified and
// `debuggable=true`, so its p99 is not the p99 users see.
//
// Deliberately NOT double-gated on BuildConfig.DEBUG the way allowScreenshots
// is: measuring release performance is the point. It is safe to expose because
// the instrumentation has no network sink and no persistence of any kind — it
// is an in-memory histogram, pinned by PrivacyBoundaryTest (P6-CLI-PERF-01).
// Default false; CI release builds never pass it.
val perfOverlay = ((project.findProperty("perfOverlay") as String?)
    ?: System.getenv("BIRDO_PERF_OVERLAY"))?.toBoolean() ?: false

android {
    namespace = "app.birdo.vpn"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.birdo.vpn"
        minSdk = 29 // Android 10+
        targetSdk = 36
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABI allow-list: ship every live Android ABI, and ship each one ONLY
        // with the complete VPN engine set behind it — libxray.so, libwg-go.so
        // and librosenpass_jni.so. An APK that installs and then cannot start a
        // tunnel is worse for the user than one that does not install, so the
        // two directions are enforced together by
        // scripts/verify_android_release_apk.py (cross product of these ABIs
        // against the three engines, plus a check that no OTHER lib/<abi>/
        // appears in the APK).
        //
        // All four are covered as of this change:
        //
        //   ABI          libwg-go.so   librosenpass_jni.so   libxray.so
        //   arm64-v8a    AAR           cargo-ndk             pinned release zip
        //   armeabi-v7a  AAR           cargo-ndk             built from source
        //   x86_64       AAR           cargo-ndk             pinned release zip
        //   x86          AAR           cargo-ndk             built from source
        //
        // The 32-bit engines are built from source because XTLS publishes no
        // 32-bit Android binary — the pinned release carries exactly two Android
        // assets, arm64-v8a and amd64. The linux-arm32/linux-32 assets are NOT
        // substitutes (glibc, not bionic). See scripts/provision_xray_engines.sh
        // for how they are pinned and built.
        //
        // KEEP IN SYNC: scripts/provision_xray_engines.sh (ALL_ABIS),
        // native/build.sh + native/build.ps1 (cargo-ndk targets), and
        // SHIPPED_ABIS in scripts/verify_android_release_apk.py.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }

        buildConfigField("String", "API_BASE_URL", "\"https://api.birdo.app\"")
        // Web app origin hosting the /native/oauth/* SSO broker routes. Distinct
        // from API_BASE_URL: the broker lives on the Next.js app host (birdo.app),
        // the exchange goes to the API host (api.birdo.app).
        buildConfigField("String", "WEB_BASE_URL", "\"https://birdo.app\"")
        buildConfigField("String", "APP_VERSION", "\"$computedVersionName\"")
        // Sentry DSN — loaded from local.properties (dev) or CI environment variable
        val sentryDsn = localProperties.getProperty("SENTRY_DSN")
            ?: System.getenv("SENTRY_DSN")
            ?: ""
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")

        // Native library integrity: populated by computeNativeHashes task for release builds
        buildConfigField("String", "NATIVE_HASH_WG_GO", "\"\"")
        buildConfigField("String", "NATIVE_HASH_XRAY", "\"\"")
        buildConfigField("String", "NATIVE_HASH_ROSENPASS_JNI", "\"\"")

        // PFA-Pass10: APK signing-cert SHA-256 fingerprint allow-list
        // (colon-separated upper-hex values separated by comma/semicolon/space).
        // Release builds now require this so runtime tamper checks are active.
        buildConfigField("String", "SIGNING_CERT_FINGERPRINT", "\"$signingCertFingerprint\"")

        // Store-screenshot capture flag (see allowScreenshots above). Consumed by
        // MainActivity, double-gated by BuildConfig.DEBUG so release ignores it.
        buildConfigField("boolean", "ALLOW_SCREENSHOTS", "$allowScreenshots")

        // Play-distribution flag (see isPlayBuild above). Baked into BuildConfig
        // so UI can hide external-purchase steering in the Play (AAB) build.
        buildConfigField("boolean", "IS_PLAY_BUILD", "$isPlayBuild")

        // Frame-timing HUD flag (see perfOverlay above).
        buildConfigField("boolean", "PERF_OVERLAY", "$perfOverlay")

        // ── Play external-offers (link-out billing) ────────────────────────
        // From 30 Jun 2026 Google permits linking out to your own checkout in
        // the UK/US/EEA — but ONLY for developers enrolled in the billing-choice
        // / external-offers programme. Unenrolled steering is a policy violation
        // and gets the app pulled; the package name does not come back.
        //
        // This is therefore a SEPARATE flag from IS_PLAY_BUILD and defaults to
        // FALSE. It must be turned on deliberately (-PplayExternalOffers=true),
        // and only AFTER enrolment is confirmed in the Play Console. Being a
        // Play build must never by itself be enough to make the app steer.
        // See birdo-web/docs/PLAY-LINK-OUT-BILLING.md.
        val playExternalOffers = (project.findProperty("playExternalOffers") as String?)
            ?.toBoolean() ?: false
        buildConfigField("boolean", "PLAY_EXTERNAL_OFFERS", "$playExternalOffers")
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
                ?: System.getenv("RELEASE_STORE_FILE")
            val storePwd = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                ?: System.getenv("RELEASE_STORE_PASSWORD")
            val keyAlias_ = localProperties.getProperty("RELEASE_KEY_ALIAS")
                ?: System.getenv("RELEASE_KEY_ALIAS")
            val keyPwd = localProperties.getProperty("RELEASE_KEY_PASSWORD")
                ?: System.getenv("RELEASE_KEY_PASSWORD")

            if (storeFilePath != null && storePwd != null && keyAlias_ != null && keyPwd != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = storePwd
                keyAlias = keyAlias_
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            // Bundle native debug symbols (wg-go, xray) into the AAB for Play Console crash/ANR symbolication.
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

// PFA-M3: pin every dependency resolution to a generated lockfile so a
// compromised upstream proxy or transitive version drift cannot silently
// substitute artifacts. Run `./gradlew :app:dependencies --write-locks`
// to (re)generate gradle.lockfile after deliberate dependency bumps.
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
    // kotlin-stdlib-common is brought in transitively at differing versions by
    // the Kotlin 2.2 compiler plugin classpaths vs the app; locking it causes a
    // STRICT-mode resolution conflict, so exclude it from the lock.
    ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
}

// Kotlin 2.2: jvmTarget via the compilerOptions DSL (kotlinOptions{} inside
// android{} is removed). Top-level kotlin{} extension applies to all compilations.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test>().configureEach {
    ignoreFailures = false
}

val validateReleaseSecurityConfig = tasks.register("validateReleaseSecurityConfig") {
    group = "verification"
    description = "Fails release builds when mandatory anti-tamper settings are missing."

    doLast {
        if (signingCertFingerprint.isBlank()) {
            throw GradleException(
                "BIRDO_SIGNING_CERT_FINGERPRINT is required for release builds. " +
                    "Use a comma-separated allow-list for Play and sideload certificates."
            )
        }
    }
}

// ── Rosenpass JNI native build ──────────────────────────────────────────────
//
// Cross-compiles `native/rosenpass-jni/` (Rust) for all four live Android
// ABIs (arm64-v8a, armeabi-v7a, x86_64, x86) via cargo-ndk, depositing
// librosenpass_jni.so into
// app/src/main/jniLibs/<abi>/ where AGP picks it up automatically.
//
// This task is NOT auto-wired into mergeReleaseNativeLibs — local debug builds
// without Rust installed should still succeed (RosenpassNative gracefully
// falls back when the .so is absent). CI invokes this task explicitly:
//
//     ./gradlew :app:buildRustLibs bundleRelease
//
// See native/README.md for one-time setup (rustup, cargo-ndk, NDK targets).
val buildRustLibs = tasks.register<Exec>("buildRustLibs") {
    group = "build"
    description = "Cross-compiles the Rosenpass JNI Rust crate for all Android ABIs."

    val nativeDir = rootProject.file("native")
    workingDir = nativeDir

    val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
    if (isWindows) {
        commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", "build.ps1", "-Profile", "release")
    } else {
        commandLine("bash", "build.sh", "release")
    }

    inputs.dir("$nativeDir/rosenpass-jni/src")
    inputs.file("$nativeDir/rosenpass-jni/Cargo.toml")
    outputs.files(
        "${project.projectDir}/src/main/jniLibs/arm64-v8a/librosenpass_jni.so",
        "${project.projectDir}/src/main/jniLibs/armeabi-v7a/librosenpass_jni.so",
        "${project.projectDir}/src/main/jniLibs/x86_64/librosenpass_jni.so",
        "${project.projectDir}/src/main/jniLibs/x86/librosenpass_jni.so",
    )

    // Surface a clear diagnostic when Rust toolchain isn't installed locally,
    // instead of dumping a raw "command not found" stack trace.
    doFirst {
        val cargoCheck = if (isWindows) {
            ProcessBuilder("where", "cargo").redirectErrorStream(true).start()
        } else {
            ProcessBuilder("which", "cargo").redirectErrorStream(true).start()
        }
        if (cargoCheck.waitFor() != 0) {
            throw GradleException(
                "cargo not on PATH — install Rust + cargo-ndk + Android targets " +
                "(see native/README.md). Or skip this task for local builds; " +
                "RosenpassNative will fall back to the server-provided PSK path."
            )
        }
    }
}

// Compute SHA-256 hashes of native .so libraries for runtime integrity verification.
// The NativeLibraryVerifier reads these BuildConfig fields to validate binaries at load time.
afterEvaluate {
    android.applicationVariants.all {
        if (buildType.name == "release") {
            val variantName = name.replaceFirstChar { it.uppercase() }
            val mergeTask = tasks.findByName("merge${variantName}NativeLibs")
            val generateBuildConfig = tasks.findByName("generate${variantName}BuildConfig")
            if (mergeTask != null && generateBuildConfig != null) {
                generateBuildConfig.doFirst {
                    val nativeDirs = mergeTask.outputs.files.files
                    fun hashSo(name: String): String {
                        val candidates = nativeDirs.flatMap { dir ->
                            fileTree(dir) { include("**/lib$name.so") }.files
                        }
                        // Hash EVERY shipped ABI variant, keyed by its ABI dir name,
                        // and encode as "abi=hash;abi=hash". Previously only the
                        // arm64-v8a hash was baked, so on the x86_64 build the
                        // runtime hash never matched and integrity silently fell back
                        // to signature-only verification. The verifier now looks up
                        // the hash for the device's actual ABI.
                        val knownAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                        return candidates.mapNotNull { f ->
                            val normalized = f.path.replace('\\', '/')
                            val abi = knownAbis.firstOrNull { normalized.contains("/$it/") }
                                ?: return@mapNotNull null
                            val hash = MessageDigest.getInstance("SHA-256")
                                .digest(f.readBytes())
                                .joinToString("") { b -> "%02x".format(b) }
                            "$abi=$hash"
                        }.distinct().joinToString(";")
                    }
                    val wgHash = hashSo("wg-go")
                    val xrayHash = hashSo("xray").ifBlank { hashSo("Xray") }
                    // AUDIT-E1: include librosenpass_jni.so in the integrity
                    // set. Without this, an attacker who swaps just the
                    // PQ JNI .so could silently downgrade BirdoPQ v1 by
                    // returning an attacker-known PSK from deriveSharedPsk().
                    val rosenpassJniHash = hashSo("rosenpass_jni")
                    // FAIL LOUDLY on a blank hash. A release that bakes "" here
                    // makes NativeLibraryVerifier take its no-registered-hash
                    // branch, silently reducing the engine-integrity control to
                    // the installer/signature check — with no build-time or
                    // runtime signal that it happened.
                    check(wgHash.isNotBlank()) {
                        "NATIVE_HASH_WG_GO is blank — libwg-go.so was not found in " +
                            "${mergeTask.name} outputs; refusing to ship a release " +
                            "with the native integrity check disarmed."
                    }
                    check(xrayHash.isNotBlank()) {
                        "NATIVE_HASH_XRAY is blank — libxray.so was not found in " +
                            "${mergeTask.name} outputs; refusing to ship a release " +
                            "with the native integrity check disarmed."
                    }
                    check(rosenpassJniHash.isNotBlank()) {
                        "NATIVE_HASH_ROSENPASS_JNI is blank — librosenpass_jni.so was not " +
                            "found in ${mergeTask.name} outputs; refusing to ship a release " +
                            "with the native integrity check disarmed."
                    }
                    android.defaultConfig.buildConfigField("String", "NATIVE_HASH_WG_GO", "\"$wgHash\"")
                    android.defaultConfig.buildConfigField("String", "NATIVE_HASH_XRAY", "\"$xrayHash\"")
                    android.defaultConfig.buildConfigField("String", "NATIVE_HASH_ROSENPASS_JNI", "\"$rosenpassJniHash\"")
                }
                // dependsOn, not mustRunAfter: mustRunAfter is ORDERING-only, so
                // in a graph where the merge task was not scheduled (or in a warm
                // build dir where generateBuildConfig would otherwise be skipped
                // as UP-TO-DATE with stale/blank hashes) the doFirst above hashed
                // nothing. Declaring the merged native libs as a real task input
                // also makes generateBuildConfig re-run whenever any shipped .so
                // changes — the hashes are now derived from a declared input, not
                // from whatever happened to be on disk.
                generateBuildConfig.dependsOn(mergeTask)
                generateBuildConfig.inputs.files(mergeTask.outputs.files)
                    .withPropertyName("nativeLibsForIntegrityHash")
            }
        }
    }

    tasks.findByName("preReleaseBuild")?.dependsOn(validateReleaseSecurityConfig, buildRustLibs)
}

dependencies {
    // ── Shared KMP Module ────────────────────────────────────────
    implementation(project(":shared"))

    // ── Google Play Billing (in-app subscriptions) ───────────────
    // Play Console enforces a MINIMUM Billing Library version on upload and
    // raises it roughly yearly; an old one is rejected at upload, not at
    // review, so it blocks the release outright. 9.1.0 is the current release
    // as of 2026-08 (dl.google.com maven-metadata, published 2026-06-18).
    //
    // Note the transitive play-services-location: it has been a dependency of
    // the billing library since 7.x and cannot be excluded without breaking it.
    // The billing AAR adds exactly one permission to the merged manifest,
    // com.android.vending.BILLING, and no location permission — verified by
    // reading app/build/intermediates/merged_manifest/debug/... after
    // :app:processDebugMainManifest. PlayBillingDependencyTest pins the part
    // that can regress from a source edit: that nothing ever CALLS the location
    // APIs, plus the version floor and the lockfile agreeing with this line.
    implementation("com.android.billingclient:billing:9.1.0")

    // ── Play Integrity (official-client attestation) ─────────────
    // Requests a device/app integrity verdict the backend verifies so only the
    // genuine, unmodified Play build can obtain a WireGuard peer. Only produces a
    // valid token on Play-distributed builds; gated by BuildConfig.IS_PLAY_BUILD.
    implementation("com.google.android.play:integrity:1.6.0")

    // ── Core Android ─────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // ── Frame timing ─────────────────────────────────────────────
    // JankStats: the supported wrapper over Window.OnFrameMetricsAvailableListener
    // (API 24+) that also carries per-frame STATE labels, which is what lets the
    // globe be measured separately from the rest of the screen. Local only —
    // JankStats reports to an in-process listener and has no reporting backend
    // of its own. Debug/-PperfOverlay use only; R8 strips it from stock releases.
    implementation("androidx.metrics:metrics-performance:1.0.0")

    // ── Compose ──────────────────────────────────────────────────
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.compose.material3:material3-window-size-class")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── Hilt DI ──────────────────────────────────────────────────
    // Must match the Hilt Gradle plugin version (2.57.2) or the aggregating task
    // fails with "rootComponentPackage has not been initialized".
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-compiler:2.57.2")
    implementation("androidx.hilt:hilt-navigation-compose:1.3.0")

    // ── Networking ───────────────────────────────────────────────
    implementation("com.squareup.retrofit2:retrofit:2.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")

    // ── WireGuard Tunnel ─────────────────────────────────────────
    implementation("com.wireguard.android:tunnel:1.0.20260102")

    // NOTE: Xray core (libXray) is loaded at runtime via reflection.
    // Place libXray.aar in app/libs/ or include the native .so files
    // in src/main/jniLibs/<abi>/ when available.
    // XrayManager falls back to bundled xray binary if library is absent.

    // ── Security ─────────────────────────────────────────────────
    implementation("androidx.security:security-crypto:1.1.0") // no stable 1.1.x available
    implementation("androidx.biometric:biometric:1.1.0")
    // Crash Reporting
    implementation("io.sentry:sentry-android:8.54.0")

    // ── Glance (Home Screen Widget) ──────────────────────────────
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // ── Testing ──────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("app.cash.turbine:turbine:1.2.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
