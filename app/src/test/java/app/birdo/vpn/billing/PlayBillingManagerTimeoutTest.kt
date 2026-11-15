package app.birdo.vpn.billing

import app.birdo.vpn.R
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.testing.StringsXml
import com.android.billingclient.api.AccountIdentifiers
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A-M74 item 16 (audit PLAY-BILLING-MANAGER-AUDIT-2026-11-10): since the sweep
 * runs under `gate`, a Google Play call that never calls back held `gate`
 * forever, blocking every later sweep, the storefront refresh and Restore.
 *
 * Every fake below hangs one Play call. All time is virtual
 * (`advanceUntilIdle` jumps over the deadline), so these run in milliseconds.
 */
class PlayBillingManagerTimeoutTest {

    private val ok: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    private val accepted = StoreLinkOutcome.Accepted(
        GooglePlayLinkResponse(linked = true, plan = "OPERATIVE", state = "ACTIVE"),
    )

    private fun purchase(token: String, acknowledged: Boolean = true): Purchase {
        // Bound, as a purchase this app launched is (F-967-02 is not this suite's subject).
        val ids = mockk<AccountIdentifiers> { every { obfuscatedAccountId } returns "acct-$token" }
        return mockk {
            every { purchaseToken } returns token
            every { products } returns listOf(BirdoPlayProduct.OPERATIVE.productId)
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { isAcknowledged } returns acknowledged
            every { accountIdentifiers } returns ids
        }
    }

    private class Rig(val manager: PlayBillingManager, val queries: IntArray, val links: IntArray)

    /**
     * @param hangFirstQuery the first `queryPurchasesAsync` never answers; later ones do.
     * @param ready false makes `connect()` call `startConnection`, which never answers.
     * @param acknowledged false makes the sweep call `acknowledgePurchase`, which never answers.
     */
    private fun TestScope.rig(
        hangFirstQuery: Boolean = false,
        ready: Boolean = true,
        acknowledged: Boolean = true,
    ): Rig {
        val queries = IntArray(1)
        val links = IntArray(1)
        val client = mockk<BillingClient>(relaxed = true) {
            every { isReady } returns ready
            every { queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
                queries[0]++
                if (!(hangFirstQuery && queries[0] == 1)) {
                    secondArg<PurchasesResponseListener>()
                        .onQueryPurchasesResponse(ok, listOf(purchase("tok-a", acknowledged)))
                }
            }
        }
        val repository = mockk<BirdoRepository> {
            coEvery { linkGooglePurchase(any(), any()) } coAnswers { links[0]++; accepted }
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
        return Rig(manager, queries, links)
    }

    @Test
    fun `a hung purchases query releases the gate and the next sweep runs`() = runTest {
        val rig = rig(hangFirstQuery = true)

        rig.manager.onSignedIn()
        advanceUntilIdle()
        assertEquals(1, rig.queries[0])
        assertEquals("the hung sweep linked nothing", 0, rig.links[0])

        rig.manager.onSignedIn()
        advanceUntilIdle()
        assertEquals("the second sweep was not blocked by the first", 2, rig.queries[0])
        assertEquals(1, rig.links[0])
    }

    @Test
    fun `restore reports a clear failure instead of spinning when Play never answers`() = runTest {
        val rig = rig(hangFirstQuery = true)

        val restore = async { rig.manager.restorePurchases() }
        advanceUntilIdle()

        assertTrue("restore must finish", restore.isCompleted)
        val outcome = restore.await()
        assertTrue(outcome is StoreRestoreOutcome.Failed)
        assertEquals(StringsXml.get(R.string.billing_restore_unreachable), (outcome as StoreRestoreOutcome.Failed).text)
        assertFalse(rig.manager.state.value.isRestoring)

        // and Restore works again afterwards
        val again = async { rig.manager.restorePurchases() }
        advanceUntilIdle()
        assertTrue(again.await().isSuccess)
    }

    @Test
    fun `a hung startConnection fails the sweep and releases the gate`() = runTest {
        val rig = rig(ready = false)

        val restore = async { rig.manager.restorePurchases() }
        advanceUntilIdle()

        assertTrue("restore must finish", restore.isCompleted)
        assertTrue(restore.await() is StoreRestoreOutcome.Failed)
        assertFalse(rig.manager.state.value.isRestoring)
        assertEquals(0, rig.queries[0])
    }

    @Test
    fun `a hung acknowledge does not hold the gate and the purchase still counts as restored`() = runTest {
        val rig = rig(acknowledged = false)

        val restore = async { rig.manager.restorePurchases() }
        advanceUntilIdle()

        assertTrue("restore must finish", restore.isCompleted)
        assertTrue(restore.await().isSuccess)
        assertEquals(1, rig.links[0])
    }
}
