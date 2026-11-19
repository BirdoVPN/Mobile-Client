// ONE source per coordinate (MR-1794). gradle/verification-metadata.xml pins
// the bytes of every file a build resolves, so a coordinate that two
// repositories both serve, with different bytes, fails the build depending on
// which one a runner's Gradle cache happens to hold. The Gradle Plugin Portal
// did exactly that: it serves its own plugin-marker POMs for the Kotlin
// plugins (693 bytes for org.jetbrains.kotlin.multiplatform 2.4.10, against
// Maven Central's 1416), and Android CI failed verification on the Portal's
// copy (run 44376353548, Analyze). Every plugin and dependency this build
// resolves is on Google's Maven or Maven Central (checked for all 797
// components in the metadata), so the Portal is not listed at all. A plugin
// that exists ONLY there would come back as gradlePluginPortal { content {
// includeGroup("<its group>") } }, never as an unfiltered fallback.
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    // NO PLUGIN-MARKER LOOKUPS (MOBILE-PLUGIN-MARKER-FLAKE). A plugin
    // requested by id and version is normally found through its marker POM,
    // <id>:<id>.gradle.plugin:<version>, whose only content is a dependency on
    // the module that implements it. That lookup was the request Maven
    // Central's rate limit (429) kept failing: "Plugin [id:
    // 'org.jetbrains.kotlin.multiplatform', version: '2.4.10'] was not found"
    // in Lint Check, Unit Tests, Build APK and the release on 2026-11-19, on
    // main too. Each id below goes straight to the module its marker names
    // (read from the published markers), so no marker is ever requested and
    // the module is one gradle/verification-metadata.xml already pins. Not a
    // blanket org.jetbrains.kotlin.* -> kotlin-gradle-plugin rule: the compose
    // and serialization plugins live in modules of their own.
    //
    // Lint Check configures the build with every marker unreachable, so a
    // plugin added to the root plugins {} block without a line here fails
    // there. Versionless requests (the subprojects') are left alone: their
    // plugins are already on the root build's classpath.
    resolutionStrategy {
        val implementations = mapOf(
            "com.android.application" to "com.android.tools.build:gradle",
            "com.android.library" to "com.android.tools.build:gradle",
            "com.android.kotlin.multiplatform.library" to "com.android.tools.build:gradle",
            "org.jetbrains.kotlin.multiplatform" to "org.jetbrains.kotlin:kotlin-gradle-plugin",
            "org.jetbrains.kotlin.plugin.compose" to "org.jetbrains.kotlin:compose-compiler-gradle-plugin",
            "org.jetbrains.kotlin.plugin.serialization" to "org.jetbrains.kotlin:kotlin-serialization",
            "com.google.dagger.hilt.android" to "com.google.dagger:hilt-android-gradle-plugin",
            "com.google.devtools.ksp" to "com.google.devtools.ksp:symbol-processing-gradle-plugin",
            "androidx.baselineprofile" to "androidx.benchmark:benchmark-baseline-profile-gradle-plugin",
        )
        eachPlugin {
            val module = implementations[requested.id.id]
            val version = requested.version
            if (module != null && version != null) {
                useModule("$module:$version")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BirdoVPN"
include(":app")
include(":shared")
// Baseline-profile PRODUCER (com.android.test). Never a dependency of the
// shipped app -- it builds a separate instrumentation APK that records the
// startup profile on a Gradle Managed Device. See baselineprofile/build.gradle.kts.
include(":baselineprofile")
