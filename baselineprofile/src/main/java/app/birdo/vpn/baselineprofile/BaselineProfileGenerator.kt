package app.birdo.vpn.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
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
 * It walks COLD-START paths and nothing else: process start, Application
 * (Hilt graph + Sentry init), MainActivity, the Compose runtime, and the first
 * screen the nav graph resolves to. Those are the paths launches pay for, and
 * the only ones where a profile is unambiguously a win. It does NOT connect a
 * tunnel or navigate deeper: a baseline profile is a fixed budget, and padding
 * it with screens launches never reach is how a profile makes startup SLOWER,
 * which is exactly the failure #358 was opened to prevent.
 *
 * TWO starts, because there are two first screens (A2-020):
 *  - [startup]: a fresh install, which opens on Consent and then Login.
 *  - [signedInStartup]: every later cold start of a signed-in user, which
 *    opens on Connect: the globe renderer, the Home screen and the Login path
 *    that led there. The first recording covered only the fresh install, so
 *    the launches that matter most ran the 1,000-line globe interpreted.
 *    Signing in needs a test account, so this one runs only when the
 *    `birdoAccountNumber` instrumentation argument names one (see
 *    scripts/generate-baseline-profile.sh). It is skipped otherwise, never
 *    faked, and the number is never committed.
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

    @Test
    fun signedInStartup() {
        val accountNumber = InstrumentationRegistry.getArguments().getString(ARG_ACCOUNT_NUMBER)
        assumeTrue(
            "No -Pandroid.testInstrumentationRunnerArguments.$ARG_ACCOUNT_NUMBER=<24 digits>: " +
                "the signed-in start is not recorded in this run.",
            !accountNumber.isNullOrBlank(),
        )
        rule.collect(
            packageName = TARGET_PACKAGE,
            maxIterations = 8,
            stableIterations = 3,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            // Only the FIRST iteration signs in; the session persists, so every
            // later one is the real signed-in cold start straight into Connect.
            signInIfNeeded(accountNumber!!)
            device.wait(Until.hasObject(By.res(TAG_CONNECT_BUTTON)), SIGN_IN_TIMEOUT_MS)
            // Let the globe draw for a while so its render path is observed,
            // not just its first frame.
            device.wait(Until.hasObject(By.res(TAG_NEVER_PRESENT)), GLOBE_OBSERVE_MS)
        }
    }

    /**
     * Consent, then the Anonymous tab. Found through the Compose test tags,
     * which MainActivity exposes as resource ids (testTagsAsResourceId).
     */
    private fun MacrobenchmarkScope.signInIfNeeded(accountNumber: String) {
        if (device.wait(Until.hasObject(By.res(TAG_CONNECT_BUTTON)), SHORT_WAIT_MS)) return
        // A fresh install opens on Consent, whose accept button is below the
        // fold; on Login this finds nothing and clicks nothing.
        device.findObject(By.scrollable(true))
            ?.scrollUntil(Direction.DOWN, Until.findObject(By.res(TAG_CONSENT_ACCEPT)))
            ?.click()
        device.wait(Until.findObject(By.res(TAG_TAB_ANONYMOUS)), UI_TIMEOUT_MS)?.click()
        device.wait(Until.findObject(By.res(TAG_ANON_ID_FIELD)), UI_TIMEOUT_MS)?.text = accountNumber
        device.findObject(By.res(TAG_ANON_SUBMIT))?.click()
    }

    private companion object {
        /** Instrumentation argument carrying the test account's 24-digit number. */
        const val ARG_ACCOUNT_NUMBER = "birdoAccountNumber"

        // Mirrors of app.birdo.vpn.ui.TestTags (this module cannot depend on :app).
        const val TAG_CONSENT_ACCEPT = "consent_accept"
        const val TAG_TAB_ANONYMOUS = "login_tab_anonymous"
        const val TAG_ANON_ID_FIELD = "login_anonymous_id_field"
        const val TAG_ANON_SUBMIT = "login_anonymous_submit"
        const val TAG_CONNECT_BUTTON = "connect_button"
        /** A tag nothing carries: waiting for it to appear is a bounded pause. */
        const val TAG_NEVER_PRESENT = "baseline_profile_idle"

        const val SHORT_WAIT_MS = 3_000L
        const val SIGN_IN_TIMEOUT_MS = 30_000L
        const val GLOBE_OBSERVE_MS = 4_000L

        /**
         * The release applicationId. NOT `app.birdo.vpn.debug` -- the profile is
         * recorded against the `nonMinifiedRelease` variant the plugin builds,
         * which carries no applicationIdSuffix.
         */
        const val TARGET_PACKAGE = "app.birdo.vpn"
        const val UI_TIMEOUT_MS = 10_000L
    }
}
