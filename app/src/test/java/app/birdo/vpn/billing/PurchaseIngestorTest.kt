package app.birdo.vpn.billing

import app.birdo.vpn.testing.StringsXml
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering invariant, pinned.
 *
 * A Play purchase must be ACKNOWLEDGED only after the server has accepted it.
 * Acknowledge early and Google is told to keep the money for an entitlement
 * Birdo's database does not have; the customer has paid for nothing and there
 * is no refund, because we said not to give one. The iOS rail states the same
 * rule about `Transaction.finish()`.
 *
 * These tests record the ORDER of the two side effects rather than asserting
 * that each merely happened, because "both happened" is true of the broken
 * implementation too.
 */
class PurchaseIngestorTest {

    private val accepted = StoreLinkOutcome.Accepted(
        GooglePlayLinkResponse(linked = true, plan = "OPERATIVE", state = "ACTIVE"),
    )

    private fun purchased(
        token: String = "tok-1",
        isAcknowledged: Boolean = false,
        isBound: Boolean = true,
    ) = IngestablePurchase(
        purchaseToken = token,
        state = IngestablePurchase.State.PURCHASED,
        isAcknowledged = isAcknowledged,
        productIds = listOf(BirdoPlayProduct.OPERATIVE.productId),
        isBound = isBound,
    )

    // ── The ordering invariant ──────────────────────────────────────────────

