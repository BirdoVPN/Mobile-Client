plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // ── Android target ───────────────────────────────────────────
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    // ── Apple targets ────────────────────────────────────────────
    // macOS is here because the Mac App Store build is a NATIVE macOS app, not
    // Mac Catalyst: Go has no ios-macabi target, so libwg-go.a cannot be built
    // for Catalyst and the whole tunnel would be impossible there. A native
    // macOS app needs its own Kotlin/Native slices, and both arches ship
    // because the Mac App Store serves one universal binary to Apple Silicon
    // and Intel alike.
    listOf(
        iosX64(),           // Intel simulator
        iosArm64(),         // Device (arm64)
        iosSimulatorArm64(),// Apple Silicon simulator
        macosArm64(),       // Apple Silicon Macs
        macosX64()          // Intel Macs
    ).forEach { appleTarget ->
        appleTarget.binaries.framework {
            baseName = "BirdoShared"
            isStatic = true
        }
    }

    // Typed source-set accessors (commonMain, appleMain, ...) instead of
    // `val x by getting`: Gradle 9.6 deprecates the delegate form, and the
    // template-created sets (appleMain) are not yet registered when the
    // delegate would resolve them, so the provider form is also the one that
    // works.
    sourceSets {
        commonMain {
            dependencies {
                // PINNED to 1.9.0 — must match the Kotlin 2.2.21 pin in the root
                // build.gradle.kts. 1.10.0/1.11.0 ship Kotlin/Native klibs with ABI
                // 2.3.0 (built by the 2.3.x compiler), which Kotlin 2.2.21 cannot
                // read: the iOS framework fails with "incompatible ABI version".
                // The JVM/Android path tolerates the skew, which is why this only
                // ever broke the iOS build. Bump this ONLY together with Kotlin
                // (and KSP/Hilt/Compose, which are version-locked to it).
                // Pinned to 1.9.0: 1.11.0's iOS Kotlin/Native klib is built
                // against a newer Kotlin/Native ABI than the project's 2.2.21,
                // so `compileKotlinIosArm64` fails "KLIB resolver: could not find
                // …serialization-json-iosArm64…1.11.0.klib". (This is a KMP
                // module; the Android-only app module can and does use 1.11.0.)
                // Bumping this needs the same Kotlin/AGP-9 toolchain move the
                // other deferred dependency upgrades need.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
                implementation("io.ktor:ktor-client-core:3.3.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.3.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.3.3")
                implementation("io.ktor:ktor-client-logging:3.3.3")
            }
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }

        androidMain {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.3.3")
            }
        }

        // appleMain comes from Kotlin's default hierarchy template: it sits
        // over every Apple target declared above (iOS device, both
        // simulators, both macOS), so the Darwin ktor engine and the shared
        // Kotlin are written once and cannot drift between iOS and macOS.
        // Wiring the same edges by hand (`by creating` + dependsOn) made the
        // plugin skip the template and warn on every build.
        appleMain {
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.3.3")
            }
        }
    }
}

// The generated `android {}` accessor for a KMP + com.android.library module
// still targets AGP's legacy LibraryExtension type, which AGP 9 deprecates;
// configuring the public DSL type by name is the supported form.
configure<com.android.build.api.dsl.LibraryExtension> {
    namespace = "app.birdo.vpn.shared"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = 29
    }
    lint {
        // Same bar as the app module; the shared policy file explains the
        // few ignored checks (dependency bumps are Dependabot's).
        abortOnError = true
        warningsAsErrors = true
        lintConfig = rootProject.file("lint.xml")
    }
}
