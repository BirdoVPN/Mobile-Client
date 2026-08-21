package app.birdo.vpn.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Google Play rail's decisions, asserted without a device, a Play Store or
 * a network. The iOS equivalent is `BirdoVPNTests/StoreCatalogTests.swift`.
 */
class PlayStoreCatalogTest {

    // ── Refusal classification ──────────────────────────────────────────────

    /**
     * The code is authoritative. These five strings are the whole contract with
     * GooglePlayStoreService; if the backend renames one, this test is the only
     * thing standing between a paying customer and a wrong explanation.
     */
    @Test
    fun `each backend refusal code maps to its own outcome`() {
        assertEquals(
            StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT,
            StoreLinkRefusal.classify(409, "STORE_TRANSACTION_ALREADY_LINKED"),
        )
        assertEquals(
            StoreLinkRefusal.PRODUCT_UNMAPPED,
            StoreLinkRefusal.classify(409, "STORE_PRODUCT_UNMAPPED"),
        )
        assertEquals(
            StoreLinkRefusal.PURCHASE_NOT_RECOGNISED,
            StoreLinkRefusal.classify(401, "GOOGLE_PLAY_PURCHASE_NOT_FOUND"),
        )
        assertEquals(
            StoreLinkRefusal.STATE_UNRECOGNISED,
            StoreLinkRefusal.classify(503, "GOOGLE_PLAY_STATE_UNRECOGNISED"),
        )
        assertEquals(
            StoreLinkRefusal.TRANSIENT,
            StoreLinkRefusal.classify(503, "GOOGLE_PLAY_VERIFICATION_UNAVAILABLE"),
        )
    }

    /**
     * THE 401 TRAP. `GOOGLE_PLAY_PURCHASE_NOT_FOUND` arrives on a 401, so a
     * status-first classifier would tell an already-signed-in user to sign in —
     * the exact iOS bug fixed in e3da2d5 — and, on Android, would also send the
     * call down BirdoRepository's refresh path and spend a single-use refresh
     * token on a healthy session.
     */
    @Test
    fun `a coded 401 is not a sign-in problem`() {
        val coded = StoreLinkRefusal.classify(401, "GOOGLE_PLAY_PURCHASE_NOT_FOUND")
        assertEquals(StoreLinkRefusal.PURCHASE_NOT_RECOGNISED, coded)
        assertFalse(
            "a coded 401 must never be presented as a sign-in problem",
            coded == StoreLinkRefusal.NEEDS_SIGN_IN,
        )
        // ...and an UNCODED 401 still is one.
        assertEquals(StoreLinkRefusal.NEEDS_SIGN_IN, StoreLinkRefusal.classify(401, null))
    }

    /**
     * A 409 we do not have a code for is still a conflict: the server decided,
     * and the identical request gets the identical answer. Treating it as
     * transient would retry into a 20-per-60s bucket forever.
     */
    @Test
    fun `an unrecognised 409 is terminal, not transient`() {
        val refusal = StoreLinkRefusal.classify(409, "SOMETHING_NOBODY_HAS_TAUGHT_THIS_CLIENT")
        assertEquals(StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT, refusal)
        assertTrue(refusal.isTerminal)
    }

    @Test
    fun `status-only classification covers the rest`() {
        assertEquals(StoreLinkRefusal.NEEDS_SIGN_IN, StoreLinkRefusal.classify(403, null))
        assertEquals(StoreLinkRefusal.RATE_LIMITED, StoreLinkRefusal.classify(429, null))
        assertEquals(StoreLinkRefusal.TRANSIENT, StoreLinkRefusal.classify(500, null))
        // No status at all: a transport failure the client minted itself.
        assertEquals(StoreLinkRefusal.TRANSIENT, StoreLinkRefusal.classify(null, null))
    }

    /** Only the two conflicts are terminal. Everything else must be retryable. */
    @Test
    fun `terminality is exactly the conflict refusals`() {
        val terminal = StoreLinkRefusal.entries.filter { it.isTerminal }.toSet()
        assertEquals(
            setOf(
                StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT,
                StoreLinkRefusal.PRODUCT_UNMAPPED,
            ),
            terminal,
        )
    }

    /**
     * The invariant the whole rail is built on. If anyone ever makes this
     * conditional, a refused purchase can be acknowledged — telling Google to
     * keep money for an entitlement Birdo's database does not have.
     */
    @Test
    fun `no refusal may ever be acknowledged`() {
        StoreLinkRefusal.entries.forEach {
            assertFalse("$it must not be acknowledged", it.shouldAcknowledge)
        }
    }

