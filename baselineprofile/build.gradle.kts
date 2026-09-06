// Baseline-profile PRODUCER module.
//
// This module exists so the profile shipped in the APK/AAB is RECORDED from a
// real running app rather than written by hand. Issue #358 is on record because
// two earlier attempts committed a guessed profile: a guess does not merely fail
// to help, it actively pins the wrong classes into the startup image and makes
// cold start WORSE, and nothing in CI can tell you that happened.
//
// Nothing here ships to users. `com.android.test` produces a standalone test APK
// that instruments :app; it is never a dependency of the release artifact.
plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "app.birdo.vpn.baselineprofile"

    // Matches :app exactly. See the compileSdk note in app/build.gradle.kts --
    // the SDK repository publishes no bare `platforms;android-37`, so the minor
    // has to be named, and naming it is an AGP 9 feature.
    compileSdk = 37
    compileSdkMinor = 0

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    defaultConfig {
        // Macrobenchmark needs API 28+ to read the profile back off the device.
        // :app's own minSdk is 29, which is above that floor, so the generated
        // profile covers every device the app supports.
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    // ── The device the profile is recorded on ────────────────────────────────
    //
    // A Gradle Managed Device, NOT a connected phone: the point of #358 is that
    // the profile must be reproducible by someone who does not own the phone the
    // last one was recorded on. AGP downloads the system image, boots the
    // emulator, runs the test and tears it down.
    //
    // WHY google_apis AND NOT google_apis_playstore:
    //   Recording a baseline profile requires pulling the ART profile out of
    //   /data/misc/profiles, which needs `adb root`. Play-store images are
    //   production-signed and refuse adb root, so the run fails at the very last
    //   step, after the emulator has already booted -- the most expensive
    //   possible way to find out. aosp_atd would also allow root and boots
    //   faster, but it carries no Google Play services, and this app touches
    //   Play Integrity and Play Billing on the startup path.
    //
    // WHY API 34:
    //   ART profile transcoding is version-sensitive; recording on a mid-range
    //   supported API and letting the platform transcode upward is the
    //   documented arrangement. It is also the newest API level with a stable
    //   google_apis x86_64 ATD-free image on every host we run this from.
    //
    // KEEP IN SYNC: the `managedDevices` entry in the baselineProfile block
    // below, .github/workflows/baseline-profile.yml, and
    // scripts/generate-baseline-profile.sh all name this device by string.
    testOptions {
        managedDevices {
            localDevices {
                create("profileGenDevice") {
                    device = "Pixel 6"
                    sdkVersion = 34
                    systemImageSource = "google_apis"
                    // Pinned, not defaulted. AGP 9.4.0 warns that this defaults
                    // to x86_64 today and flips to arm64-v8a in AGP 10, at which
                    // point an unpinned device would start running the app under
                    // NDK translation -- a different execution path, recording a
                    // profile for a device nobody ships to. The runners this is
                    // generated on are x86_64.
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

// ── How the profile is produced ──────────────────────────────────────────────
//
// `useConnectedDevices = false` is deliberate and is the whole point: with it
// true, a run on a machine that happens to have a phone plugged in silently uses
// that phone, and the result is no longer reproducible. Overridable for a
// physical-device run via scripts/generate-baseline-profile.sh, which passes
// -Pandroid.testInstrumentationRunnerArguments and the connected-device flag
// explicitly, so the human doing it has to say so out loud.
baselineProfile {
    managedDevices += "profileGenDevice"
    useConnectedDevices = false
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.7.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0-rc02")
}
