// Top-level build file
// Coordinated toolchain modernization (the parked landmine):
//   Kotlin 2.1 -> 2.2.20, KSP -> 2.2.20-2.0.2, Hilt 2.53.1 -> 2.57.1 (KSP2; <2.59
//   to stay off AGP 9), AGP 8.7.3 -> 8.11.1 (its lint can analyze Kotlin 2.2
//   bytecode; 8.7.3 crashed lintAnalyzeDebug), Gradle 8.11.1 -> 8.14.3.
//   See app/shared build.gradle.kts (compilerOptions DSL + ktor 3.3.3 restored).
plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20" apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.2.20-2.0.2" apply false
}
