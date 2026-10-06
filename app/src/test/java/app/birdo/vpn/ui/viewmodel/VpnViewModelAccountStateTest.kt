package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.PortForward
import app.birdo.vpn.data.model.RedeemVoucherResponse
import app.birdo.vpn.data.model.SubscriptionStatus
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.data.repository.FailureReason
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.service.VpnState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What VpnViewModel keeps per ACCOUNT, and when it may talk to the backend.
 *
 * The main dispatcher runs on its own scheduler, advanced by hand: the
 * ViewModel's init launches collectors that never complete (the session
 * state, Private DNS, the quota notice), and the stats loop runs while
 * Connected; a scheduler the test does not drive leaves them all parked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnViewModelAccountStateTest {

    private val scheduler = TestCoroutineScheduler()
    private lateinit var vpnManager: VpnManager
    private lateinit var repository: BirdoRepository
    private lateinit var prefs: AppPreferences
    private lateinit var tokenManager: TokenManager

    /** The VM reads its copy through the application context: the shipped strings.xml here. */
    private val appContext: android.content.Context = mockk(relaxed = true) {
        every { getString(any()) } answers { app.birdo.vpn.testing.StringsXml.get(firstArg()) }
    }

    private val paidServers = listOf(
        VpnServer(id = "de-1", name = "Frankfurt 1", country = "Germany", countryCode = "DE", accessible = true),
        VpnServer(id = "us-1", name = "New York 1", country = "USA", countryCode = "US", minPlan = "SOVEREIGN", accessible = false),
    )

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        vpnManager = mockk(relaxed = true)
        every { vpnManager.state } returns MutableStateFlow(VpnState.Disconnected)
        every { vpnManager.connectedServer } returns MutableStateFlow(null)
        every { vpnManager.connectedServerId } returns MutableStateFlow(null)
        every { vpnManager.quotaGrace } returns MutableStateFlow(null)
        every { vpnManager.stealthNotice } returns MutableStateFlow(null)
        every { vpnManager.connectedSince } returns MutableStateFlow(0L)
        every { vpnManager.isVpnPermissionGranted() } returns true
        coEvery { vpnManager.connect(any(), any()) } returns ApiResult.Success(ConnectResponse(success = true))
        coEvery { vpnManager.quickConnect() } returns ApiResult.Success(ConnectResponse(success = true))
        repository = mockk(relaxed = true)
        every { repository.cachedSubscriptionOrNull() } returns null
        coEvery { repository.getServers(any()) } returns ApiResult.Success(paidServers)
        coEvery { repository.getSubscription(any()) } returns ApiResult.Success(SubscriptionStatus(plan = "OPERATIVE"))
        prefs = mockk(relaxed = true)
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { prefs.autoConnect } returns false
        every { prefs.favoriteServers } returns emptySet()
        every { prefs.multiHopEnabled } returns false
        every { prefs.multiHopEntryNodeId } returns null
        every { prefs.multiHopExitNodeId } returns null
        tokenManager = mockk(relaxed = true)
        every { tokenManager.isLoggedIn() } returns true
        mockkObject(BirdoVpnService.Companion)
        every { BirdoVpnService.killSwitchActive } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun viewModel(): VpnViewModel =
        VpnViewModel(vpnManager, repository, prefs, tokenManager, appContext).also { scheduler.runCurrent() }

    // ── A2-003 ───────────────────────────────────────────────────

    @Test
    fun `signing out forgets the previous account's servers, selection, plan and forwards`() {
        val vm = viewModel()
        vm.loadServers()
        coEvery { repository.getPortForwards() } returns ApiResult.Success(
            listOf(PortForward(id = "pf-1", externalPort = 40000, internalPort = 8080, protocol = "tcp")),
        )
        vm.loadPortForwards()
        scheduler.runCurrent()
        assertTrue(vm.uiState.value.servers.isNotEmpty())
        assertTrue(vm.uiState.value.selectedServer != null)

        vm.resetForSignOut()

        val state = vm.uiState.value
        assertTrue(state.servers.isEmpty())
        assertNull(state.selectedServer)
        assertNull(state.subscription)
        assertTrue(state.portForwards.isEmpty())
        assertNull(state.connectError)
        assertNull(state.serversError)
        assertNull(state.portForwardError)
    }

    @Test
    fun `a plan change reloads the servers as well as the plan`() {
        val vm = viewModel()

        vm.onEntitlementChanged()
        scheduler.runCurrent()

        coVerify { repository.getSubscription(true) }
        coVerify { repository.getServers(true) }
    }

    @Test
    fun `a redeemed voucher reloads the servers it unlocks`() {
        coEvery { repository.redeemVoucher(any()) } returns ApiResult.Success(
            RedeemVoucherResponse(ok = true, plan = "SOVEREIGN", durationDays = 30),
        )
        val vm = viewModel()
        var result: VoucherResult? = null

        vm.redeemVoucher("BIRD-AAAA-BBBB-CCCC") { result = it }
        scheduler.runCurrent()

        assertTrue(result is VoucherResult.Redeemed)
        coVerify { repository.getServers(true) }
    }

    @Test
    fun `a voucher that failed for a reason other than the code says why`() {
        coEvery { repository.redeemVoucher(any()) } returns
            ApiResult.Error("Your session has expired. Sign in again.", 401, FailureReason.SESSION_EXPIRED)
        val vm = viewModel()
        var result: VoucherResult? = null

        vm.redeemVoucher("BIRD-AAAA-BBBB-CCCC") { result = it }
        scheduler.runCurrent()

        assertEquals(VoucherResult.Failed("Your session has expired. Sign in again."), result)
    }

    // ── A2-028 ───────────────────────────────────────────────────

    @Test
    fun `nothing is dialled or fetched before the current consent is accepted`() {
        every { prefs.hasAcceptedCurrentConsent } returns false
        every { prefs.autoConnect } returns true
        every { prefs.lastServerId } returns "de-1"
        val vm = viewModel()
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()

        coVerify(exactly = 0) { vpnManager.connect(any()) }
        coVerify(exactly = 0) { vpnManager.quickConnect() }
        coVerify(exactly = 0) { repository.getSubscription(any()) }

        vm.onConsentAccepted()
        scheduler.advanceTimeBy(5_000)
        scheduler.runCurrent()

        coVerify { repository.getSubscription(any()) }
        coVerify { vpnManager.connect("de-1") }
    }

    // ── A2-018 ───────────────────────────────────────────────────

    @Test
    fun `a failed plan fetch is reported instead of spinning forever`() {
        coEvery { repository.getSubscription(any()) } returns
            ApiResult.Error("No internet connection.", 0, FailureReason.OFFLINE)
        val vm = viewModel()

        vm.fetchSubscription(forceRefresh = true)
        scheduler.runCurrent()

        assertEquals("No internet connection.", vm.uiState.value.subscriptionError)
        assertFalse(vm.uiState.value.isLoadingSubscription)

        coEvery { repository.getSubscription(any()) } returns ApiResult.Success(SubscriptionStatus())
        vm.fetchSubscription(forceRefresh = true)
        scheduler.runCurrent()

        assertNull(vm.uiState.value.subscriptionError)
    }

    // ── A2-038 ───────────────────────────────────────────────────

    @Test
    fun `a failed rule deletion is surfaced, not swallowed`() {
        coEvery { repository.deletePortForward(any()) } returns
            ApiResult.Error("Couldn't delete the rule. Please try again.", 500, FailureReason.SERVER_UNAVAILABLE)
        val vm = viewModel()

        vm.deletePortForward("pf-1")
        scheduler.runCurrent()

        assertEquals("Couldn't delete the rule. Please try again.", vm.uiState.value.portForwardError)
    }
}
