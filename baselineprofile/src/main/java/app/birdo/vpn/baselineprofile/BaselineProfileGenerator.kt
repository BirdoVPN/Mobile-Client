package app.birdo.vpn.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

/**
 * RECORDS the baseline profile that ships in the release APK/AAB.
 *
 * Run it with `:app:generateReleaseBaselineProfile`, or via
 * `scripts/generate-baseline-profile.sh`, which is the only invocation that is
 * documented and reproducible. The output lands in
 * `app/src/release/generated/baselineProfiles/baseline-prof.txt` and is
 * committed; the release build only consumes it.
 *
 * WHAT THIS DOES AND DOES NOT COVER
 *
 * It walks the COLD-START path and nothing else: process start, Application
 * (Hilt graph + Sentry init), MainActivity, the Compose runtime, and whichever
 * first screen the nav graph resolves to. That is the path every launch pays for
 * and the only one where a profile is unambiguously a win.
 *
 * It deliberately does NOT sign in, connect a tunnel or navigate deeper. A
 * baseline profile is a fixed budget -- every class listed is one more class the
 * platform AOT-compiles and pages in at launch. Padding it with screens most
 * launches never reach is the mechanism by which a profile makes startup slower,
 * which is exactly the failure #358 was opened to prevent.
 *
 * The app opens on Consent/Login for a signed-out user, so the profile is
 * recorded against a fresh install, which is the state a first launch is in.
 */
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        // Repeat so a one-off scheduling artefact on the emulator cannot decide
        // what gets pinned. The rule unions the classes/methods seen across
        // iterations, so more iterations mean a MORE complete startup profile,
        // not a longer one -- the startup path is the same every time.
        maxIterations = 12,
        stableIterations = 3,
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()

        // Wait for real first content rather than trusting startActivityAndWait's
        // first-frame signal. Compose draws a frame before the first screen has
        // composed, and stopping there records a profile that covers the window
        // background and little else.
        device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)
        device.waitForIdle(UI_TIMEOUT_MS)
    }

    private companion object {
        /**
         * The release applicationId. NOT `app.birdo.vpn.debug` -- the profile is
         * recorded against the `nonMinifiedRelease` variant the plugin builds,
         * which carries no applicationIdSuffix.
         */
        const val TARGET_PACKAGE = "app.birdo.vpn"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