    /** Every refusal must have copy; a blank banner explains nothing. */
    @Test
    fun `every refusal carries a fallback message`() {
        StoreLinkRefusal.entries.forEach {
            assertTrue("$it has no fallback copy", it.fallbackMessage.length > 30)
        }
    }

    /** A refusal the user cannot act on is not an alarm. */
    @Test
    fun `unactionable refusals are not alarming`() {
        assertFalse(StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT.isAlarming)
        assertFalse(StoreLinkRefusal.PRODUCT_UNMAPPED.isAlarming)
        assertFalse(StoreLinkRefusal.STATE_UNRECOGNISED.isAlarming)
        assertFalse(StoreLinkRefusal.PURCHASE_NOT_RECOGNISED.isAlarming)
        assertTrue(StoreLinkRefusal.NEEDS_SIGN_IN.isAlarming)
    }

    // ── Zero products ───────────────────────────────────────────────────────

    /**
     * THE STATE THAT MATTERS TODAY. The Play Console products do not exist yet,
     * so a real device gets an OK response with an EMPTY list. That must be an
     * explained state, never Ready — Ready is what draws a purchase button, and
     * a button that dead-ends in "purchasing unavailable" is what got the iOS
     * build rejected under Guideline 2.1.
     */
    @Test
    fun `zero products is an explained unavailable state, never ready`() {
        val state = StorefrontState.decide(
            isLoading = false,
            hasAttempted = true,
            purchasableCount = 0,
            failure = StorefrontFailure.NO_PRODUCTS,
        )
        assertTrue("zero products must not be Ready", state is StorefrontState.Unavailable)
        val unavailable = state as StorefrontState.Unavailable
        assertEquals(StorefrontFailure.NO_PRODUCTS, unavailable.failure)
        assertTrue(unavailable.message.length > 60)
        // No retry offered: nothing the user does changes an unpublished product.
        assertFalse(unavailable.canRetry)
        // The copy must not read like a crash, and must not promise a date.
        val lowered = unavailable.message.lowercase()
        listOf("error", "failed", "crash", "soon", "next week").forEach {
            assertFalse("zero-products copy must not say '$it'", lowered.contains(it))
        }
    }

    /** An empty answer with NO recorded failure still gets the honest sentence. */
    @Test
    fun `an empty catalogue with no recorded failure defaults to no-products`() {
        val state = StorefrontState.decide(
            isLoading = false,
            hasAttempted = true,
            purchasableCount = 0,
            failure = null,
        )
        assertEquals(
            StorefrontState.Unavailable(StorefrontFailure.NO_PRODUCTS),
            state,
        )
    }

    /**
     * "Play has nothing to sell" and "we could not ask Play" are different
     * facts and must not share a sentence. Collapsing them is how a user with a
     * flat connection is told the product does not exist.
     */
    @Test
    fun `each failure has its own distinct copy and its own retryability`() {
        val messages = StorefrontFailure.entries.map { it.message }
        assertEquals("failure copy must be distinct", messages.size, messages.toSet().size)
        assertFalse(StorefrontFailure.NO_PRODUCTS.isRetryable)
        assertTrue(StorefrontFailure.QUERY_FAILED.isRetryable)
        assertTrue(StorefrontFailure.PLAY_STORE_UNAVAILABLE.isRetryable)
        assertTrue(StorefrontFailure.SUBSCRIPTIONS_UNSUPPORTED.isRetryable)
    }

    @Test
    fun `a loaded catalogue is ready and survives a background refresh`() {
        assertEquals(
            StorefrontState.Ready,
            StorefrontState.decide(false, true, purchasableCount = 4, failure = null),
        )
        // A refresh already in flight must not blank a good catalogue.
        assertEquals(
            StorefrontState.Ready,
            StorefrontState.decide(true, true, purchasableCount = 4, failure = null),
        )
    }

    @Test
    fun `nothing asked yet is Loading, not Unavailable`() {
        assertEquals(
            StorefrontState.Loading,
            StorefrontState.decide(false, hasAttempted = false, purchasableCount = 0, failure = null),
        )
        assertEquals(
            StorefrontState.Loading,
            StorefrontState.decide(true, hasAttempted = false, purchasableCount = 0, failure = null),
        )
    }

    /** A spinner with no ceiling is indistinguishable from a broken app. */
    @Test
    fun `the load deadline is bounded and short`() {
        assertTrue(StorefrontState.LOAD_DEADLINE_MS in 5_000..30_000)
    }

    // ── Refusal memory (rate-limit protection) ──────────────────────────────

