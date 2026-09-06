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

// ── Sentry DSN (crash reporting) ────────────────────────────────────────────
//
// Resolution order: -PsentryDsn=… > local.properties > SENTRY_DSN env var.
// CI supplies the env var from the SENTRY_DSN repo secret — see the `release`
// job in .github/workflows/android.yml and docs/SENTRY-SETUP.md.
//
// Resolved HERE, once, at the top level rather than inside defaultConfig,
// because the release-build gate below has to read the same value. Two
// distinct not-configured states have to collapse to one:
//
//   • absent            → BuildConfig.SENTRY_DSN = "" → the SDK stays disabled.
//   • the text "null"   → what `"$sentryDsn"` interpolated into BuildConfig
//                         before the `?: ""` fallback existed (issue #357).
//                         "null" is NOT blank, so Sentry would accept it as a
//                         DSN and throw on every cold start instead of quietly
//                         disabling. Treat it as absent.
//
// A blank DSN is fine for debug and for anyone building from a clean checkout;
// it is NOT fine for a release, which is what validateSentryDsn enforces.
val sentryDsn = ((project.findProperty("sentryDsn") as String?)
    ?: localProperties.getProperty("SENTRY_DSN")
    ?: System.getenv("SENTRY_DSN"))
    ?.trim()
    ?.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }
    ?: ""

// A usable DSN is exactly `https://<publicKey>@<host>/<projectId>`. Anything
// else — a placeholder, a project slug, an accidentally pasted auth token —
// either initialises into a permanent no-op or throws, so "non-empty" is not
// a strong enough test to ship on.
val sentryDsnIsUsable = Regex("""^https://[^@/\s]+@[^/\s]+/\d+$""").matches(sentryDsn)

// Escaped for embedding as a Java string literal in generated BuildConfig.java.
// A DSN contains none of these today; doing it anyway means a malformed value
// produces a build-gate failure (below) rather than a Java syntax error with a
// stack trace that quotes the secret.
val sentryDsnLiteral = sentryDsn.replace("\\", "\\\\").replace("\"", "\\\"")

