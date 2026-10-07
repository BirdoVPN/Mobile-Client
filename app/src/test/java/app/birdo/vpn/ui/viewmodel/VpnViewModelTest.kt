package app.birdo.vpn.ui.viewmodel

import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import app.birdo.vpn.service.BirdoVpnService
import app.birdo.vpn.service.SessionCopy
import app.birdo.vpn.service.VpnManager
import app.birdo.vpn.service.VpnState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VpnViewModelTest {

    private val state = MutableStateFlow<VpnState>(VpnState.Disconnected)
    private val switching = MutableStateFlow(false)
    private val sessionExpired = MutableStateFlow(false)

    private lateinit var vpnManager: VpnManager
    private lateinit var repository: BirdoRepository
    private lateinit var prefs: AppPreferences
    private lateinit var tokenManager: TokenManager

    /** The VM reads its copy through the application context: the shipped strings.xml here. */
    private val appContext: android.content.Context = mockk(relaxed = true) {
        every { getString(any()) } answers { app.birdo.vpn.testing.StringsXml.get(firstArg()) }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vpnManager = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        every { vpnManager.state } returns state
        every { vpnManager.switching } returns switching
        every { vpnManager.sessionExpired } returns sessionExpired
        every { vpnManager.connectedServer } returns MutableStateFlow(null)
        every { vpnManager.connectedServerId } returns MutableStateFlow(null)
        every { vpnManager.quotaGrace } returns MutableStateFlow(null)
        every { vpnManager.stealthNotice } returns MutableStateFlow(null)
        every { vpnManager.connectedSince } returns MutableStateFlow(0L)
        every { vpnManager.activeMultiHopRoute } returns null
        every { vpnManager.isVpnPermissionGranted() } returns true
        every { repository.cachedSubscriptionOrNull() } returns null
        every { tokenManager.isLoggedIn() } returns false
        every { prefs.autoConnect } returns false
        every { prefs.hasAcceptedCurrentConsent } returns false
        every { prefs.multiHopEnabled } returns false
        every { prefs.favoriteServers } returns emptySet()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
        setKillSwitchFlow(false)
        setNotArmedFlow(null)
    }

    private fun setNotArmedFlow(value: String?) {
        val field = BirdoVpnService::class.java.getDeclaredField("_killSwitchNotArmedFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableStateFlow<String?>).value = value
    }

    private fun setKillSwitchFlow(value: Boolean) {
        val field = BirdoVpnService::class.java.getDeclaredField("_killSwitchActiveFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableStateFlow<Boolean>).value = value
    }

    private fun viewModel() = VpnViewModel(vpnManager, repository, prefs, tokenManager, appContext)

    private fun server(id: String, country: String, load: Int, accessible: Boolean = true) = VpnServer(
        id = id, name = id, country = country, countryCode = "XX", city = country,
        hostname = "$id.birdo.app", ipAddress = "1.2.3.4", port = 51820, load = load,
        minPlan = "RECON", accessible = accessible, isPremium = false, isHighSpeed = false,
        isPortForwarding = false, isOnline = true,
    )

    // ── A1-007: the permission grant replays the exact dial ───────────────

    @Test
    fun `granting VPN permission after a Multi-Hop dial connects Multi-Hop, never a single hop`() = runTest {
        every { vpnManager.isVpnPermissionGranted() } returns false
        val vm = viewModel()
        vm.connectMultiHop("de-1", "nl-1")
        assertTrue(vm.uiState.value.needsVpnPermission)

        every { vpnManager.isVpnPermissionGranted() } returns true
        vm.onVpnPermissionGranted()

        coVerify(exactly = 1) { vpnManager.connectMultiHop("de-1", "nl-1") }
        coVerify(exactly = 0) { vpnManager.connect(any()) }
        assertFalse(vm.uiState.value.needsVpnPermission)
    }

    @Test
    fun `a denied permission forgets the dial and says why`() = runTest {
        every { vpnManager.isVpnPermissionGranted() } returns false
        val vm = viewModel()
        vm.connectMultiHop("de-1", "nl-1")

        vm.onVpnPermissionDenied()
        vm.onVpnPermissionGranted()

        coVerify(exactly = 0) { vpnManager.connectMultiHop(any(), any()) }
        assertEquals(SessionCopy.VPN_PERMISSION, vm.uiState.value.connectError)
    }

    // ── A1-008 / P1-parity-001: no second dial beside a recovery ──────────

    @Test
    fun `Connect is a no-op while the supervisor is reconnecting`() = runTest {
        val vm = viewModel()
        state.value = VpnState.Reconnecting(2)

        vm.connect()
        vm.quickConnect()
        vm.connectMultiHop("de-1", "nl-1")

        coVerify(exactly = 0) { vpnManager.connect(any()) }
        coVerify(exactly = 0) { vpnManager.quickConnect() }
        coVerify(exactly = 0) { vpnManager.connectMultiHop(any(), any()) }
    }

    // ── A2-011 / A2-012: one banner, per-surface errors ───────────────────

    @Test
    fun `a connect failure is not copied into a second banner`() = runTest {
        coEvery { repository.getServers(any()) } returns ApiResult.Success(listOf(server("s1", "DE", 10)))
        coEvery { vpnManager.connect(any()) } returns ApiResult.Error("Upgrade required", 426)
        val vm = viewModel()
        vm.loadServers()

        vm.connect()

        coVerify(exactly = 1) { vpnManager.connect("s1") }
        // VpnManager publishes the failure as the session's Error; the screen
        // draws that one banner.
        assertNull(vm.uiState.value.connectError)
    }

    @Test
    fun `each screen keeps its own error`() = runTest {
        coEvery { repository.getPortForwards() } returns ApiResult.Error("Port forwarding unavailable")
        coEvery { repository.deletePortForward(any()) } returns ApiResult.Error("Could not delete")
        coEvery { repository.getServers(any()) } returns ApiResult.Error("Servers unavailable")
        val vm = viewModel()

        vm.loadPortForwards()
        vm.loadServers()
        assertEquals("Port forwarding unavailable", vm.uiState.value.portForwardError)
        assertEquals("Servers unavailable", vm.uiState.value.serversError)
        assertNull(vm.uiState.value.connectError)

        vm.deletePortForward("pf-1")
        assertEquals("Could not delete", vm.uiState.value.portForwardError)

        vm.dismissConnectError()
        assertEquals("Could not delete", vm.uiState.value.portForwardError)
    }

    // ── A1-023: K10 on Android ────────────────────────────────────────────

    @Test
    fun `the pre-selected server is the lowest-load usable one, not the first alphabetically`() = runTest {
        coEvery { repository.getServers(any()) } returns ApiResult.Success(
            listOf(server("a", "Austria", load = 90), server("z", "Zambia", load = 5), server("b", "Belgium", 1, accessible = false)),
        )
        val vm = viewModel()

        vm.loadServers()

        assertEquals("z", vm.uiState.value.selectedServer?.id)
    }

    // ── A1-044, P1-parity-003/-016/-017: the state stream ────────────────

    @Test
    fun `the kill-switch banner follows the block, not only connection changes`() = runTest {
        val vm = viewModel()
        setKillSwitchFlow(true)
        assertTrue(vm.uiState.value.killSwitchActive)
        setKillSwitchFlow(false)
        assertFalse(vm.uiState.value.killSwitchActive)
    }

    @Test
    fun `a switch and a Multi-Hop route reach the screen`() = runTest {
        every { vpnManager.activeMultiHopRoute } returns ("de-1" to "nl-1")
        val vm = viewModel()

        switching.value = true
        state.value = VpnState.Connecting
        assertTrue(vm.uiState.value.switching)
        assertEquals("de-1", vm.uiState.value.liveMultiHopEntryId)

        switching.value = false
        state.value = VpnState.Connected
        assertTrue(vm.uiState.value.multiHopActive)

        state.value = VpnState.Disconnected
        assertNull(vm.uiState.value.liveMultiHopEntryId)
    }

    // ── REVIEW-AND-003: Auto-Connect's fallback ───────────────────────────

    private fun autoConnectReady() {
        every { prefs.autoConnect } returns true
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokenManager.isLoggedIn() } returns true
        every { prefs.lastServerId } returns "srv-1"
        every { prefs.lastKnownPlan } returns null
    }

    @Test
    fun `a Cancel during Auto-Connect is not undone by a fallback quick connect`() = runTest {
        autoConnectReady()
        coEvery { vpnManager.connect("srv-1") } returns ApiResult.Error(VpnManager.SUPERSEDED)
        viewModel()
        testScheduler.advanceTimeBy(2_000)
        testScheduler.runCurrent()
        coVerify(exactly = 1) { vpnManager.connect("srv-1") }
        coVerify(exactly = 0) { vpnManager.quickConnect() }
    }

    @Test
    fun `Auto-Connect falls back only when the saved server is gone or locked`() {
        assertTrue(VpnViewModel.fallsBackToQuickConnect(ApiResult.Error("Server not found", 404)))
        assertTrue(VpnViewModel.fallsBackToQuickConnect(ApiResult.Error("Requires a higher plan", 403)))
        assertFalse(VpnViewModel.fallsBackToQuickConnect(ApiResult.Error(VpnManager.SUPERSEDED)))
        assertFalse("the watchdog", VpnViewModel.fallsBackToQuickConnect(ApiResult.Error(SessionCopy.NO_TUNNEL)))
        assertFalse(VpnViewModel.fallsBackToQuickConnect(ApiResult.Error(SessionCopy.UPDATE_REQUIRED, 426)))
        assertFalse(VpnViewModel.fallsBackToQuickConnect(ApiResult.Error(SessionCopy.SESSION_EXPIRED, 401)))
        assertFalse(VpnViewModel.fallsBackToQuickConnect(ApiResult.Error("timeout", 0)))
    }

    // ── The selection a fresh process starts with ─────────────────────────

    @Test
    fun `a fresh process selects the user's last server, not the best one`() = runTest {
        every { prefs.lastServerId } returns "br-1"
        coEvery { repository.getServers(any()) } returns ApiResult.Success(
            listOf(server("ca-1", "Canada", load = 5), server("br-1", "Brazil", load = 60)),
        )
        val vm = viewModel()
        vm.loadServers()
        // The emulator, 2026-09-30: São Paulo became Toronto after a crash,
        // and the next Always-on boot dialled Toronto as "the last server".
        assertEquals("br-1", vm.uiState.value.selectedServer?.id)
    }

    @Test
    fun `a last server this plan can no longer use falls back to the best one`() = runTest {
        every { prefs.lastServerId } returns "br-1"
        coEvery { repository.getServers(any()) } returns ApiResult.Success(
            listOf(server("ca-1", "Canada", load = 5), server("br-1", "Brazil", load = 60, accessible = false)),
        )
        val vm = viewModel()
        vm.loadServers()
        assertEquals("ca-1", vm.uiState.value.selectedServer?.id)
    }

    // ── Round 7, item 4: a switch the network cut short ───────────────────

    /**
     * VpnManager's offline branch hands the session the switch was moving back
     * to the supervisor: it waits for the network and re-dials
     * prefs.lastServerId. The selection stayed on the tapped server. It must
     * follow the re-dial — here deliberately not the previous LABEL, which a
     * tap while disconnected had moved off the session's server.
     */
    @Test
    fun `a switch cut short while offline puts the selection on the server being re-dialled`() = runTest {
        every { prefs.lastServerId } returns "br-1"
        coEvery { repository.getServers(any()) } returns ApiResult.Success(
            listOf(server("br-1", "Brazil", load = 60), server("ca-1", "Canada", load = 5), server("de-1", "Germany", load = 30)),
        )
        val vm = viewModel()
        vm.loadServers()
        vm.selectServer(vm.uiState.value.servers.first { it.id == "de-1" }) // a relabel: nothing connected
        state.value = VpnState.Connected // e.g. an Always-on boot dialled br-1
        coEvery { vpnManager.connect("ca-1", any()) } coAnswers {
            state.value = VpnState.Reconnecting(attempt = 1, waitingForNetwork = true)
            ApiResult.Error("Connection lost. Reconnecting…")
        }

        vm.selectServer(vm.uiState.value.servers.first { it.id == "ca-1" })

        assertEquals("br-1", vm.uiState.value.selectedServer?.id)
        assertNull("waiting for the network is not a switch error", vm.uiState.value.connectError)
    }

    @Test
    fun `sign-out forgets the account's last server and plan`() {
        viewModel().resetForSignOut()
        io.mockk.verify { prefs.lastServerId = null }
        io.mockk.verify { prefs.lastKnownPlan = null }
    }

    // ── A1-045 / A2-005: sign-out ordering ────────────────────────────────

    @Test
    fun `sign-out wipes the tokens only after the slot is released`() = runTest {
        val released = CompletableDeferred<Unit>()
        coEvery { vpnManager.disconnectForSignOut() } coAnswers { released.await() }
        val vm = viewModel()
        var signedOut = false

        vm.disconnectForSignOut { signedOut = true }
        assertFalse(signedOut)

        released.complete(Unit)
        assertTrue(signedOut)
    }

    // ── P2-2: a kill switch that could not be armed stays on Home ────────

    @Test
    fun `Home keeps saying the kill switch could not be armed while the supervisor reconnects`() = runTest {
        val vm = viewModel()
        state.value = VpnState.Error(SessionCopy.KILL_SWITCH_NOT_ARMED, app.birdo.vpn.service.FailureKind.DIED_AFTER_HANDSHAKE)
        setNotArmedFlow(SessionCopy.KILL_SWITCH_NOT_ARMED)
        // VpnManager answers the retryable failure at once.
        state.value = VpnState.Reconnecting(1)

        assertEquals(SessionCopy.KILL_SWITCH_NOT_ARMED, vm.uiState.value.killSwitchNotArmed)
        assertEquals(SessionCopy.KILL_SWITCH_NOT_ARMED, app.birdo.vpn.ui.screen.homeMessage(vm.uiState.value))

        // A block that comes up (or a Connected, or a stop) clears it.
        setNotArmedFlow(null)
        assertNull(app.birdo.vpn.ui.screen.homeMessage(vm.uiState.value))
    }
}
