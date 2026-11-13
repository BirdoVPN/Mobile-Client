package app.birdo.vpn.billing

import app.birdo.vpn.R
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.testing.StringsXml
import com.android.billingclient.api.AccountIdentifiers
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-967 client hardening (docs/audits/NEVER-AUDITED-2026-11-13, F-967-02, -05,
 * -09, -10), driven through the real [PlayBillingManager]: its listener, its
 * sweeps, Restore and sign-out. The Play side is a mocked [BillingClient] and
 * the server is a mocked [BirdoRepository], as in PlayBillingManagerReconcileTest;
 * nothing below re-implements the logic it checks.
 */
class PlayBillingClientHardeningTest {

    private val ok: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    private val alreadyOwned: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
        .build()

    private fun answer(
        entitled: Boolean? = null,
        needsRestore: Boolean? = null,
        duplicate: DuplicateBillingNotice? = null,
    ) = StoreLinkOutcome.Accepted(
        GooglePlayLinkResponse(
            linked = needsRestore != true,
            plan = "OPERATIVE",
            state = "ACTIVE",
            duplicateBilling = duplicate,
            entitled = entitled,
            needsRestore = needsRestore,
        ),
    )

    private val otherAccount = StoreLinkOutcome.Refused(
        StoreLinkRefusal.ALREADY_LINKED_TO_ANOTHER_ACCOUNT,
        "linked to another account",
    )

    private val unmapped = StoreLinkOutcome.Refused(StoreLinkRefusal.PRODUCT_UNMAPPED, "not ours")

    /** [bound] = Play echoes an obfuscatedAccountId, as for every purchase this app launches. */
    private fun purchase(
        token: String,
        productId: String = BirdoPlayProduct.OPERATIVE.productId,
        bound: Boolean = true,
        acknowledged: Boolean = true,
    ): Purchase {
        val ids: AccountIdentifiers? =
            if (bound) mockk<AccountIdentifiers> { every { obfuscatedAccountId } returns "acct-$token" } else null
        return mockk {
            every { purchaseToken } returns token
            every { products } returns listOf(productId)
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { isAcknowledged } returns acknowledged
            every { accountIdentifiers } returns ids
        }
    }

    private class Rig(
        val manager: PlayBillingManager,
        val client: BillingClient,
        /** Every `/link` call, in order: (token, origin). */
        val links: MutableList<Pair<String, StoreLinkOrigin>>,
    )

    /**
     * @param owned what `queryPurchasesAsync` answers on every sweep.
     * @param server what `/link` answers for a token.
     * @param linkMs virtual time one `/link` call takes.
     */
    private fun TestScope.rig(
        owned: List<Purchase>,
        server: (String) -> StoreLinkOutcome = { answer() },
        linkMs: Long = 0,
    ): Rig {
        val links = mutableListOf<Pair<String, StoreLinkOrigin>>()
        val client = mockk<BillingClient>(relaxed = true) {
            every { isReady } returns true
            every { queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
                secondArg<PurchasesResponseListener>().onQueryPurchasesResponse(ok, owned)
            }
            every { acknowledgePurchase(any(), any()) } answers {
                secondArg<AcknowledgePurchaseResponseListener>().onAcknowledgePurchaseResponse(ok)
            }
        }
        val repository = mockk<BirdoRepository> {
            coEvery { linkGooglePurchase(any(), any()) } coAnswers {
                val token = firstArg<String>()
                links += token to secondArg<StoreLinkOrigin>()
                if (linkMs > 0) delay(linkMs)
                server(token)
            }
        }
        val tokens = mockk<TokenManager> { every { isLoggedIn() } returns true }
        val manager = PlayBillingManager(
            repository = repository,
            tokenManager = tokens,
            strings = StringsXml,
            billingClientFactory = { client },
            dispatcher = StandardTestDispatcher(testScheduler),
            railEnabled = true,
        )
        return Rig(manager, client, links)
    }

    // ── F-967-09: the listener applies the same product filter as the sweep ──

    @Test
    fun `a foreign product from the purchase listener is ignored quietly`() = runTest {
        val rig = rig(owned = emptyList(), server = { unmapped })

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-foreign", productId = "someone_elses_sku")))
        advanceUntilIdle()

        assertTrue("a product Birdo does not sell must never reach /link", rig.links.isEmpty())
        assertNull("and must raise no notice", rig.manager.state.value.notice)
        assertFalse(rig.manager.state.value.ownsBirdoSubscription)
        assertNull(rig.manager.state.value.purchasingProductId)
    }

    @Test
    fun `the listener still presents the Birdo purchase next to a foreign one`() = runTest {
        val rig = rig(owned = emptyList())

        rig.manager.onPurchasesUpdated(
            ok,
            mutableListOf(purchase("tok-foreign", productId = "someone_elses_sku"), purchase("tok-birdo")),
        )
        advanceUntilIdle()

        assertEquals(listOf("tok-birdo" to StoreLinkOrigin.AUTO), rig.links)
        assertEquals(StoreNotice.Kind.SUCCESS, rig.manager.state.value.notice?.kind)
    }

