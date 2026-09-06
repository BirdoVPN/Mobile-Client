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
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "BirdoVPN"
include(":app")
include(":shared")
// Baseline-profile PRODUCER (com.android.test). Never a dependency of the
// shipped app -- it builds a separate instrumentation APK that records the
// startup profile on a Gradle Managed Device. See baselineprofile/build.gradle.kts.
include(":baselineprofile")