    @Test
    fun `a terminal refusal is never re-presented in this session`() {
        val memory = PurchaseRefusalMemory { 0L }
        memory.record("tok", StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT)
        assertTrue(memory.shouldSkip("tok"))
        // ...not even much later. Terminal means terminal.
        val laterMemory = PurchaseRefusalMemory { 0L }
        laterMemory.record("tok", StoreLinkRefusal.PRODUCT_UNMAPPED)
        assertTrue(laterMemory.shouldSkip("tok"))
        // A different token is unaffected.
        assertFalse(laterMemory.shouldSkip("other"))
    }

    /**
     * A transient refusal must not be hammered either: the reconcile sweep runs
     * on every resume and the endpoint allows 20 requests per 60 seconds.
     */
    @Test
    fun `a transient refusal is suppressed for the rate-limit window then retried`() {
        var now = 0L
        val memory = PurchaseRefusalMemory { now }
        memory.record("tok", StoreLinkRefusal.TRANSIENT)
        assertTrue("still inside the cooldown", memory.shouldSkip("tok"))
        now = PurchaseRefusalMemory.COOLDOWN_MS - 1
        assertTrue("still inside the cooldown", memory.shouldSkip("tok"))
        now = PurchaseRefusalMemory.COOLDOWN_MS
        assertFalse("cooldown elapsed, retry is allowed", memory.shouldSkip("tok"))
    }

    /** Restore Purchases is an explicit ask; it clears everything. */
    @Test
    fun `clear forgets terminal refusals too`() {
        val memory = PurchaseRefusalMemory { 0L }
        memory.record("tok", StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT)
        memory.clear()
        assertFalse(memory.shouldSkip("tok"))
    }

    // ── Catalogue ───────────────────────────────────────────────────────────

    /**
     * These ids are half of a cross-repo contract. The other half is
     * DEFAULT_GOOGLE_PRODUCT_PLANS in birdo-web
     * (backend/src/payments/google/google-notification.ts). A rename on either
     * side and the server answers STORE_PRODUCT_UNMAPPED, so the purchase takes
     * the customer's money and unlocks nothing.
     */
    @Test
    fun `product ids match the backend plan map`() {
        assertEquals(
            listOf("app.birdo.vpn.operative", "app.birdo.vpn.sovereign"),
            BirdoPlayProduct.allProductIds,
        )
        assertEquals("OPERATIVE", BirdoPlayProduct.OPERATIVE.planSlug)
        assertEquals("SOVEREIGN", BirdoPlayProduct.SOVEREIGN.planSlug)
    }

    /** RECON is free and has no product. Guessing one would sell nothing twice. */
    @Test
    fun `the free plan has no product and an unknown id is not guessed`() {
        assertNull(BirdoPlayProduct.forPlan("RECON"))
        assertNull(BirdoPlayProduct.forPlan("nonsense"))
        assertNull(BirdoPlayProduct.fromProductId("app.birdo.vpn.operative.monthly"))
        assertNotNull(BirdoPlayProduct.forPlan("operative"))
        assertEquals(
            BirdoPlayProduct.SOVEREIGN,
            BirdoPlayProduct.fromProductId("app.birdo.vpn.sovereign"),
        )
    }

    /**
     * The base-plan ids are what an offer is matched on. A typo here does not
     * fail loudly — it silently yields zero purchasable offers, which the
     * storefront then reports as "no products", hiding a build mistake behind
     * an honest-looking sentence.
     */
    @Test
    fun `base plan ids are the Play Console strings the UI toggles on`() {
        assertEquals("monthly", BirdoBillingPeriod.MONTHLY.basePlanId)
        assertEquals("yearly", BirdoBillingPeriod.YEARLY.basePlanId)
        assertEquals(BirdoBillingPeriod.YEARLY, BirdoBillingPeriod.fromKey("yearly"))
        assertEquals(BirdoBillingPeriod.MONTHLY, BirdoBillingPeriod.fromKey("MONTHLY"))
        assertNull(BirdoBillingPeriod.fromKey("quarterly"))
    }

    // ── Restore copy ────────────────────────────────────────────────────────

    /** Restore must answer in every case, including the common empty one. */
    @Test
    fun `every restore outcome says something`() {
        assertTrue(StoreRestoreOutcome.Restored(1).message.contains("restored"))
        assertTrue(StoreRestoreOutcome.Restored(2).message.startsWith("2 "))
        assertTrue(StoreRestoreOutcome.NothingToRestore.message.length > 40)
        assertTrue(StoreRestoreOutcome.Refused("x").message == "x")
        assertTrue(StoreRestoreOutcome.Restored(1).isSuccess)
        assertFalse(StoreRestoreOutcome.NothingToRestore.isSuccess)
        assertFalse(StoreRestoreOutcome.Failed("x").isSuccess)
    }
}
