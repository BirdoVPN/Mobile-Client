package app.birdo.vpn

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * APPLE 2.1(b), 2026-09-29 — "no payment sheet triggered to complete payment".
 *
 * The rejected flow, from App Review's own route: a guest opens Manage
 * Subscription, the button reads "Sign in to subscribe", and `purchase()`
 * returned early after raising the sign-in sheet. On success ContentView also
 * set `selectedTab = .home`, so the subscription screen was dismissed too. The
 * reviewer landed on Connect with no App Store sheet and no sign that the
 * button had quietly become "Subscribe".
 *
 * Why a JVM test reads Swift, again: iOS never builds in PR CI, so nothing
 * else fails when this regresses. Same reason [QuickSelectGuardTest] and
 * [IosVersionFloorWiringTest] do it.
 */
class IosPurchaseResumeTest {

    private val repoRoot: File by lazy {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile
                ?: error("settings.gradle.kts not found above ${File("").absolutePath}")
        }
        dir
    }

    private fun source(path: String): String {
        val file = File(repoRoot, path)
        assertTrue("scan target is missing: $path — this test would be vacuous", file.isFile)
        val text = file.readText()
        assertTrue("scan target $path is empty", text.length > 200)
        return text
    }

    private val service by lazy { source("iosApp/iosApp/Services/StoreKitService.swift") }
    private val contentView by lazy { source("iosApp/iosApp/ContentView.swift") }

    @Test
    fun `a purchase blocked by sign-in is remembered, not discarded`() {
        val guard = Regex("""guard isSignedIn\(\) else \{[^}]*?pendingPurchaseProductId = product\.id""",
                          RegexOption.DOT_MATCHES_ALL)
        assertTrue(
            "purchase() raises the sign-in sheet without recording WHICH product the user " +
                "wanted. That is the 2.1(b) rejection: the tap is lost and no payment sheet " +
                "ever appears.",
            guard.containsMatchIn(service),
        )
    }

    @Test
    fun `sign-in resumes the purchase, and does it after linking`() {
        assertTrue(
            "ContentView never calls resumePendingPurchase() — signing in still ends the " +
                "purchase attempt.",
            contentView.contains("resumePendingPurchase()"),
        )
        val link = contentView.indexOf("linkExistingEntitlementsAfterSignIn()")
        val resume = contentView.indexOf("resumePendingPurchase()")
        assertTrue("both calls must be present", link >= 0 && resume >= 0)
        assertTrue(
            "resumePendingPurchase() must run AFTER linkExistingEntitlementsAfterSignIn(), or " +
                "an account that already owns the plan is charged a second time instead of " +
                "having its entitlement restored.",
            resume > link,
        )
    }

    @Test
    fun `an already-owned product is not bought again`() {
        assertTrue(
            "resumePendingPurchase() must check Transaction.currentEntitlements before buying. " +
                "Reading our own plan snapshot instead is unsafe — it refreshes asynchronously " +
                "and is still stale at this point.",
            Regex("""resumePendingPurchase[\s\S]{0,1400}?Transaction\.currentEntitlements""")
                .containsMatchIn(service),
        )
    }

    @Test
    fun `the pending purchase is cleared before the attempt and on sign-out`() {
        assertTrue(
            "resumePendingPurchase() must null the slot BEFORE awaiting the purchase, or a " +
                "failure leaves a purchase that retries itself on every sign-in.",
            Regex("""guard let productId = pendingPurchaseProductId else \{ return \}\s*\n\s*pendingPurchaseProductId = nil""")
                .containsMatchIn(service),
        )
        assertTrue(
            "signing out must cancel the pending purchase, or the NEXT account to sign in on " +
                "this device gets charged for it.",
            contentView.contains("cancelPendingPurchase()"),
        )
    }
}
