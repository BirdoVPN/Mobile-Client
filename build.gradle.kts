// Top-level build file
//
// TOOLCHAIN: AGP 9.4.0 / Gradle 9.7.1 / compileSdk 37 / Hilt 2.59. These four
// move together or not at all -- each one refuses the others' predecessors:
//
//   * AGP 9.4.0 requires Gradle >= 9.6.0 (VersionCheckPlugin.GRADLE_MIN_VERSION).
//   * AGP 8.11.2 CANNOT run on Gradle >= 9.6.0 -- it calls
//     org.gradle.api.problems.internal.InternalProblems, removed in 9.6.0.
//   * compileSdk 37 requires AGP 9: the SDK repository publishes no bare
//     `platforms;android-37`, only android-37.0/37.1/37.2, and resolving a
//     minor-versioned platform is an AGP 9 feature (compileSdkMinor).
//   * androidx.hilt:hilt-navigation-compose 1.4.0 and
//     androidx.navigation:navigation-compose 2.10.0 declare
//     minCompileSdk=37 / minAGP=9.1.0 in their aar-metadata.
//
// HILT MUST BE >= 2.59 ON AGP 9, and the previous comment here had it exactly
// backwards ("<2.59 to stay off AGP 9"). Dagger 2.57.2's HiltGradlePlugin does
//   project.extensions.findByType(com.android.build.gradle.BaseExtension::class)
//     ?: error("Android BaseExtension not found.")
// on its default path. Under AGP 9 `android.newDsl` defaults to true, so the
// extension is ApplicationExtension and BaseExtension is never registered --
// configuration fails outright. Verified empirically: see the PR body. 2.59 is
// the first release with zero BaseExtension/BaseVariant references, and its POM
// still sits on kotlin-bom 2.2.0, matching Kotlin 2.2.21 (2.60.x moves to
// kotlin-bom 2.3.21 and would skew the STRICT lock against the 2.2.21 compiler).
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("com.android.library") version "9.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("com.google.dagger.hilt.android") version "2.59" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
