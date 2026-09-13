plugins {
    id("org.jetbrains.kotlin.multiplatform")
    // AGP's KMP library plugin (OPEN-WORK G4, 2026-09-13): KGP 2.4 deprecates
    // org.jetbrains.kotlin.multiplatform + com.android.library in one project,
    // and AGP 9's new DSL does not support that pairing at all. The Android
    // target is configured in kotlin { androidLibrary { } } below; there is no
    // android { } block any more.
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // ── Android target ───────────────────────────────────────────
    androidLibrary {
        namespace = "app.birdo.vpn.shared"
        compileSdk = 35
        minSdk = 29
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        // commonTest (FlagUtils/FormatUtils/InputValidator, 29 tests) runs on
        // the JVM as the Android host test; this is what `:shared:allTests`
        // executes in CI.
        withHostTestBuilder { }
        lint {
            abortOnError = true
            warningsAsErrors = true
            lintConfig = rootProject.file("lint.xml")
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
                // Same 1.11.0 as the app module. This sat on 1.9.0 while the root
                // Kotlin pin was 2.2.21: 1.10+/1.11 ship Kotlin/Native klibs with
                // ABI 2.3.0, which the 2.2.x compiler cannot read — the JVM/Android
                // path tolerates the skew, so only `compileKotlinIosArm64` ever
                // broke ("KLIB resolver: could not find …serialization-json-
                // iosArm64…1.11.0.klib"). Kotlin 2.4.20 (2026-09-13, OPEN-WORK G4)
                // reads it. Keep this and the root Kotlin version moving together;
                // the iOS workflow is the only build that exercises the K/N side.
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
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
