package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.model.MultiHopConnectResponse
import app.birdo.vpn.data.model.ServerNodeInfo
import app.birdo.vpn.shared.model.MultiHopInfo
import app.birdo.vpn.shared.model.MultiHopNodeInfo
import app.birdo.vpn.data.model.VpnServer
import app.birdo.vpn.data.network.NetworkMonitor
import app.birdo.vpn.data.preferences.AppPreferences
import app.birdo.vpn.data.repository.ApiResult
import app.birdo.vpn.data.repository.BirdoRepository
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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

    /** What NetworkMonitor reports; tests flip it to model going offline. */
    private val onlineFlow = MutableStateFlow(true)

    /**
     * The action of every Intent VpnManager builds, in order. android.jar's
     * Intent is a stub here, so the actions are recorded on construction.
     */
    private val dispatchedActions = mutableListOf<String>()
    private val stringExtras = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        every { networkMonitor.isOnline } returns onlineFlow

        mockkConstructor(Intent::class)
        every { anyConstructed<Intent>().setAction(any()) } answers {
            dispatchedActions += firstArg<String>()
            self as Intent
        }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers {
            stringExtras[firstArg()] = secondArg()
            self as Intent
        }

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
        vpnManager.jitter = { 0.0 }
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

    // ── BirdoShield (D18): pref → dial-time flag ───────────────────────────
    //
    // The review of #403 removed both `dnsFiltering = prefs.dnsFilteringEnabled`
    // lines and the suite stayed green: the repository and serializer tests see
    // the flag once it is a parameter, but nothing proved the PREF reaches the
    // parameter. These four are that proof, one per dial path per state.

    @Test
    fun `connect sends dnsFiltering when the BirdoShield pref is on`() = runTest {
        every { prefs.dnsFilteringEnabled } returns true
        coEvery {
            repository.connectVpn(
                serverNodeId = "srv-1", deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = true,
            )
        } returns ApiResult.Success(makeConnectResponse())

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Success)
        coVerify(exactly = 1) {
            repository.connectVpn(
                serverNodeId = "srv-1", deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = true,
            )
        }
    }

    @Test
    fun `connect leaves dnsFiltering off when the BirdoShield pref is off`() = runTest {
        every { prefs.dnsFilteringEnabled } returns false
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Success)
        coVerify(exactly = 1) {
            repository.connectVpn(
                serverNodeId = "srv-1", deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = false,
            )
        }
        coVerify(exactly = 0) {
            repository.connectVpn(
                serverNodeId = any(), deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = true,
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
        assertEquals(SessionCopy.QUANTUM_FAILED, (result as ApiResult.Error).message)
        assertEquals(FailureKind.QUANTUM_FAILED, (vpnManager.state.value as VpnState.Error).kind)
        coVerify(exactly = 0) { repository.connectVpn(any(), any(), any(), any(), any()) }
    }

    // ── connectMultiHop() ───────────────────────────────────────

    /**
     * A multi-hop response the client will accept. The `multiHop` block is
     * REQUIRED, not decoration: `connectMultiHop` refuses any response whose
     * route block is missing or names a different pair, because a single hop
     * believed to be two is a silent, indefinite jurisdiction leak. Build mock
     * responses through here so a future field addition fails in one place.
     */
    private fun makeMultiHopResponse(
        multiHop: MultiHopInfo? = makeRoute(),
    ) = MultiHopConnectResponse(
        success = true,
        keyId = "mh-key",
        privateKey = "priv-key-base64=",
        serverPublicKey = "srv-pub-base64=",
        endpoint = "de-1.birdo.app:51820",
        assignedIp = "10.100.0.3",
        multiHop = multiHop,
    )

    private fun makeRoute(entryId: String = "de-1", exitId: String = "nl-1") = MultiHopInfo(
        entryNode = MultiHopNodeInfo(id = entryId, name = "Frankfurt", country = "DE"),
        exitNode = MultiHopNodeInfo(id = exitId, name = "Amsterdam", country = "NL"),
        route = "DE -> NL",
    )

    @Test
    fun `connectMultiHop forwards stealth quantum and PQ public key when enabled`() = runTest {
        every { prefs.stealthModeEnabled } returns true
        every { prefs.quantumProtectionEnabled } returns true
        mockkObject(RosenpassManager)
        coEvery { RosenpassManager.getClientPublicKeyB64(context) } returns "pq-public-key"
        val response = makeMultiHopResponse()
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
    fun `connectMultiHop refuses a response with no route block`() = runTest {
        // `success: true` only means the request was handled. Without the route
        // block we cannot tell a real two-hop install from a single hop, and the
        // user cannot observe their own egress country — so refuse.
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(makeMultiHopResponse(multiHop = null))

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Error)
        assertTrue(vpnManager.state.value is VpnState.Error)
    }

    @Test
    fun `connectMultiHop refuses a route naming a different pair`() = runTest {
        // The backend confirmed a DIFFERENT exit. Connecting anyway would render
        // the user's chosen route while egressing somewhere else indefinitely.
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(
            makeMultiHopResponse(multiHop = makeRoute(entryId = "de-1", exitId = "us-9")),
        )

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Error)
        assertTrue(vpnManager.state.value is VpnState.Error)
    }

    @Test
    fun `connectMultiHop sends dnsFiltering when the BirdoShield pref is on`() = runTest {
        every { prefs.dnsFilteringEnabled } returns true
        coEvery {
            repository.connectMultiHop(
                entryNodeId = "de-1", exitNodeId = "nl-1", deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = true,
            )
        } returns ApiResult.Success(makeMultiHopResponse())

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Success)
        coVerify(exactly = 1) {
            repository.connectMultiHop(
                entryNodeId = "de-1", exitNodeId = "nl-1", deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = true,
            )
        }
    }

    @Test
    fun `connectMultiHop leaves dnsFiltering off when the BirdoShield pref is off`() = runTest {
        every { prefs.dnsFilteringEnabled } returns false
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(makeMultiHopResponse())

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Success)
        coVerify(exactly = 1) {
            repository.connectMultiHop(
                entryNodeId = "de-1", exitNodeId = "nl-1", deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = false,
            )
        }
        coVerify(exactly = 0) {
            repository.connectMultiHop(
                entryNodeId = any(), exitNodeId = any(), deviceName = any(), stealthMode = any(),
                fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(),
                integrityToken = any(), dnsFiltering = true,
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
        assertEquals(SessionCopy.QUANTUM_FAILED, (result as ApiResult.Error).message)
        assertEquals(FailureKind.QUANTUM_FAILED, (vpnManager.state.value as VpnState.Error).kind)
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

    // ── The session supervisor (A1-001, -003, -004, -005..-011, -014, -017..-022, -035) ──

    private fun serviceEmits(state: VpnState) {
        every { BirdoVpnService.currentState } returns state
        serviceStateFlow.value = state
    }

    /**
     * End a test whose session still wants to be up. The supervisor never
     * stops on its own for such a session (that is the point: backoff, then
     * the cooldown, forever), and runTest drains the shared scheduler before
     * returning — so without a Disconnect it would advance forever.
     */
    private suspend fun quiesce() {
        vpnManager.disconnect()
    }

    /** Connect to srv-1 and let the service report a real handshake. */
    private suspend fun TestScope.connectAndEstablish() {
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        vpnManager.connect("srv-1")
        serviceEmits(VpnState.Connected)
        runCurrent()
        assertEquals(VpnState.Connected, vpnManager.state.value)
    }

    @Test
    fun `a drop after a real connect retries past the old five attempts, then gives up in a clear state`() = runTest {
        connectAndEstablish()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("timeout")

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        // The old loop spent five attempts in ~62 s and stopped for good.
        advanceTimeBy(20 * 60_000L)

        // 1 original + ReconnectPolicy.maxAttempts(DIED_AFTER_HANDSHAKE) = 8 re-dials.
        coVerify(exactly = 9) { repository.connectVpn(any(), any()) }
        val error = vpnManager.state.value as VpnState.Error
        assertTrue(
            "the give-up says what stopped: ${error.message}",
            error.message.startsWith("BirdoVPN stopped reconnecting after 8 attempts"),
        )
        // Never a silent block: the supervisor releases the app's own block.
        assertTrue(BirdoVpnService.ACTION_RELEASE_BLOCK in dispatchedActions)

        // …and tries once more on its own after the cooldown.
        advanceTimeBy(ReconnectPolicy.TRIP_COOLDOWN_MS)
        coVerify(atLeast = 10) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `an offline drop waits for the network and re-dials the moment it returns`() = runTest {
        connectAndEstablish()
        onlineFlow.value = false
        runCurrent()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("timeout")

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        val waiting = vpnManager.state.value
        assertTrue("shows Reconnecting while offline: $waiting", waiting is VpnState.Reconnecting && waiting.waitingForNetwork)

        // Offline for ten minutes spends no budget and makes no request.
        advanceTimeBy(10 * 60_000L)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }

        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        onlineFlow.value = true
        advanceTimeBy(10_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a 426 on a fresh dial is shown once and never retried`() = runTest {
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("Upgrade required", 426)

        vpnManager.connect("srv-1")
        advanceTimeBy(30 * 60_000L)

        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
        val error = vpnManager.state.value as VpnState.Error
        assertEquals(FailureKind.UPDATE_REQUIRED, error.kind)
        assertEquals(SessionCopy.UPDATE_REQUIRED, error.message)
        // A dial from idle tears nothing down and starts nothing.
        assertFalse(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_START in dispatchedActions)
    }

    @Test
    fun `a user dial that never connected is not retried, even for a transient failure`() = runTest {
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("timeout")

        vpnManager.connect("srv-1")
        advanceTimeBy(10 * 60_000L)

        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
        assertTrue(vpnManager.state.value is VpnState.Error)
        verify { prefs.sessionShouldBeUp = false }
    }

    @Test
    fun `a fresh dial that fails takes down the block it put up`() = runTest {
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        vpnManager.connect("srv-1")
        // The service arms the block on every setup failure.
        every { BirdoVpnService.killSwitchActive } returns true

        serviceEmits(VpnState.Error(SessionCopy.STEALTH_FAILED, FailureKind.STEALTH_FAILED))
        runCurrent()

        // The kill switch is for drops of a session that was up (A1-003).
        assertTrue(BirdoVpnService.ACTION_RELEASE_BLOCK in dispatchedActions)
        assertEquals(SessionCopy.STEALTH_FAILED, (vpnManager.state.value as VpnState.Error).message)
    }

    @Test
    fun `a refusal after a session was up keeps the block and never fails open silently`() = runTest {
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("Upgrade required", 426)

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        advanceTimeBy(30_000)

        assertFalse(BirdoVpnService.ACTION_RELEASE_BLOCK in dispatchedActions)
        assertEquals(FailureKind.UPDATE_REQUIRED, (vpnManager.state.value as VpnState.Error).kind)
    }

    @Test
    fun `a refusal while reconnecting stops recovery at once`() = runTest {
        connectAndEstablish()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("Forbidden", 403)

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        advanceTimeBy(30 * 60_000L)

        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        assertEquals(FailureKind.REFUSED, (vpnManager.state.value as VpnState.Error).kind)
    }

    @Test
    fun `heartbeat valid=false while beats were flowing tears down, releases the block and never re-dials`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        coEvery { repository.sendHeartbeat(any()) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse(valid = false))

        advanceTimeBy(61_000)

        // Owner decision 2026-09-30 (iOS parity): a full STOP — which releases
        // the kill-switch block — carrying the revoke copy, not a held block.
        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(SessionCopy.REVOKED, stringExtras[BirdoVpnService.EXTRA_STOP_REASON])
        assertEquals(FailureKind.REVOKED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
        verify { prefs.sessionShouldBeUp = false }

        serviceEmits(VpnState.Error(SessionCopy.REVOKED, FailureKind.REVOKED))
        advanceTimeBy(30 * 60_000L)
        // No eviction ping-pong: the other device keeps the slot.
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `heartbeat valid=false after an idle gap past the reap window re-dials once behind the block`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        coEvery { repository.sendHeartbeat(any()) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse(valid = false))

        // The phone slept past the backend's 5-minute reap before this beat.
        now = VpnManager.REAP_WINDOW_MS + 60_000L
        advanceTimeBy(61_000)
        assertTrue(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK in dispatchedActions)

        advanceTimeBy(30_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `heartbeat 401 keeps the working tunnel, and the next drop asks for sign-in instead of retrying`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        coEvery { repository.sendHeartbeat(any()) } returns ApiResult.Error("Session expired", 401)

        advanceTimeBy(61_000)

        assertTrue(vpnManager.sessionExpired.value)
        assertEquals(VpnState.Connected, vpnManager.state.value)
        assertFalse(BirdoVpnService.ACTION_STOP in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK in dispatchedActions)

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        advanceTimeBy(30 * 60_000L)
        assertEquals(FailureKind.SIGN_IN_REQUIRED, (vpnManager.state.value as VpnState.Error).kind)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `heartbeat runs every 60 s, and a wake nudge beats at once but not twice in a row`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        coEvery { repository.sendHeartbeat(any()) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse())

        advanceTimeBy(59_000)
        coVerify(exactly = 0) { repository.sendHeartbeat(any()) }
        advanceTimeBy(2_000)
        coVerify(exactly = 1) { repository.sendHeartbeat("key-123") }

        // Screen on right after a beat: dropped.
        vpnManager.heartbeatNow()
        runCurrent()
        coVerify(exactly = 1) { repository.sendHeartbeat(any()) }

        now += 30_000
        vpnManager.heartbeatNow()
        runCurrent()
        coVerify(exactly = 2) { repository.sendHeartbeat(any()) }

        // The loop ends with the session (and must, or the test scheduler
        // would advance it forever).
        serviceEmits(VpnState.Disconnected)
        advanceTimeBy(5 * 60_000L)
        coVerify(exactly = 2) { repository.sendHeartbeat(any()) }
    }

    @Test
    fun `a Disconnect during a multi-hop dial's API call wins, and the minted peer is released`() = runTest {
        val gate = CompletableDeferred<ApiResult<MultiHopConnectResponse>>()
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers { gate.await() }

        val dial = launch { vpnManager.connectMultiHop("de-1", "nl-1") }
        runCurrent()
        vpnManager.disconnect()
        gate.complete(ApiResult.Success(makeMultiHopResponse()))
        runCurrent()

        assertTrue(dial.isCompleted)
        assertFalse(BirdoVpnService.ACTION_START in dispatchedActions)
        coVerify { repository.disconnectVpn("mh-key") }
    }

    @Test
    fun `the notification's Disconnect supersedes a single-hop dial in flight`() = runTest {
        val gate = CompletableDeferred<ApiResult<ConnectResponse>>()
        coEvery { repository.connectVpn(any(), any()) } coAnswers { gate.await() }

        launch { vpnManager.connect("srv-1") }
        runCurrent()
        // ACTION_USER_DISCONNECT → VpnManager.requestDisconnect()
        vpnManager.requestDisconnect()
        runCurrent()
        // The service's own Disconnected lands before the API answers.
        serviceEmits(VpnState.Disconnected)
        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()

        assertFalse(BirdoVpnService.ACTION_START in dispatchedActions)
        coVerify { repository.disconnectVpn("key-123") }
    }

    @Test
    fun `a dial outlives its caller, and a stuck API phase times out instead of Connecting forever`() = runTest {
        coEvery { repository.connectVpn(any(), any()) } coAnswers { awaitCancellation() }

        val caller = launch { vpnManager.connect("srv-1") }
        runCurrent()
        caller.cancel()
        assertEquals(VpnState.Connecting, vpnManager.state.value)

        advanceTimeBy(46_000)
        assertEquals(FailureKind.NEVER_ESTABLISHED, (vpnManager.state.value as VpnState.Error).kind)
    }

    @Test
    fun `a user connect during Reconnecting cancels the pending re-dial`() = runTest {
        connectAndEstablish()
        coEvery { repository.connectVpn("srv-1", any()) } returns ApiResult.Error("timeout")
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        assertTrue(vpnManager.state.value is VpnState.Reconnecting)

        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())
        vpnManager.connect("srv-2")
        advanceTimeBy(30_000)

        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
    }

    @Test
    fun `quick connect from idle runs no switch teardown`() = runTest {
        coEvery { repository.getServers() } returns ApiResult.Success(listOf(makeServer(id = "srv-1")))
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.quickConnect()

        assertFalse(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        coVerify(exactly = 0) { repository.disconnectVpn(any()) }
        assertTrue(BirdoVpnService.ACTION_START in dispatchedActions)
    }

    @Test
    fun `a multi-hop session names its route for the notification, tile and widget`() = runTest {
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(makeMultiHopResponse())
        val config = slot<ConnectResponse>()
        every { BirdoVpnService.setConfig(capture(config)) } just Runs

        vpnManager.connectMultiHop("de-1", "nl-1")

        assertEquals("Frankfurt → Amsterdam", config.captured.serverNode?.name)
        // The node this device handshakes with: the entry.
        assertEquals("de-1", config.captured.serverNode?.id)
    }

    @Test
    fun `a headless start dials the last server, once, never the best one`() = runTest {
        every { prefs.lastServerId } returns "srv-1"
        val gate = CompletableDeferred<ApiResult<ConnectResponse>>()
        coEvery { repository.connectVpn(any(), any()) } coAnswers { gate.await() }

        assertTrue(vpnManager.connectHeadless())
        runCurrent()
        // Always-on and our own MY_PACKAGE_REPLACED can both start us.
        assertFalse(vpnManager.connectHeadless())
        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()

        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        coVerify(exactly = 0) { repository.getServers(any()) }
        quiesce()
    }

    @Test
    fun `a headless session heals a transient failure even before it ever connected`() = runTest {
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("timeout")

        vpnManager.connectHeadless()
        advanceTimeBy(60_000)

        // The phone may have booted before its network: a user dial would stop
        // here, a system start keeps going.
        coVerify(atLeast = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a headless start with Multi-Hop armed dials the pair`() = runTest {
        every { prefs.multiHopEnabled } returns true
        every { prefs.multiHopEntryNodeId } returns "de-1"
        every { prefs.multiHopExitNodeId } returns "nl-1"
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(makeMultiHopResponse())

        vpnManager.connectHeadless()
        runCurrent()

        coVerify { repository.connectMultiHop("de-1", "nl-1", any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a headless start that needs the user publishes why and dials nothing`() = runTest {
        vpnManager.reportHeadlessBlocked(FailureKind.SIGN_IN_REQUIRED)
        advanceTimeBy(30 * 60_000L)

        val error = vpnManager.state.value as VpnState.Error
        assertEquals(FailureKind.SIGN_IN_REQUIRED, error.kind)
        assertEquals(SessionCopy.SESSION_EXPIRED, error.message)
        coVerify(exactly = 0) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `sign-out waits for the server to release the slot`() = runTest {
        val gate = CompletableDeferred<ApiResult<Unit>>()
        coEvery { repository.disconnectVpn(any()) } coAnswers { gate.await() }

        val signOut = launch { vpnManager.disconnectForSignOut() }
        runCurrent()
        assertTrue(BirdoVpnService.ACTION_STOP in dispatchedActions)
        assertFalse("sign-out must not finish before the DELETE", signOut.isCompleted)

        gate.complete(ApiResult.Success(Unit))
        runCurrent()
        assertTrue(signOut.isCompleted)
    }
}
