package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.MultiHopConnectResponse
import app.birdo.vpn.data.model.ServerNodeInfo
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for VpnManager.
 *
 * Covers:
 *  - connect() — API call, state transitions, service intent, error handling
 *  - quickConnect() — server selection, preference for free+lowest-load
 *  - disconnect() — service intent, backend notification
 *  - toggle() — connect/disconnect based on current state
 *  - applyStateWithGuards() — transition guard logic, timeout, error propagation
 *  - isVpnPermissionGranted / getVpnPermissionIntent
 *  - kill switch active state
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VpnManagerTest {

    private lateinit var context: Context
    private lateinit var repository: BirdoRepository
    private lateinit var prefs: AppPreferences
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var vpnManager: VpnManager
    private val testDispatcher = UnconfinedTestDispatcher()

    // Captured state flow for BirdoVpnService static mock
    private val serviceStateFlow = MutableStateFlow<VpnState>(VpnState.Disconnected)

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        every { networkMonitor.isOnline } returns MutableStateFlow(true)

        every { prefs.killSwitchEnabled } returns true
        every { prefs.splitTunnelingEnabled } returns false
        every { prefs.splitTunnelApps } returns emptySet()
        every { prefs.lastServerId } returns null
        every { prefs.stealthModeEnabled } returns false
        every { prefs.quantumProtectionEnabled } returns false

        // Mock BirdoVpnService static companion members
        mockkObject(BirdoVpnService.Companion)
        every { BirdoVpnService.stateFlow } returns serviceStateFlow
        every { BirdoVpnService.currentState } returns VpnState.Disconnected
        every { BirdoVpnService.connectedServer } returns null
        every { BirdoVpnService.connectedSince } returns 0L
        every { BirdoVpnService.killSwitchActive } returns false
        every { BirdoVpnService.setConfig(any()) } just Runs

        // Mock VpnService.prepare() - returns null when permission is granted
        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null

        vpnManager = VpnManager(context, repository, prefs, networkMonitor)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun makeConnectResponse(
        success: Boolean = true,
        message: String? = null,
    ) = ConnectResponse(
        success = success,
        message = message,
        config = "[Interface]\nPrivateKey = ...",
        keyId = "key-123",
        privateKey = "priv-key-base64=",
        publicKey = "pub-key-base64=",
        presharedKey = "psk-base64=",
        assignedIp = "10.100.0.2",
        serverPublicKey = "srv-pub-base64=",
        endpoint = "lon-01.birdo.app:51820",
        dns = listOf("1.1.1.1", "1.0.0.1"),
        allowedIps = listOf("0.0.0.0/0", "::/0"),
        mtu = 1420,
        persistentKeepalive = 25,
        serverNode = ServerNodeInfo(
            id = "srv-1",
            name = "London-01",
            region = "Europe",
            country = "United Kingdom",
            hostname = "lon-01.birdo.app",
        ),
    )

    private fun makeServer(
        id: String = "srv-1",
        name: String = "London-01",
        load: Int = 30,
        isPremium: Boolean = false,
        isOnline: Boolean = true,
        /**
         * Server-computed: does this user's plan reach the node's minPlan?
         * Defaults to a RECON (free) user's view — a premium node is locked —
         * which is what these quickConnect cases model. Pass explicitly to
         * model a paid user.
         */
        accessible: Boolean = !isPremium,
    ) = VpnServer(
        id = id,
        name = name,
        country = "United Kingdom",
        countryCode = "GB",
        city = "London",
        hostname = "lon-01.birdo.app",
        ipAddress = "185.199.108.153",
        port = 51820,
        load = load,
        minPlan = if (isPremium) "OPERATIVE" else "RECON",
        accessible = accessible,
        isPremium = isPremium,
        isHighSpeed = false,
        isPortForwarding = false,
        isOnline = isOnline,
    )

    // ── connect() ───────────────────────────────────────────────

    @Test
    fun `connect sets state to Connecting then starts service on success`() = runTest {
        val response = makeConnectResponse()
        coEvery { repository.connectVpn("srv-1", any()) } returns ApiResult.Success(response)

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Success)
        // State should be Connecting (waiting for service to confirm Connected)
        assertEquals(VpnState.Connecting, vpnManager.state.value)
        // Config should be passed to BirdoVpnService
        verify { BirdoVpnService.setConfig(response) }
        // Service should be started
        verify { context.startForegroundService(any()) }
        // Connected server name should be set
        assertEquals("London-01", vpnManager.connectedServer.value)
        // Last server preference saved
        verify { prefs.lastServerId = "srv-1" }
    }

    @Test
    fun `connect passes kill switch and split tunnel extras to intent`() = runTest {
        every { prefs.killSwitchEnabled } returns true
        every { prefs.splitTunnelingEnabled } returns true
        every { prefs.splitTunnelApps } returns setOf("com.example.app")

        val response = makeConnectResponse()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(response)

        vpnManager.connect("srv-1")

        verify { context.startForegroundService(any()) }
        verify { prefs.killSwitchEnabled }
        verify { prefs.splitTunnelingEnabled }
        verify { prefs.splitTunnelApps }
    }

    @Test
    fun `connect sets Error state when API returns error`() = runTest {
        coEvery { repository.connectVpn(any(), any()) } returns
            ApiResult.Error("No active subscription")

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Error)
        assertEquals("No active subscription", (result as ApiResult.Error).message)
        assertTrue(vpnManager.state.value is VpnState.Error)
        assertEquals("No active subscription", (vpnManager.state.value as VpnState.Error).message)
    }

    @Test
    fun `connect sets Error state when server response has success=false`() = runTest {
        val badResponse = makeConnectResponse(success = false, message = "Device limit reached")
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(badResponse)

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Error)
        assertTrue(vpnManager.state.value is VpnState.Error)
    }

    @Test
    fun `connect sets Error state when privateKey is null`() = runTest {
        val noPrivKey = makeConnectResponse().copy(privateKey = null)
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(noPrivKey)

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Error)
        assertTrue(vpnManager.state.value is VpnState.Error)
    }

    @Test
    fun `connect sets Error state when serverPublicKey is null`() = runTest {
        val noSrvPk = makeConnectResponse().copy(serverPublicKey = null)
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(noSrvPk)

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `connect sets Error state when endpoint is null`() = runTest {
        val noEndpoint = makeConnectResponse().copy(endpoint = null)
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(noEndpoint)

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `connect falls back to Unknown Server when serverNode is null`() = runTest {
        val noNode = makeConnectResponse().copy(serverNode = null)
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(noNode)

        vpnManager.connect("srv-1")

        assertEquals("Unknown Server", vpnManager.connectedServer.value)
    }

    @Test
    fun `connect forwards stealth quantum and PQ public key when enabled`() = runTest {
        every { prefs.stealthModeEnabled } returns true
        every { prefs.quantumProtectionEnabled } returns true
        mockkObject(RosenpassManager)
        coEvery { RosenpassManager.getClientPublicKeyB64(context) } returns "pq-public-key"
        val response = makeConnectResponse()
        coEvery {
            repository.connectVpn(
                serverNodeId = "srv-1",
                deviceName = any(),
                stealthMode = true,
                quantumProtection = true,
                pqClientPublicKey = "pq-public-key",
            )
        } returns ApiResult.Success(response)

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Success)
        coVerify {
            repository.connectVpn(
                serverNodeId = "srv-1",
                deviceName = any(),
                stealthMode = true,
                quantumProtection = true,
                pqClientPublicKey = "pq-public-key",
            )
        }
    }

    @Test
    fun `connect refuses quantum downgrade when PQ public key is unavailable`() = runTest {
        every { prefs.quantumProtectionEnabled } returns true
        mockkObject(RosenpassManager)
        coEvery { RosenpassManager.getClientPublicKeyB64(context) } returns null

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Error)
        assertEquals("Quantum engine unavailable", (result as ApiResult.Error).message)
        assertTrue(vpnManager.state.value is VpnState.Error)
        coVerify(exactly = 0) { repository.connectVpn(any(), any(), any(), any(), any()) }
    }

    // ── connectMultiHop() ───────────────────────────────────────

    @Test
    fun `connectMultiHop forwards stealth quantum and PQ public key when enabled`() = runTest {
        every { prefs.stealthModeEnabled } returns true
        every { prefs.quantumProtectionEnabled } returns true
        mockkObject(RosenpassManager)
        coEvery { RosenpassManager.getClientPublicKeyB64(context) } returns "pq-public-key"
        val response = MultiHopConnectResponse(
            success = true,
            keyId = "mh-key",
            privateKey = "priv-key-base64=",
            serverPublicKey = "srv-pub-base64=",
            endpoint = "de-1.birdo.app:51820",
            assignedIp = "10.100.0.3",
        )
        coEvery {
            repository.connectMultiHop(
                entryNodeId = "de-1",
                exitNodeId = "nl-1",
                deviceName = any(),
                stealthMode = true,
                quantumProtection = true,
                pqClientPublicKey = "pq-public-key",
            )
        } returns ApiResult.Success(response)

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Success)
        coVerify {
            repository.connectMultiHop(
                entryNodeId = "de-1",
                exitNodeId = "nl-1",
                deviceName = any(),
                stealthMode = true,
                quantumProtection = true,
                pqClientPublicKey = "pq-public-key",
            )
        }
    }

    @Test
    fun `connectMultiHop refuses quantum downgrade when PQ public key is unavailable`() = runTest {
        every { prefs.quantumProtectionEnabled } returns true
        mockkObject(RosenpassManager)
        coEvery { RosenpassManager.getClientPublicKeyB64(context) } returns null

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Error)
        assertEquals("Quantum engine unavailable", (result as ApiResult.Error).message)
        assertTrue(vpnManager.state.value is VpnState.Error)
        coVerify(exactly = 0) { repository.connectMultiHop(any(), any(), any(), any(), any(), any()) }
    }

    // ── quickConnect() ──────────────────────────────────────────

    @Test
    fun `quickConnect selects lowest-load free online server`() = runTest {
        val servers = listOf(
            makeServer(id = "srv-high", load = 80),
            makeServer(id = "srv-low", load = 10),
            makeServer(id = "srv-premium", load = 5, isPremium = true),
            makeServer(id = "srv-offline", load = 0, isOnline = false),
        )
        coEvery { repository.getServers() } returns ApiResult.Success(servers)

        val response = makeConnectResponse()
        coEvery { repository.connectVpn("srv-low", any()) } returns ApiResult.Success(response)

        val result = vpnManager.quickConnect()

        assertTrue(result is ApiResult.Success)
        coVerify { repository.connectVpn("srv-low", any()) }
    }

    @Test
    fun `quickConnect errors rather than picking an out-of-plan server`() = runTest {
        // A RECON user, fleet of premium-only online nodes. The old code fell
        // back to "any online server" and connected to a node the backend was
        // always going to refuse. Refusing locally is the honest answer.
        val servers = listOf(
            makeServer(id = "srv-prem", load = 10, isPremium = true),
            makeServer(id = "srv-offline", load = 5, isOnline = false),
        )
        coEvery { repository.getServers() } returns ApiResult.Success(servers)

        val result = vpnManager.quickConnect()

        assertTrue(result is ApiResult.Error)
        coVerify(exactly = 0) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `quickConnect uses a premium node when the plan reaches it`() = runTest {
        // Same fleet, but the user's plan clears the node's minPlan, so the
        // backend marks it accessible.
        val servers = listOf(
            makeServer(id = "srv-prem", load = 10, isPremium = true, accessible = true),
            makeServer(id = "srv-offline", load = 5, isOnline = false),
        )
        coEvery { repository.getServers() } returns ApiResult.Success(servers)

        val response = makeConnectResponse()
        coEvery { repository.connectVpn("srv-prem", any()) } returns ApiResult.Success(response)

        val result = vpnManager.quickConnect()

        assertTrue(result is ApiResult.Success)
        coVerify { repository.connectVpn("srv-prem", any()) }
    }

    @Test
    fun `quickConnect returns error when no servers available`() = runTest {
        coEvery { repository.getServers() } returns ApiResult.Success(emptyList())

        val result = vpnManager.quickConnect()

        assertTrue(result is ApiResult.Error)
        assertEquals("No servers available", (result as ApiResult.Error).message)
        assertTrue(vpnManager.state.value is VpnState.Error)
    }

    @Test
    fun `quickConnect returns error when getServers API fails`() = runTest {
        coEvery { repository.getServers() } returns ApiResult.Error("Network error")

        val result = vpnManager.quickConnect()

        assertTrue(result is ApiResult.Error)
        assertTrue(vpnManager.state.value is VpnState.Error)
    }

    @Test
    fun `quickConnect returns error when all servers are offline`() = runTest {
        val servers = listOf(
            makeServer(id = "srv-1", isOnline = false),
            makeServer(id = "srv-2", isOnline = false),
        )
        coEvery { repository.getServers() } returns ApiResult.Success(servers)

        val result = vpnManager.quickConnect()

        assertTrue(result is ApiResult.Error)
        assertEquals("No servers available", (result as ApiResult.Error).message)
    }

    // ── disconnect() ────────────────────────────────────────────

    @Test
    fun `disconnect sets Disconnecting state and starts stop service`() = runTest {
        vpnManager.disconnect()

        assertEquals(VpnState.Disconnecting, vpnManager.state.value)
        verify { context.startForegroundService(any()) }
        coVerify { repository.disconnectVpn() }
    }

    @Test
    fun `disconnect notifies backend even if service call fails`() = runTest {
        // Backend notification is best-effort
        coEvery { repository.disconnectVpn() } returns ApiResult.Error("Network")

        vpnManager.disconnect()

        // Should not throw, disconnect is resilient
        assertEquals(VpnState.Disconnecting, vpnManager.state.value)
    }

    // ── toggle() ────────────────────────────────────────────────

    @Test
    fun `toggle disconnects when currently connected`() = runTest {
        // Simulate Connected state
        serviceStateFlow.value = VpnState.Connected
        every { BirdoVpnService.currentState } returns VpnState.Connected
        advanceUntilIdle()

        val result = vpnManager.toggle()

        assertFalse(result)
        coVerify { repository.disconnectVpn() }
    }

    @Test
    fun `toggle connects when currently disconnected`() = runTest {
        val servers = listOf(makeServer(id = "srv-1", load = 10))
        coEvery { repository.getServers() } returns ApiResult.Success(servers)
        coEvery { repository.connectVpn(any(), any()) } returns
            ApiResult.Success(makeConnectResponse())

        val result = vpnManager.toggle()

        assertTrue(result)
    }

    @Test
    fun `toggle returns false when connect fails`() = runTest {
        coEvery { repository.getServers() } returns ApiResult.Error("Network error")

        val result = vpnManager.toggle()

        assertFalse(result)
    }

    // ── State guard logic ───────────────────────────────────────

    @Test
    fun `Error state from service always propagates immediately`() = runTest {
        val response = makeConnectResponse()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(response)

        vpnManager.connect("srv-1")
        // Now simulate an Error from the service
        serviceStateFlow.value = VpnState.Error("Tunnel failed")
        advanceUntilIdle()

        assertTrue(vpnManager.state.value is VpnState.Error)
        assertEquals("Tunnel failed", (vpnManager.state.value as VpnState.Error).message)
    }

    @Test
    fun `Connected state propagates from service after Connecting`() = runTest {
        // Simulate service transitioning to Connected
        val response = makeConnectResponse()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(response)

        vpnManager.connect("srv-1")
        // Now service confirms Connected
        every { BirdoVpnService.currentState } returns VpnState.Connected
        every { BirdoVpnService.connectedServer } returns "London-01"
        every { BirdoVpnService.connectedSince } returns System.currentTimeMillis()
        serviceStateFlow.value = VpnState.Connected
        advanceUntilIdle()

        assertEquals(VpnState.Connected, vpnManager.state.value)
    }

    @Test
    fun `Disconnected state propagates from service after Disconnecting`() = runTest {
        // First get to Connected state
        serviceStateFlow.value = VpnState.Connected
        advanceUntilIdle()

        // Now disconnect
        vpnManager.disconnect()
        // Service confirms disconnected
        serviceStateFlow.value = VpnState.Disconnected
        advanceUntilIdle()

        assertEquals(VpnState.Disconnected, vpnManager.state.value)
    }

    // ── VPN Permission ──────────────────────────────────────────

    @Test
    fun `isVpnPermissionGranted returns true when prepare returns null`() {
        every { VpnService.prepare(any()) } returns null

        assertTrue(vpnManager.isVpnPermissionGranted())
    }

    @Test
    fun `isVpnPermissionGranted returns false when prepare returns intent`() {
        every { VpnService.prepare(any()) } returns mockk<Intent>()

        assertFalse(vpnManager.isVpnPermissionGranted())
    }

    @Test
    fun `getVpnPermissionIntent returns intent from VpnService prepare`() {
        val permIntent = mockk<Intent>()
        every { VpnService.prepare(any()) } returns permIntent

        assertEquals(permIntent, vpnManager.getVpnPermissionIntent())
    }

    // ── Kill switch ─────────────────────────────────────────────

    @Test
    fun `isKillSwitchActive delegates to BirdoVpnService`() {
        every { BirdoVpnService.killSwitchActive } returns true
        assertTrue(vpnManager.isKillSwitchActive)

        every { BirdoVpnService.killSwitchActive } returns false
        assertFalse(vpnManager.isKillSwitchActive)
    }

    // ── syncState() ─────────────────────────────────────────────

    @Test
    fun `syncState updates from BirdoVpnService currentState`() {
        every { BirdoVpnService.currentState } returns VpnState.Connected
        every { BirdoVpnService.connectedServer } returns "Tokyo-01"
        every { BirdoVpnService.connectedSince } returns 1000L

        vpnManager.syncState()

        assertEquals(VpnState.Connected, vpnManager.state.value)
        assertEquals("Tokyo-01", vpnManager.connectedServer.value)
        assertEquals(1000L, vpnManager.connectedSince.value)
    }
}
