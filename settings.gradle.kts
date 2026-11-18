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
