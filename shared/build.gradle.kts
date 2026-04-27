plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // ── Android target ───────────────────────────────────────────
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    // ── iOS targets ──────────────────────────────────────────────
    listOf(
        iosX64(),      // Intel simulator
        iosArm64(),    // Device (arm64)
        iosSimulatorArm64() // Apple Silicon simulator
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "BirdoShared"
            isStatic = true
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("io.ktor:ktor-client-core:3.2.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
                implementation("io.ktor:ktor-client-logging:3.2.3")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:3.2.3")
            }
        }

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation("io.ktor:ktor-client-darwin:3.2.3")
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