// Deliberate, explicit escape hatch: build a RELEASE artifact with no Sentry
// account at all (reproducible/F-Droid-style source builds, a contributor
// checking that minification still works). The build shouts whenever it is used.
//
// CI DOES SET THIS, in exactly one place. .github/workflows/android.yml runs
// `:app:minifyReleaseWithR8 -PallowMissingSentryDsn=true` in the `build` job, so
// it is passed on EVERY pull request. That is deliberate and safe: the R8-on-PR
// step runs the shrinker only — it never reaches packageRelease, produces no
// APK or AAB, and a fork or dependabot PR has neither the Azure keystore nor the
// Sentry DSN secret. Nothing built there is distributable, so no artifact with a
// blank DSN can escape.
//
// The jobs that DO package and distribute — `release` and `release-aab` — never
// pass it, so a shipped artifact still cannot have an inert crash reporter; that
// is the invariant #357 established and it is unchanged. If a future workflow
// adds this flag to a job that uploads anything, that job is the bug.
val allowMissingSentryDsn = ((project.findProperty("allowMissingSentryDsn") as String?)
    ?: System.getenv("BIRDO_ALLOW_MISSING_SENTRY_DSN"))?.toBoolean() ?: false

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
    // compileSdk 37 + compileSdkMinor 0 -> platforms;android-37.0.
    //
    // The SDK repository publishes NO bare `platforms;android-37`; the only
    // API-37 platforms are android-37.0, 37.1 and 37.2. Selecting a
    // minor-versioned platform is an AGP 9 feature, so this line is a second,
    // independent reason the toolchain bump had to be atomic -- AGP 8 cannot
    // resolve any API-37 platform at all.
    //
    // Pinned to the .0 minor deliberately rather than tracking the newest: a
    // compileSdk minor bump is an API-surface change and belongs in its own PR
    // with its own lint run, not implicit in a toolchain migration.
    //
    // targetSdk stays 36 (see defaultConfig): compiling against 37 is required
    // by the androidx AARs, but RAISING targetSdk changes runtime behaviour for
    // users and needs a device test, which this change does not carry.
    compileSdk = 37
    compileSdkMinor = 0

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
        // Sentry DSN — resolved by the block above local.properties/env/-P.
        // Empty in debug and in any build that was not given one; a RELEASE
        // build with an empty or malformed value is refused outright by
        // :app:validateSentryDsn, which preReleaseBuild depends on.
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsnLiteral\"")

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
// substitute artifacts.
//
// REGENERATE WITH BOTH OF THESE, IN ORDER (verified during the AGP 9 bump):
//
//   rm app/gradle.lockfile
//   ./gradlew :app:resolveAndLockAll --write-locks
//   ./gradlew :app:assembleDebug :app:testDebugUnitTest --write-locks
//
// BOTH steps are required and neither is sufficient alone:
//
//   * resolveAndLockAll walks the configuration container, so it covers
//     configurations no single build touches -- but it cannot see ones AGP
//     creates during task EXECUTION. `:app:androidApis` is the one that bites:
//     it is created by parseDebugLocalResources, so a lockfile built only from
//     resolveAndLockAll fails the very next build with
//     "Configuration ':app:androidApis' is locked but does not have lock state".
//   * the build step covers androidApis and the real task classpaths, but only
//     the ones those tasks resolve.
//
// Delete the lockfile first. --write-locks UPDATES the configurations it
// resolves and LEAVES THE REST, so stale entries survive a "regeneration"
// silently. That is not cosmetic under LockMode.STRICT.
//
// NOT with `./gradlew :app:dependencies --write-locks`, which this comment used
// to recommend and which is NOT sufficient. Measured during the AGP 9 bump: the
// `dependencies` report does not resolve releaseUnitTestCompileClasspath or
// releaseUnitTestRuntimeClasspath, so --write-locks leaves their entries
// untouched. After bumping appcompat 1.7.1 -> 1.8.0 the lockfile still held
//
//   androidx.appcompat:appcompat:1.7.1=releaseUnitTestCompileClasspath,
//   androidx.appcompat:appcompat:1.7.1=releaseUnitTestRuntimeClasspath
//
// alongside the new 1.8.0 line -- pinning a version the build no longer
// declares. (Under AGP 9 those two configurations no longer exist at all:
// AGP 9 creates unit tests only for the testBuildType, so there is no
// testReleaseUnitTest task and no releaseUnitTest* classpath. The entries were
// pure stale garbage, which is exactly why the lockfile must be DELETED rather
// than updated in place.)
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
    // kotlin-stdlib-common is brought in transitively at differing versions by
    // the Kotlin 2.2 compiler plugin classpaths vs the app; locking it causes a
    // STRICT-mode resolution conflict, so exclude it from the lock.
    ignoredDependencies.add("org.jetbrains.kotlin:kotlin-stdlib-common")
}

// Resolve EVERY resolvable configuration so --write-locks refreshes all of them.
// See the note above dependencyLocking for why the `dependencies` report is not
// a substitute. Refuses to run without --write-locks so it cannot be mistaken
// for a verification task.
tasks.register("resolveAndLockAll") {
    notCompatibleWithConfigurationCache("Resolves every configuration to write lock state")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "resolveAndLockAll does nothing useful without --write-locks. " +
                "Run: ./gradlew :app:resolveAndLockAll --write-locks"
        }
    }
    doLast {
        // Resolve the dependency GRAPH, not the artifacts. Dependency locking
        // records the graph, and `Configuration.resolve()` additionally forces
        // artifact selection -- which fails on the androidTest classpaths
        // ("cannot choose between the following variants of project ':app'",
        // artifactType unmatched) even though their graph resolves fine.
        configurations
            .filter { it.isCanBeResolved }
            .forEach { it.incoming.resolutionResult.root }
    }
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

