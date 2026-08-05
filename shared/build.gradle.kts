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

    sourceSets {
        val commonMain by getting {
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
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.3.3")
            }
        }

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val macosArm64Main by getting
        val macosX64Main by getting
        // Named appleMain, not iosMain: it now feeds macOS too. The Darwin ktor
        // engine is correct for every Apple platform, so the macOS targets share
        // this source set rather than duplicating it — which also means the
        // shared Kotlin cannot drift between iOS and macOS.
        val appleMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            macosArm64Main.dependsOn(this)
            macosX64Main.dependsOn(this)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.3.3")
            }
        }
    }
}

android {
    namespace = "app.birdo.vpn.shared"
    compileSdk = 35
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    defaultConfig {
        minSdk = 29
    }
}