    // ── F-967-10: sign-out forgets the previous account ─────────────────────

    @Test
    fun `sign-out forgets a refusal, so the next account's sign-in presents the purchase again`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-a")), server = { otherAccount })

        rig.manager.onSignedIn()
        advanceUntilIdle()
        rig.manager.onSignedIn()
        advanceUntilIdle()
        assertEquals("a terminal refusal is suppressed for the session", 1, rig.links.size)

        rig.manager.onSignedOut()
        rig.manager.onSignedIn()
        advanceUntilIdle()

        assertEquals("the next account must be asked afresh", 2, rig.links.size)
    }

    @Test
    fun `sign-out clears the purchase list, the ownership flag and the account's banners`() = runTest {
        val duplicate = DuplicateBillingNotice(otherSource = "POLAR", message = "paying twice")
        val rig = rig(owned = listOf(purchase("tok-a")), server = { answer(duplicate = duplicate) })
        val sovereign = PurchasableOffer(
            product = BirdoPlayProduct.SOVEREIGN,
            period = BirdoBillingPeriod.MONTHLY,
            formattedPrice = "1",
            productDetails = mockk<ProductDetails>(),
            offerToken = "offer",
        )

        rig.manager.onSignedIn()
        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-a")))
        advanceUntilIdle()
        rig.manager.state.value.let {
            assertTrue(it.ownsBirdoSubscription)
            assertNotNull(it.notice)
            assertNotNull(it.duplicateBilling)
        }
        assertEquals(
            "the cross-grade source is the previous sweep's purchase",
            BirdoPlayProduct.OPERATIVE.productId,
            rig.manager.oldSubscriptionProductId(sovereign),
        )

        rig.manager.onSignedOut()
        advanceUntilIdle()

        rig.manager.state.value.let {
            assertFalse(it.ownsBirdoSubscription)
            assertNull(it.notice)
            assertNull(it.duplicateBilling)
        }
        assertNull("knownPurchases survived sign-out", rig.manager.oldSubscriptionProductId(sovereign))
    }

    /**
     * Thread safety under the gate. A sweep for the old account is inside
     * `/link` when sign-out lands. A clear that ran beside it would be undone
     * a moment later, when that sweep records its refusal, and the refusal
     * would silence the new account's sweep for the rest of the session.
     */
    @Test
    fun `a sweep in flight at sign-out cannot leave its refusal behind for the next account`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-a")), server = { otherAccount }, linkMs = 100)

        rig.manager.onSignedIn()
        advanceTimeBy(50)
        runCurrent()
        assertEquals("the old account's sweep is inside /link", 1, rig.links.size)

        rig.manager.onSignedOut()
        advanceUntilIdle()
        rig.manager.onSignedIn()
        advanceUntilIdle()

        assertEquals(2, rig.links.size)
    }

    /**
     * #615 review, M1: the same race through the PURCHASE LISTENER, whose link
     * did not take the gate. The old account's 409 landed after the clear: its
     * notice stayed up and its refusal silenced the next account's link.
     */
    @Test
    fun `sign-out during an in-flight listener link leaves no refusal or notice behind`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-a")), server = { otherAccount }, linkMs = 100)

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-a")))
        advanceTimeBy(50)
        runCurrent()
        assertEquals("the listener's link is in flight", 1, rig.links.size)

        rig.manager.onSignedOut()
        advanceUntilIdle()
        assertNull("the old account's refusal notice outlived sign-out", rig.manager.state.value.notice)

        rig.manager.onSignedIn()
        advanceUntilIdle()
        assertEquals("the old account's refusal silenced the next account", 2, rig.links.size)
    }

    @Test
    fun `sign-out during an in-flight listener link drops the old account's banner and success`() = runTest {
        val duplicate = DuplicateBillingNotice(otherSource = "POLAR", message = "paying twice")
        val rig = rig(owned = emptyList(), server = { answer(duplicate = duplicate) }, linkMs = 100)

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-a")))
        advanceTimeBy(50)
        runCurrent()
        assertEquals("the listener's link is in flight", 1, rig.links.size)

        rig.manager.onSignedOut()
        advanceUntilIdle()

        rig.manager.state.value.let {
            assertNull("the old account's duplicate-billing banner outlived sign-out", it.duplicateBilling)
            assertNull("the old account's success notice outlived sign-out", it.notice)
            assertFalse(it.ownsBirdoSubscription)
            assertNull(it.purchasingProductId)
        }
    }

    // ── F-967-05: entitled = false is not a purchase ─────────────────────────

    @Test
    fun `entitled false is not announced, not acknowledged, and opens no plan gate`() = runTest {
        val rig = rig(owned = emptyList(), server = { answer(entitled = false) })
        val changed = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.manager.entitlementChanged.collect { changed += it }
        }

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-a", acknowledged = false)))
        advanceUntilIdle()

        assertEquals(1, rig.links.size)
        verify(exactly = 0) { rig.client.acknowledgePurchase(any(), any()) }
        val notice = rig.manager.state.value.notice
        assertEquals(StoreNotice.Kind.INFO, notice?.kind)
        assertEquals(StringsXml.get(R.string.billing_nothing_to_restore), notice?.text)
        assertTrue("nothing was granted, so no plan gate may reopen", changed.isEmpty())
    }

    @Test
    fun `entitled true is announced and acknowledged`() = runTest {
        val rig = rig(owned = emptyList(), server = { answer(entitled = true) })

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-a", acknowledged = false)))
        advanceUntilIdle()

        verify(exactly = 1) { rig.client.acknowledgePurchase(any(), any()) }
        assertEquals(StoreNotice.Kind.SUCCESS, rig.manager.state.value.notice?.kind)
    }

    @Test
    fun `a server that sends no entitled field keeps today's behaviour`() = runTest {
        val rig = rig(owned = emptyList(), server = { answer(entitled = null) })
        val changed = mutableListOf<Unit>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            rig.manager.entitlementChanged.collect { changed += it }
        }

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-a", acknowledged = false)))
        advanceUntilIdle()

        verify(exactly = 1) { rig.client.acknowledgePurchase(any(), any()) }
        assertEquals(
            StringsXml.get(R.string.billing_purchased, StringsXml.get(R.string.plan_name_operative)),
            rig.manager.state.value.notice?.text,
        )
        assertEquals(1, changed.size)
    }

    @Test
    fun `Restore of a purchase that grants nothing answers no active subscription`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-a", acknowledged = false)), server = { answer(entitled = false) })

        val restore = async { rig.manager.restorePurchases() }
        advanceUntilIdle()

        assertEquals(StoreRestoreOutcome.NothingToRestore, restore.await())
        verify(exactly = 0) { rig.client.acknowledgePurchase(any(), any()) }
        assertEquals(StoreNotice.Kind.INFO, rig.manager.state.value.notice?.kind)
        assertEquals(StringsXml.get(R.string.billing_nothing_to_restore), rig.manager.state.value.notice?.text)
    }

    // ── F-967-02: Restore tap only, for a purchase that names no account ─────

    @Test
    fun `an unbound purchase on resume makes no link call`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-u", bound = false)))

        rig.manager.onAppResumed()
        advanceUntilIdle()

        assertTrue("an unbound purchase must not be linked silently", rig.links.isEmpty())
        assertNull("and leaves no error behind", rig.manager.state.value.notice)
        // Play still owns it, so the purchase gate must still know.
        assertTrue(rig.manager.state.value.ownsBirdoSubscription)
    }

    @Test
    fun `an unbound purchase is not linked on sign-in, at launch or from the listener either`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-u", bound = false)))

        rig.manager.onSignedIn()
        rig.manager.start()
        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-u", bound = false)))
        advanceUntilIdle()

        assertTrue(rig.links.isEmpty())
        assertNull(rig.manager.state.value.notice)
    }

    @Test
    fun `Restore links the same unbound purchase and says it is a restore`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-u", bound = false)))

        rig.manager.onAppResumed()
        advanceUntilIdle()
        assertTrue(rig.links.isEmpty())

        val restore = async { rig.manager.restorePurchases() }
        advanceUntilIdle()

        assertTrue(restore.await().isSuccess)
        assertEquals(listOf("tok-u" to StoreLinkOrigin.RESTORE), rig.links)
    }

    @Test
    fun `a bound purchase still auto-links on resume and on sign-in`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-b")))

        rig.manager.onAppResumed()
        advanceUntilIdle()
        rig.manager.onSignedIn()
        advanceUntilIdle()

        assertEquals(
            listOf("tok-b" to StoreLinkOrigin.AUTO, "tok-b" to StoreLinkOrigin.AUTO),
            rig.links,
        )
    }

    @Test
    fun `already-owned with only an unbound purchase points at Restore instead of linking`() = runTest {
        val rig = rig(owned = listOf(purchase("tok-u", bound = false)))

        rig.manager.onPurchasesUpdated(alreadyOwned, null)
        advanceUntilIdle()

        assertTrue(rig.links.isEmpty())
        assertEquals(StoreNotice.Kind.INFO, rig.manager.state.value.notice?.kind)
        assertEquals(StringsXml.get(R.string.billing_already_owned_unseen), rig.manager.state.value.notice?.text)
    }

    @Test
    fun `a needsRestore answer shows no success and no error, and is not acknowledged`() = runTest {
        val rig = rig(owned = emptyList(), server = { answer(needsRestore = true) })

        rig.manager.onPurchasesUpdated(ok, mutableListOf(purchase("tok-b", acknowledged = false)))
        advanceUntilIdle()

        assertEquals(1, rig.links.size)
        assertNull(rig.manager.state.value.notice)
        verify(exactly = 0) { rig.client.acknowledgePurchase(any(), any()) }
    }
}
