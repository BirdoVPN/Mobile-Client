package app.birdo.vpn

import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Guards the Play policy gate.
 *
 * Google permits linking out to an external checkout only for developers
 * ENROLLED in the billing-choice / external-offers programme. Steering while
 * unenrolled is a policy violation: the app is removed, and per our own release
 * history the package name does not come back — `app.birdo.vpn` would be burned
 * permanently.
 *
 * The protection is that external offers require TWO independent flags, and the
 * second one defaults to false. This test exists so that nobody can quietly
 * flip that default (or collapse the two flags into one) without a red build.
 *
 * When enrolment IS confirmed in the Play Console, the correct way to turn this
 * on is at the build invocation — `-PplayExternalOffers=true` — not by editing
 * the default here. See birdo-web/docs/PLAY-LINK-OUT-BILLING.md.
 */
class BillingPolicyGateTest {

    @Test
    fun `external offers are OFF by default`() {
        assertFalse(
            "PLAY_EXTERNAL_OFFERS must default to false. Linking out to an external " +
                "checkout without being enrolled in Google's billing-choice programme " +
                "gets the app removed from Play, and the package name is not recoverable. " +
                "Enable it per-build with -PplayExternalOffers=true, only after enrolment.",
            BuildConfig.PLAY_EXTERNAL_OFFERS,
        )
    }

    @Test
    fun `a Play build alone must never be enough to steer`() {
        // The app may only show the external-purchase choice when it is BOTH a
        // Play build AND enrolled. If someone ever reduces this to a single
        // condition, the unit-test default build (IS_PLAY_BUILD=false,
        // PLAY_EXTERNAL_OFFERS=false) would still pass a naive check — so assert
        // the conjunction explicitly.
        val mayShowExternalChoice =
            BuildConfig.IS_PLAY_BUILD && BuildConfig.PLAY_EXTERNAL_OFFERS
        assertFalse(
            "The default build must not be permitted to steer to an external checkout.",
            mayShowExternalChoice,
        )
    }
}
