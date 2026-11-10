package app.birdo.vpn.billing

import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.testing.StringsXml
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * A-M74 (audit PLAY-BILLING-MANAGER-AUDIT-2026-11-10, finding 14): `reconcile`
 * ran OUTSIDE `gate` from four callers, so two sweeps could present the same
 * purchase token to `/link` at the same moment.
 *
 * A fake [BillingClient] answers `queryPurchasesAsync` immediately with two
 * purchases and the fake `/link` takes 100 ms of virtual time, so any two sweeps
 * that are allowed to overlap are both inside `/link` for the same token.
 */
class PlayBillingManagerReconcileTest {

    private val ok: BillingResult = BillingResult.newBuilder()
        .setResponseCode(BillingClient.BillingResponseCode.OK)
        .build()

    private val accepted = StoreLinkOutcome.Accepted(
        GooglePlayLinkResponse(linked = true, plan = "OPERATIVE", state = "ACTIVE"),
    )

    private fun purchase(token: String): Purchase = mockk {
        every { purchaseToken } returns token
        every { products } returns listOf(BirdoPlayProduct.OPERATIVE.productId)
        every { purchaseState } returns Purchase.PurchaseState.PURCHASED
        every { isAcknowledged } returns true
    }

    /** Counts, per token, how many `/link` calls are in flight at once. */
    private class LinkProbe {
        val inFlight = HashMap<String, AtomicInteger>()
        val maxInFlight = HashMap<String, Int>()
        val calls = HashMap<String, Int>()
        var sweeps = 0

        @Synchronized
        fun enter(token: String) {
            calls[token] = (calls[token] ?: 0) + 1
            val now = inFlight.getOrPut(token) { AtomicInteger() }.incrementAndGet()
            maxInFlight[token] = maxOf(maxInFlight[token] ?: 0, now)
        }

        @Synchronized
        fun leave(token: String) {
            inFlight.getValue(token).decrementAndGet()
        }
    }

    private class Rig(val manager: PlayBillingManager, val probe: LinkProbe)

    private fun TestScope.rig(): Rig {
        val probe = LinkProbe()
        val client = mockk<BillingClient>(relaxed = true) {
            every { isReady } returns true
            every { queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
                probe.sweeps++
                secondArg<PurchasesResponseListener>()
                    .onQueryPurchasesResponse(ok, listOf(purchase("tok-a"), purchase("tok-b")))
            }
        }
        val repository = mockk<BirdoRepository> {
            coEvery { linkGooglePurchase(any()) } coAnswers {
                val token = firstArg<String>()
                probe.enter(token)
                delay(100)
                probe.leave(token)
                accepted
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
        return Rig(manager, probe)
    }

    @Test
    fun `two overlapping sign-in sweeps never have the same token in flight twice`() = runTest {
        val rig = rig()

        rig.manager.onSignedIn()
        rig.manager.onSignedIn()
        advanceUntilIdle()

        assertEquals(2, rig.probe.sweeps)
        listOf("tok-a", "tok-b").forEach {
            assertEquals("$it must be presented to /link by one sweep at a time", 1, rig.probe.maxInFlight[it])
        }
    }

    @Test
    fun `restore, sign-in and already-owned sweeps are serialised`() = runTest {
        val rig = rig()

        rig.manager.onSignedIn()
        rig.manager.onPurchasesUpdated(
            BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)
                .build(),
            null,
        )
        val restore = async { rig.manager.restorePurchases() }
        advanceUntilIdle()

        assertTrue(restore.await().isSuccess)
        assertEquals(3, rig.probe.sweeps)
        listOf("tok-a", "tok-b").forEach {
            assertEquals(1, rig.probe.maxInFlight[it])
            assertEquals("one /link per token per sweep", 3, rig.probe.calls[it])
        }
    }

    @Test
    fun `start() reconciles under the gate it already holds and does not deadlock`() = runTest {
        val rig = rig()

        rig.manager.start()
        rig.manager.onSignedIn()
        advanceUntilIdle()

        // The catalogue query never answers in this fake, so start() waits out
        // its deadline, then its own sweep runs; the sign-in sweep queues behind
        // the gate. Both finish: no sweep is lost and none overlaps.
        assertEquals(2, rig.probe.sweeps)
        listOf("tok-a", "tok-b").forEach { assertEquals(1, rig.probe.maxInFlight[it]) }
    }
}