    @Test
    fun `acknowledge happens only AFTER the server accepts`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; accepted },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)

        assertTrue(outcome is IngestOutcome.Accepted)
        assertEquals(
            "the server must be asked before Google is told we delivered",
            listOf("link", "acknowledge"),
            calls,
        )
    }

    /**
     * The other half of the invariant, and the one that actually loses money if
     * it regresses: a refusal must leave the purchase UNACKNOWLEDGED. Google
     * then auto-refunds it, which is the correct outcome for a purchase Birdo
     * could not honour — and the server's own acknowledge covers the case where
     * the entitlement WAS written.
     */
    @Test
    fun `a refused purchase is never acknowledged`() = runTest {
        StoreLinkRefusal.entries.forEach { refusal ->
            val calls = mutableListOf<String>()
            val ingestor = PurchaseIngestor(
                link = { _, _ ->
                    calls += "link"
                    StoreLinkOutcome.Refused(refusal, StringsXml.get(refusal.fallbackMessageRes))
                },
                acknowledge = { calls += "acknowledge"; true },
                isSignedIn = { true },
                strings = StringsXml,
                refusals = PurchaseRefusalMemory { 0L },
            )

            val outcome = ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)

            assertTrue("$refusal", outcome is IngestOutcome.Rejected)
            assertEquals(
                "$refusal must not be acknowledged",
                listOf("link"),
                calls,
            )
        }
    }

    /**
     * A PENDING purchase is neither linked nor acknowledged. Acknowledging one
     * is a DEVELOPER_ERROR at Google's end and a claim we delivered something
     * that has not been paid for at ours.
     */
    @Test
    fun `a pending purchase is neither linked nor acknowledged`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; accepted },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(
            purchased().copy(state = IngestablePurchase.State.PENDING),
            StoreLinkOrigin.AUTO,
        )

        assertTrue(outcome is IngestOutcome.Pending)
        assertTrue("a pending purchase must reach neither side", calls.isEmpty())
        assertTrue((outcome as IngestOutcome.Pending).message.length > 40)
    }

    /**
     * No session: the purchase is real and must survive until there is an
     * account to bind it to. No request is made (so no rate-limit spend) and
     * nothing is acknowledged (so Play keeps re-presenting it).
     */
    @Test
    fun `a purchase made while signed out is kept, not acknowledged`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; accepted },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { false },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)

        assertEquals(StoreLinkRefusal.NEEDS_SIGN_IN, (outcome as IngestOutcome.Rejected).refusal)
        assertTrue("no session means no request at all", calls.isEmpty())
    }

    /** Already acknowledged at Play: accept, and do not spend a second round trip. */
    @Test
    fun `an already-acknowledged purchase is linked but not re-acknowledged`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; accepted },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(isAcknowledged = true), StoreLinkOrigin.AUTO)

        assertTrue((outcome as IngestOutcome.Accepted).acknowledged)
        assertEquals(listOf("link"), calls)
    }

    /**
     * A failed acknowledgement is reported but is NOT a customer-facing
     * failure: the entitlement is already durable and the server acknowledges
     * independently from /link and from the RTDN sink.
     */
    @Test
    fun `a failed acknowledgement still counts as accepted`() = runTest {
        val ingestor = PurchaseIngestor(
            link = { _, _ -> accepted },
            acknowledge = { false },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(), StoreLinkOrigin.AUTO) as IngestOutcome.Accepted

        assertEquals("OPERATIVE", outcome.plan)
        assertFalse(outcome.acknowledged)
    }

    // ── Terminal refusals must not be retried into a rate-limited endpoint ──

    @Test
    fun `a 409 is asked once and then suppressed for the session`() = runTest {
        var linkCalls = 0
        val ingestor = PurchaseIngestor(
            link = { _, _ ->
                linkCalls++
                StoreLinkOutcome.Refused(
                    StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT,
                    "already linked",
                )
            },
            acknowledge = { true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        // Three sweeps — a launch and two resumes.
        repeat(3) { ingestor.ingest(purchased(), StoreLinkOrigin.AUTO) }
        assertEquals("the endpoint allows 20/60s; do not walk into it", 1, linkCalls)

        // Restore Purchases is an explicit ask and clears the suppression.
        ingestor.forgetRefusals()
        ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)
        assertEquals(2, linkCalls)
    }

    @Test
    fun `a suppressed purchase reports Skipped rather than a fake refusal`() = runTest {
        val ingestor = PurchaseIngestor(
            link = { _, _ ->
                StoreLinkOutcome.Refused(StoreLinkRefusal.PRODUCT_UNMAPPED, "unmapped")
            },
            acknowledge = { true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )
        ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)
        assertEquals(IngestOutcome.Skipped, ingestor.ingest(purchased(), StoreLinkOrigin.AUTO))
    }

    // ── F-967-02: an unbound purchase is presented only from Restore ────────

    @Test
    fun `an unbound purchase on an automatic path reaches neither the server nor Google`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; accepted },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(isBound = false), StoreLinkOrigin.AUTO)

        assertEquals(IngestOutcome.NeedsRestore, outcome)
        assertTrue("an unbound purchase must not be presented automatically", calls.isEmpty())
    }

    @Test
    fun `Restore presents an unbound purchase and says so, a bound one goes out as auto`() = runTest {
        val origins = mutableListOf<StoreLinkOrigin>()
        val ingestor = PurchaseIngestor(
            link = { _, origin -> origins += origin; accepted },
            acknowledge = { true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val restored = ingestor.ingest(purchased(token = "tok-u", isBound = false), StoreLinkOrigin.RESTORE)
        val auto = ingestor.ingest(purchased(token = "tok-b"), StoreLinkOrigin.AUTO)

        assertTrue(restored is IngestOutcome.Accepted)
        assertTrue(auto is IngestOutcome.Accepted)
        assertEquals(listOf(StoreLinkOrigin.RESTORE, StoreLinkOrigin.AUTO), origins)
    }

    @Test
    fun `a needsRestore answer is neither accepted nor acknowledged`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ ->
                calls += "link"
                StoreLinkOutcome.Accepted(GooglePlayLinkResponse(linked = false, needsRestore = true))
            },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        assertEquals(IngestOutcome.NeedsRestore, ingestor.ingest(purchased(), StoreLinkOrigin.AUTO))
        assertEquals(listOf("link"), calls)
    }

    // ── F-967-05: a 200 that grants nothing is not a purchase ───────────────

    private fun answering(entitled: Boolean?) = StoreLinkOutcome.Accepted(
        GooglePlayLinkResponse(linked = true, plan = "OPERATIVE", state = "ACTIVE", entitled = entitled),
    )

    @Test
    fun `entitled false is not accepted and never acknowledged`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; answering(entitled = false) },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        assertEquals(IngestOutcome.NotEntitled, ingestor.ingest(purchased(), StoreLinkOrigin.AUTO))
        assertEquals("Google must not be told we delivered nothing", listOf("link"), calls)
    }

    @Test
    fun `entitled true is accepted and acknowledged after the link`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; answering(entitled = true) },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)

        assertTrue((outcome as IngestOutcome.Accepted).acknowledged)
        assertEquals(listOf("link", "acknowledge"), calls)
    }

    @Test
    fun `a server that sends no entitled field keeps the old reading, 200 means granted`() = runTest {
        val calls = mutableListOf<String>()
        val ingestor = PurchaseIngestor(
            link = { _, _ -> calls += "link"; answering(entitled = null) },
            acknowledge = { calls += "acknowledge"; true },
            isSignedIn = { true },
            strings = StringsXml,
            refusals = PurchaseRefusalMemory { 0L },
        )

        val outcome = ingestor.ingest(purchased(), StoreLinkOrigin.AUTO)

        assertEquals("OPERATIVE", (outcome as IngestOutcome.Accepted).plan)
        assertEquals(listOf("link", "acknowledge"), calls)
    }

    // ── Copy ────────────────────────────────────────────────────────────────

    /**
     * Prose names the plan ("Operative"); the slug ("OPERATIVE") belongs to the
     * plan chip alone (canonical vocabulary, A2-031). This used to assert the
     * slug was shouted into the sentence.
     */
    @Test
    fun `the success message names the plan, never its slug or a shouted placeholder`() {
        val ingestor = PurchaseIngestor(
            link = { _, _ -> accepted },
            acknowledge = { true },
            isSignedIn = { true },
            strings = StringsXml,
        )
        assertEquals(
            "Thank you — Operative is now active on this Birdo account.",
            ingestor.purchasedMessage("operative"),
        )
        val sovereign = ingestor.purchasedMessage("SOVEREIGN")
        assertTrue(sovereign.contains("Sovereign"))
        assertFalse(sovereign.contains("SOVEREIGN"))
        val blank = ingestor.purchasedMessage("")
        assertTrue(blank.contains("your new plan"))
        assertFalse("the placeholder must not be uppercased", blank.contains("YOUR NEW PLAN"))
    }
}