// ── Release gate: a shipped build must be able to report its own crashes ────
//
// Issue #357: `SENTRY_DSN` was read from local.properties/env and nothing ever
// supplied it — no repo secret, no `env:` on the workflow — so every artifact
// CI has ever produced compiled an empty (originally: the literal "null") DSN
// and every Sentry call in the app was inert. Nothing failed; the build was
// green and the crash reporter simply did not exist.
//
// That is the estate's recurring defect shape: a control that reports healthy
// while doing nothing. The only fix that stays fixed is refusing to produce
// the artifact, so a missing DSN is a build failure and not a warning.
//
// Debug builds are deliberately unaffected: BirdoApp.initSentry() returns
// early on BuildConfig.DEBUG, so local development never needs the secret.
val validateSentryDsn = tasks.register("validateSentryDsn") {
    group = "verification"
    description = "Fails release builds when no usable Sentry DSN was supplied."

    doLast {
        if (sentryDsnIsUsable) {
            logger.lifecycle("Sentry: release build has a DSN — crash reporting is ARMED.")
            return@doLast
        }
        if (allowMissingSentryDsn) {
            logger.warn(
                "\n" +
                    "*********************************************************************\n" +
                    "  WARNING: building a RELEASE artifact with NO Sentry DSN.\n" +
                    "  This build cannot report a single crash. It must never be the\n" +
                    "  artifact that reaches the Play Store or a GitHub release.\n" +
                    "  (-PallowMissingSentryDsn=true was passed.)\n" +
                    "*********************************************************************\n"
            )
            return@doLast
        }
        throw GradleException(
            buildString {
                appendLine("No usable SENTRY_DSN — refusing to build a release artifact that cannot report crashes.")
                appendLine()
                appendLine(
                    if (sentryDsn.isEmpty()) "  resolved value: <empty / absent / the literal \"null\">"
                    else "  resolved value: present but not a valid DSN " +
                        "(expected https://<publicKey>@<host>/<projectId>)"
                )
                appendLine()
                appendLine("Supply it in ONE of these ways:")
                appendLine("  • CI      — set the SENTRY_DSN repo secret; the release job already")
                appendLine("              passes it through as an env var.")
                appendLine("              gh secret set SENTRY_DSN --repo BirdoVPN/Mobile-Client")
                appendLine("  • local   — add SENTRY_DSN=… to local.properties (gitignored)")
                appendLine("  • one-off — ./gradlew assembleRelease -PsentryDsn=…")
                appendLine()
                appendLine("To build a release deliberately WITHOUT crash reporting (source/F-Droid")
                appendLine("style builds only, never for a shipped artifact):")
                appendLine("  ./gradlew assembleRelease -PallowMissingSentryDsn=true")
                appendLine()
                appendLine("Full setup: docs/SENTRY-SETUP.md")
            }
        )
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

    // Plain JDK property, not org.gradle.internal.os.OperatingSystem. That class
    // is a Gradle INTERNAL API; it survived into Gradle 9.7.1 but moved jars
    // (lib/gradle-stdlib-java-extensions-9.7.1.jar), and an internal API that
    // relocates under us is one toolchain bump away from vanishing. No behaviour
    // change, one fewer thing that can break on the next wrapper bump.
    val isWindows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
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

// buildRustLibs writes librosenpass_jni.so into app/src/main/jniLibs/<abi>/,
// which is ALSO a source directory AGP's merge*JniLibFolders tasks read. Gradle
// 9 promotes that undeclared producer/consumer pair from a warning to a build
// FAILURE:
//
//   Task ':app:mergeDebugJniLibFolders' uses this output of task
//   ':app:buildRustLibs' without declaring an explicit or implicit dependency.
//
// It only bites when both tasks are in ONE task graph. CI never hit it because
// it invokes `./gradlew :app:buildRustLibs` as a separate step -- but the
// command this file documents a few lines above, `./gradlew :app:buildRustLibs
// bundleRelease`, is exactly the failing shape.
//
// mustRunAfter, not dependsOn, on purpose: dependsOn would drag a cargo-ndk
// cross-compile into every debug build (and into CI jobs that deliberately do
// not install the Rust toolchain). mustRunAfter only ORDERS the two when both
// are already in the graph, which is precisely the ambiguity being fixed. It is
// Gradle's own suggestion #3 for this diagnostic.
tasks.matching { it.name.endsWith("JniLibFolders") }.configureEach {
    mustRunAfter(buildRustLibs)
}

// Compute SHA-256 hashes of the SHIPPED native .so libraries for runtime
// integrity verification. "Shipped" means the strip-stage output, not the
// merge-stage output -- see the comment on `stripTask` below for the measured
// byte difference between the two.
// NativeLibraryVerifier reads these BuildConfig fields to validate binaries at load time.
//
// WHY THIS NO LONGER WRITES THROUGH `android.defaultConfig`
// ---------------------------------------------------------
// The previous version computed the hashes inside `generateReleaseBuildConfig`'s
// `doFirst { }` and wrote them with `android.defaultConfig.buildConfigField(...)`.
// The values never reached the generated BuildConfig. `defaultConfig` is a DSL
// object AGP has already read and locked by the time any task executes, and the
// GenerateBuildConfig task resolves its field input when the task STARTS, which
// is before its own doFirst actions run. So all three constants shipped as the
// `""` declared in defaultConfig, in every release ever built, and
// NativeLibraryVerifier took its no-registered-hash branch on every device: the
// engine-integrity control silently degraded to the APK-signature check.
//
// Measured, not inferred. A DEX string-pool scan of the signed release APK from
// CI run 33994039401 (main @ fa94ae1) finds ZERO `<abi>=<sha256>` strings, and a
// local `:app:assembleRelease` off the same commit generates
//     public static final String NATIVE_HASH_WG_GO = "";
// Both builds succeeded.
//
// Nothing caught it because the `check(...)` guards below lived in that same
// doFirst: they correctly asserted the hashes WERE COMPUTED -- which they were --
// and then the computed values were thrown away.
//
// THE SIGNATURE CHECK REMAINS MANDATORY. Nothing here weakens
// NativeLibraryVerifier: its no-hash branch still returns false unless a signing
// fingerprint is configured AND the package signature is trusted, and its
// hash-mismatch branch still requires the same. This change only makes the hash
// branch reachable at all.
//
// `Variant.buildConfigFields` is the supported route: in AGP 8.11.2 it is a lazy
// MapProperty, so a Provider put here is resolved at task-execution time.
val nativeIntegrityHashes: MapProperty<String, String> =
    objects.mapProperty(String::class.java, String::class.java)

// Release variant names, collected by the onVariants callback below and consumed
// by the producer block in afterEvaluate.
//
// This list is how the producer learns which variants exist now that
// `android.applicationVariants` is gone (REMOVED in AGP 9 -- it was the legacy
// AppExtension API, and under AGP 9's default `android.newDsl=true` the android
// extension is ApplicationExtension, which never had it).
//
// ORDERING, which this depends on: AGP registers its variant-creation
// afterEvaluate callback when the plugin is applied, at the very top of this
// script, so it runs BEFORE the afterEvaluate registered further down -- Gradle
// runs afterEvaluate callbacks in registration order. The onVariants callbacks
// have therefore all fired, and this list is fully populated, by the time the
// producer reads it. The producer block already depended on exactly this
// ordering to find AGP's strip/generateBuildConfig tasks by name, so this
// introduces no new assumption -- but it is now asserted rather than assumed
// (see the isNotEmpty check in afterEvaluate).
val releaseVariantNames = mutableListOf<String>()

androidComponents.onVariants(
    androidComponents.selector().withBuildType("release")
) { variant ->
    releaseVariantNames += variant.name
    // Nullable when buildFeatures.buildConfig is off. Asserting rather than
    // null-safe-calling is deliberate: silently skipping is exactly how these
    // constants came to be blank in the first place.
    val buildConfigFields = checkNotNull(variant.buildConfigFields) {
        "buildFeatures.buildConfig is disabled for ${variant.name}, so the native " +
            "integrity hashes have nowhere to go and NativeLibraryVerifier would " +
            "ship disarmed."
    }
    for (field in listOf("NATIVE_HASH_WG_GO", "NATIVE_HASH_XRAY", "NATIVE_HASH_ROSENPASS_JNI")) {
        buildConfigFields.put(
            field,
            nativeIntegrityHashes.map { hashes ->
                // LAST LINE OF DEFENCE, and the only guard on the one code path
                // that can actually produce this constant.
                //
                // Everything that computes these hashes lives in the
                // `afterEvaluate { for (rawName in releaseVariantNames) ... }`
                // block below. If that block stops running -- an AGP task
                // rename, a variant filter, an empty variant list -- the
                // MapProperty keeps its default EMPTY MAP, every `check(...)`
                // inside it is skipped along with the block, and the old code
                // here shipped `""`. That is precisely the disarmed-but-green
                // release this whole mechanism exists to prevent.
                //
                // This check cannot be skipped that way: it runs inside the
                // Provider that generateReleaseBuildConfig resolves, so it
                // executes on every release build by construction.
                val value = hashes[field]
                check(!value.isNullOrBlank()) {
                    "$field is blank or absent: the native-integrity hashes were " +
                        "never computed for this release variant. The producer block " +
                        "(afterEvaluate/releaseVariantNames in app/build.gradle.kts) " +
                        "did not run, or AGP renamed the tasks it looks up. Refusing " +
                        "to ship a release with NativeLibraryVerifier disarmed."
                }
                com.android.build.api.variant.BuildConfigField(
                    "String",
                    // AGP writes the value verbatim into the generated Java, so a
                    // String constant has to arrive already quoted.
                    "\"" + value + "\"",
                    "SHA-256 of each shipped ABI .so, encoded abi=hash;abi=hash",
                )
            },
        )
    }
}

afterEvaluate {
    // THE NEW FAILURE MODE THIS PORT INTRODUCES, ASSERTED AWAY.
    //
    // The old `applicationVariants.all { if (buildType.name == "release") }`
    // could fail by never running its body. The replacement can fail by
    // iterating an EMPTY list -- if onVariants matched no variant, this loop is
    // a no-op, nativeIntegrityHashes keeps its default empty map, every
    // check(...) inside the loop is skipped along with the loop, and the only
    // thing left between that and a disarmed release is the
    // check(!value.isNullOrBlank()) in the consumer above.
    //
    // That is one guard too few for a control that has already shipped
    // disarmed once (#362). Fail here, on the empty list itself.
    check(releaseVariantNames.isNotEmpty()) {
        "No release variant was reported by androidComponents.onVariants, so the " +
            "native integrity hashes were never computed. Either AGP stopped " +
            "creating a `release` build type, or the onVariants callback did not " +
            "run before this afterEvaluate block. Refusing to configure a build " +
            "that could ship with NativeLibraryVerifier disarmed."
    }
    for (rawName in releaseVariantNames) {
        run {
            val variantName = rawName.replaceFirstChar { it.uppercase() }
            // HASH THE BYTES THAT ACTUALLY SHIP.
            //
            // This used to read merge${variantName}NativeLibs. AGP runs
            // strip${variantName}DebugSymbols AFTER the merge, and it is the
            // STRIPPED output that packaging consumes and that therefore lands in
            // ApplicationInfo.nativeLibraryDir -- the directory
            // NativeLibraryVerifier.verifyLibrary hashes on device
            // (NativeLibraryVerifier.kt:134). Hashing the merge stage bakes a
            // pre-strip hash the device can never reproduce.
            //
            // MEASURED on this branch, NDK 29.0.13846066, arm64-v8a:
            //   librosenpass_jni.so  merge a2c339ed.. 469480 B
            //                        strip c8f38575.. 325280 B    DIFFERENT
            //   libxray.so           merge d3dab5cf.. / strip aeca5c53..  DIFFERENT
            //   libwg-go.so          68730e8c.. unchanged (already stripped upstream)
            // x86_64 behaves identically. Two of the three engines therefore had a
            // permanently unmatchable hash and fell through to signature-only
            // verification on every device.
            //
            // librosenpass_jni.so is the worst case BY DESIGN: it is built with
            // `strip = "debuginfo"` (native/rosenpass-jni/Cargo.toml) to KEEP the
            // symbol table for debugSymbolLevel = "FULL", and AGP's strip step is
            // exactly what removes it again -- 469480 -> 325280 bytes.
            //
            // A machine with no NDK visible to AGP HIDES this: the strip task then
            // logs "Unable to strip library ... missing strip tool for ABI ..." and
            // copies the file through unchanged, so merge and strip bytes match and
            // the bug does not reproduce locally. Reading the strip output is
            // correct in both cases, which is the point -- the baked hash must not
            // depend on whether the build machine happened to have a strip tool.
            //
            // findByName plus a hard failure, never a silent skip: an `if (task !=
            // null)` with no else left the MapProperty at its default empty map and
            // shipped blank constants, with all three check(...) guards below
            // skipped along with the block that holds them.
            val stripTask = tasks.findByName("strip${variantName}DebugSymbols")
                ?: throw GradleException(
                    "AGP no longer creates :app:strip${variantName}DebugSymbols, so the " +
                        "native integrity hashes cannot be computed from the bytes that " +
                        "ship. Re-point this block at the replacement task before " +
                        "shipping; do NOT fall back to merge${variantName}NativeLibs, " +
                        "whose bytes differ from the packaged .so files."
                )
            run {
                nativeIntegrityHashes.set(
                    // A Provider read at execution time, after the .so files are
                    // stripped -- generateBuildConfig depends on stripTask below.
                    provider {
                        val nativeDirs = stripTask.outputs.files.files
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
                            //
                            // Hashing the STRIP output is sound because nothing
                            // downstream rewrites these files -- packaging copies the
                            // stripped .so into the APK and only filters ABIs. Verified
                            // by SHA-256 that every lib/<abi>/lib*.so inside the built
                            // release APK equals the stripped intermediate byte for byte.
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
                        // the installer/signature check.
                        check(wgHash.isNotBlank()) {
                            "NATIVE_HASH_WG_GO is blank - libwg-go.so was not found in " +
                                "${stripTask.name} outputs; refusing to ship a release " +
                                "with the native integrity check disarmed."
                        }
                        check(xrayHash.isNotBlank()) {
                            "NATIVE_HASH_XRAY is blank - libxray.so was not found in " +
                                "${stripTask.name} outputs; refusing to ship a release " +
                                "with the native integrity check disarmed."
                        }
                        check(rosenpassJniHash.isNotBlank()) {
                            "NATIVE_HASH_ROSENPASS_JNI is blank - librosenpass_jni.so was not " +
                                "found in ${stripTask.name} outputs; refusing to ship a release " +
                                "with the native integrity check disarmed."
                        }
                        mapOf(
                            "NATIVE_HASH_WG_GO" to wgHash,
                            "NATIVE_HASH_XRAY" to xrayHash,
                            "NATIVE_HASH_ROSENPASS_JNI" to rosenpassJniHash,
                        )
                    }
                )
                // Belt and braces on top of the provider wiring: the explicit
                // dependency guarantees the .so files are stripped before the
                // provider reads them, and the declared input makes
                // generateBuildConfig re-run whenever a shipped .so changes rather
                // than being skipped UP-TO-DATE with stale hashes.
                //
                // Not a safe call. `generateBuildConfig?.dependsOn(...)` on a miss
                // would leave the provider free to read the strip directory before
                // it is populated, with nothing to say so.
                val generateBuildConfig = tasks.findByName("generate${variantName}BuildConfig")
                    ?: throw GradleException(
                        "AGP no longer creates :app:generate${variantName}BuildConfig, " +
                            "so the native integrity hashes are not ordered after " +
                            "${stripTask.name} and could be read before the .so files " +
                            "are stripped. Re-point this block before shipping."
                    )
                generateBuildConfig.dependsOn(stripTask)
                generateBuildConfig.inputs.files(stripTask.outputs.files)
                    .withPropertyName("nativeLibsForIntegrityHash")
            }
        }
    }

    // Every release-build gate hangs off this ONE task. `findByName(...)?.` used
    // to swallow a rename: if AGP ever stopped creating preReleaseBuild the
    // safe-call would silently detach all three gates and the build would stay
    // green with the checks gone — the exact failure mode #357 documents.
    // Fail instead, so a toolchain change surfaces as a build error.
    val preRelease = tasks.findByName("preReleaseBuild") ?: throw GradleException(
        "AGP no longer creates :app:preReleaseBuild, so the release gates " +
            "(validateReleaseSecurityConfig, validateSentryDsn, buildRustLibs) are " +
            "wired to nothing. Re-point them at the replacement task before shipping."
    )
    preRelease.dependsOn(validateReleaseSecurityConfig, buildRustLibs, validateSentryDsn)
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
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
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
    implementation("androidx.navigation:navigation-compose:2.10.0")
    implementation("androidx.compose.material3:material3-window-size-class")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // ── Hilt DI ──────────────────────────────────────────────────
    // Must match the Hilt Gradle plugin version (2.57.2) or the aggregating task
    // fails with "rootComponentPackage has not been initialized".
    implementation("com.google.dagger:hilt-android:2.59")
    ksp("com.google.dagger:hilt-compiler:2.59")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")

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
