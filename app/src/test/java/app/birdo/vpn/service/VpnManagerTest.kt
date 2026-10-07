package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.birdo.vpn.R
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
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
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

    /** Every physical network behind a captive portal (A1-026); wins over [onlineFlow]. */
    private val captiveFlow = MutableStateFlow(false)

    /**
     * The action of every Intent VpnManager builds, in order. android.jar's
     * Intent is a stub here, so the actions are recorded on construction.
     */
    private val dispatchedActions = mutableListOf<String>()

    /**
     * The service's transport-blocked signal, per test. The real one is a
     * static flow: every VpnManager an earlier test left behind still
     * collects it, and (Dispatchers.Main being whatever the CURRENT test set)
     * would run its own fallback and supervisor on this test's scheduler.
     */
    private val transportBlocked = MutableSharedFlow<Unit>(extraBufferCapacity = 4)

    /** Home-screen widget re-renders VpnManager asked for (REVIEW-AND-012). */
    private var widgetRefreshes = 0
    private val stringExtras = mutableMapOf<String, String?>()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        context = mockk(relaxed = true)
        repository = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)

        every { networkMonitor.isOnline } returns onlineFlow
        every { networkMonitor.status } returns combine(onlineFlow, captiveFlow) { online, captive ->
            when {
                captive -> NetworkMonitor.Connectivity.CAPTIVE_PORTAL
                online -> NetworkMonitor.Connectivity.ONLINE
                else -> NetworkMonitor.Connectivity.OFFLINE
            }
        }

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
        // Every Connected session beats at once (WEB-HB); a healthy reply by default.
        coEvery { repository.sendHeartbeat(any()) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse())
        // …and so does the one probe around a dead tunnel (REVIEW-AND2-001):
        // the key is still live, so the drop is re-dialled as before.
        coEvery { repository.sendHeartbeat(any(), true) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse())

        // Mock BirdoVpnService static companion members
        mockkObject(BirdoVpnService.Companion)
        every { BirdoVpnService.stateFlow } returns serviceStateFlow
        every { BirdoVpnService.currentState } returns VpnState.Disconnected
        every { BirdoVpnService.connectedServer } returns null
        every { BirdoVpnService.connectedSince } returns 0L
        every { BirdoVpnService.killSwitchActive } returns false
        every { BirdoVpnService.setConfig(any()) } just Runs
        every { BirdoVpnService.transportBlockedFlow } returns transportBlocked

        // Mock VpnService.prepare() - returns null when permission is granted
        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null

        vpnManager = VpnManager(context, repository, prefs, networkMonitor)
        vpnManager.jitter = { 0.0 }
        vpnManager.refreshWidget = { widgetRefreshes++ }
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
        mockkObject(BirdoPqManager)
        coEvery { BirdoPqManager.getClientPublicKeyB64(context) } returns "pq-public-key"
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
        mockkObject(BirdoPqManager)
        coEvery { BirdoPqManager.getClientPublicKeyB64(context) } returns null

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
        mockkObject(BirdoPqManager)
        coEvery { BirdoPqManager.getClientPublicKeyB64(context) } returns "pq-public-key"
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
        mockkObject(BirdoPqManager)
        coEvery { BirdoPqManager.getClientPublicKeyB64(context) } returns null

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
        assertEquals(SessionCopy.NO_SERVERS, (result as ApiResult.Error).message)
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
        assertEquals(SessionCopy.NO_SERVERS, (result as ApiResult.Error).message)
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

        // …and tries exactly ONCE more on its own after the cooldown
        // (REVIEW-AND-011: it used to restart the full eight-attempt budget,
        // re-arming the block for ~8.5 min every 15 min).
        // The give-up landed at ~8.5 min; its cooldown re-dial at ~23.5 min.
        advanceTimeBy(ReconnectPolicy.TRIP_COOLDOWN_MS)
        coVerify(exactly = 10) { repository.connectVpn(any(), any()) }
        advanceTimeBy(2 * 60_000L)
        coVerify(exactly = 10) { repository.connectVpn(any(), any()) }
        // The next cooldown (~38.5 min) allows the next single attempt, and
        // only that one.
        advanceTimeBy(ReconnectPolicy.TRIP_COOLDOWN_MS - 2 * 60_000L)
        coVerify(exactly = 11) { repository.connectVpn(any(), any()) }
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

    /**
     * Live, 2026-09-30 (emulator, airplane mode, kill switch on): the
     * notification counted "Attempt 4, 5, 6" through the whole outage and
     * never said "Waiting for a network connection…", because the app's own
     * block-all interface kept NetworkMonitor "online". With NOT_VPN tracking
     * the offline edge arrives, and a re-dial already scheduled turns into a
     * wait that spends nothing.
     */
    @Test
    fun `going offline during a backoff stops counting attempts and waits for the network`() = runTest {
        connectAndEstablish()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("timeout")
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        val backoff = vpnManager.state.value
        assertTrue("a backoff first: $backoff", backoff is VpnState.Reconnecting && !backoff.waitingForNetwork)

        onlineFlow.value = false
        runCurrent()
        val waiting = vpnManager.state.value
        assertTrue("waits once offline: $waiting", waiting is VpnState.Reconnecting && waiting.waitingForNetwork)
        advanceTimeBy(10 * 60_000L)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }

        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        onlineFlow.value = true
        advanceTimeBy(10_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `behind a captive portal the session waits, says so, and re-dials once the portal is passed`() = runTest {
        connectAndEstablish()
        captiveFlow.value = true
        runCurrent()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("timeout")

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        val waiting = vpnManager.state.value
        assertTrue(
            "a captive portal is a wait with its own reason (A1-026): $waiting",
            waiting is VpnState.Reconnecting && waiting.waitingForNetwork && waiting.captivePortal,
        )
        // A dial behind the portal only burns the budget: none is made.
        advanceTimeBy(10 * 60_000L)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }

        // Signing in to the Wi-Fi validates it: that edge re-dials.
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        captiveFlow.value = false
        advanceTimeBy(10_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a dial that cannot get past a captive portal says to sign in to the Wi-Fi`() = runTest {
        captiveFlow.value = true
        runCurrent()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("Couldn't reach BirdoVPN.", 0)

        vpnManager.connect("srv-1")

        assertEquals(SessionCopy.CAPTIVE_PORTAL, (vpnManager.state.value as VpnState.Error).message)
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

    // ── REVIEW-AND-012: the widget follows the manager's own states ─────

    @Test
    fun `a refusal before the service ever starts re-renders the widget`() = runTest {
        // A widget tap: no service running, so its render loop cannot refresh
        // anything. The widget used to stay on "Connecting… Tap to cancel".
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("Please update BirdoVPN.", 426)

        vpnManager.connect("srv-1")
        advanceUntilIdle()

        assertTrue(vpnManager.state.value is VpnState.Error)
        assertFalse(BirdoVpnService.ACTION_START in dispatchedActions)
        // At least the Error's model reached the widget (Connecting may be
        // conflated away under the test scheduler).
        assertTrue("refreshes=$widgetRefreshes", widgetRefreshes >= 1)
    }

    // ── REVIEW-AND-023 (iOS #354): a switch that cannot be undone fails closed ──

    @Test
    fun `a switch over the teardown path that never connects keeps the block it held, and stops`() = runTest {
        connectAndEstablish()
        // Stealth is never rebuilt in place, so this switch tears down first.
        every { BirdoVpnService.stealthActive } returns true
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())
        vpnManager.connect("srv-2")
        every { BirdoVpnService.killSwitchActive } returns true

        serviceEmits(VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED))
        advanceTimeBy(10 * 60_000L)

        // The previous session is gone and cannot be swapped back, as on the
        // live path (SessionCopy.switchFailedClosed): the kill switch's block
        // holds, and nothing re-dials behind it.
        assertFalse(BirdoVpnService.ACTION_RELEASE_BLOCK in dispatchedActions)
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
        verify { prefs.sessionShouldBeUp = false }
        quiesce()
    }

    // ── Free-plan allowance at check-in (birdo-web PR #590) ─────────────

    @Test
    fun `a heartbeat inside the quota grace window says so and keeps the session`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        coEvery { repository.sendHeartbeat(any()) } returns ApiResult.Success(
            app.birdo.vpn.data.model.HeartbeatResponse(
                valid = true, quotaExceeded = true, quotaGraceSecondsRemaining = 840L,
            ),
        )

        advanceTimeBy(61_000)

        assertEquals(QuotaGrace(14), vpnManager.quotaGrace.value)
        assertEquals(VpnState.Connected, vpnManager.state.value)
        assertFalse(BirdoVpnService.ACTION_STOP in dispatchedActions)
        quiesce()
        serviceEmits(VpnState.Disconnected)
        assertNull("the notice goes with the session", vpnManager.quotaGrace.value)
    }

    @Test
    fun `quota_exceeded ends the session as a plan decision, releases the block and never re-dials`() = runTest {
        every { context.getString(R.string.session_quota_exceeded) } returns
            app.birdo.vpn.testing.StringsXml.text("session_quota_exceeded")
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        coEvery { repository.sendHeartbeat(any()) } returns ApiResult.Success(
            app.birdo.vpn.data.model.HeartbeatResponse(valid = false, quotaExceeded = true, reason = "quota_exceeded"),
        )

        advanceTimeBy(61_000)

        // A full STOP (the block released), with the shipped copy and the
        // plan kind that offers View plans.
        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(
            app.birdo.vpn.testing.StringsXml.text("session_quota_exceeded"),
            stringExtras[BirdoVpnService.EXTRA_STOP_REASON],
        )
        assertEquals(FailureKind.QUOTA_EXCEEDED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
        verify { prefs.sessionShouldBeUp = false }
        // The peer is already gone server-side: nothing to release.
        coVerify(exactly = 0) { repository.disconnectVpn(any()) }

        serviceEmits(VpnState.Error(app.birdo.vpn.testing.StringsXml.text("session_quota_exceeded"), FailureKind.QUOTA_EXCEEDED))
        advanceTimeBy(30 * 60_000L)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `a re-dial refused with retryAfterSeconds waits that long before the next one`() = runTest {
        connectAndEstablish()
        coEvery { repository.connectVpn(any(), any()) } returns
            ApiResult.Error("Try again shortly", 503, retryAfterMs = 30_000L)

        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        // First re-dial ~2 s + the 5 s teardown wait; its 503 asks for 30 s.
        // Without the hint the second would follow ~4 s (+5 s) later.
        advanceTimeBy(20_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        val reconnecting = vpnManager.state.value
        assertTrue("TRANSIENT: still recovering, $reconnecting", reconnecting is VpnState.Reconnecting)

        advanceTimeBy(25_000)
        coVerify(exactly = 3) { repository.connectVpn(any(), any()) }
        quiesce()
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
    fun `the first beat goes out as the tunnel comes up, then every 60 s, and a wake nudge beats once`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        runCurrent()
        // WEB-HB: the backend keeps only keys that checked in.
        coVerify(exactly = 1) { repository.sendHeartbeat("key-123") }

        advanceTimeBy(59_000)
        coVerify(exactly = 1) { repository.sendHeartbeat(any()) }
        now = 61_000
        advanceTimeBy(2_000)
        coVerify(exactly = 2) { repository.sendHeartbeat("key-123") }

        // Screen on right after a beat: dropped.
        vpnManager.heartbeatNow()
        runCurrent()
        coVerify(exactly = 2) { repository.sendHeartbeat(any()) }

        now += 30_000
        vpnManager.heartbeatNow()
        runCurrent()
        coVerify(exactly = 3) { repository.sendHeartbeat(any()) }

        // The loop ends with the session (and must, or the test scheduler
        // would advance it forever).
        serviceEmits(VpnState.Disconnected)
        advanceTimeBy(5 * 60_000L)
        coVerify(exactly = 3) { repository.sendHeartbeat(any()) }
    }

    // ── WEB-HB: the heartbeat's reason ──────────────────────────────────

    private fun beatsAnswer(valid: Boolean, reason: String?) {
        coEvery { repository.sendHeartbeat(any()) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse(valid = valid, reason = reason))
    }

    @Test
    fun `evicted stops the session with the canonical sentence and never re-dials`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        beatsAnswer(valid = false, reason = "evicted")

        advanceTimeBy(61_000)

        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(SessionCopy.EVICTED, stringExtras[BirdoVpnService.EXTRA_STOP_REASON])
        assertEquals(FailureKind.EVICTED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
        verify { prefs.sessionShouldBeUp = false }
        serviceEmits(VpnState.Error(SessionCopy.EVICTED, FailureKind.EVICTED))
        advanceTimeBy(30 * 60_000L)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `reaped rebuilds once behind the block, even while beats were flowing`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        beatsAnswer(valid = false, reason = "reaped")

        advanceTimeBy(61_000)
        assertTrue(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_STOP in dispatchedActions)
        advanceTimeBy(30_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a server that went offline for good is replaced by another one, behind the block`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        coEvery { repository.getServers(any()) } returns ApiResult.Success(
            listOf(makeServer(id = "srv-1", load = 1), makeServer(id = "srv-2", load = 50)),
        )
        beatsAnswer(valid = false, reason = "server_offline")

        advanceTimeBy(61_000)
        assertTrue(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK in dispatchedActions)
        advanceTimeBy(30_000)
        // srv-1 is the lowest load, and the one that went away: never re-dialled.
        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
        quiesce()
    }

    @Test
    fun `not_found is a reap after a long silence and a revoke while beats were flowing`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        runCurrent()
        beatsAnswer(valid = false, reason = "not_found")

        now = 60_000L
        advanceTimeBy(61_000)
        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(FailureKind.REVOKED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
    }

    @Test
    fun `a reply for a key this device already left is ignored`() = runTest {
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        runCurrent()
        val gate = CompletableDeferred<ApiResult<app.birdo.vpn.data.model.HeartbeatResponse>>()
        coEvery { repository.sendHeartbeat(any()) } coAnswers { gate.await() }
        advanceTimeBy(61_000)

        // The user disconnects while that beat is in flight; the DELETE wins,
        // and the server answers the beat for the key it just removed.
        vpnManager.disconnect()
        gate.complete(
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse(valid = false, reason = "revoked")),
        )
        runCurrent()

        // REVIEW-AND-013: no revoke teardown over the user's own Disconnect.
        assertEquals(null, stringExtras[BirdoVpnService.EXTRA_STOP_REASON])
    }

    // ── REVIEW-AND2-001: the server's reason, heard around a dead tunnel ──

    private fun probeAnswers(reply: ApiResult<app.birdo.vpn.data.model.HeartbeatResponse>) {
        coEvery { repository.sendHeartbeat(any(), true) } returns reply
    }

    private fun probeSays(valid: Boolean, reason: String?, serverOnline: Boolean = valid, quota: Boolean = false) =
        probeAnswers(
            ApiResult.Success(
                app.birdo.vpn.data.model.HeartbeatResponse(
                    valid = valid,
                    serverOnline = serverOnline,
                    reason = reason,
                    quotaExceeded = quota,
                ),
            ),
        )

    private fun tunnelDies() =
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))

    /**
     * A1-004's ping-pong, back since D-6. Two devices on a one-device plan:
     * B connects, the server evicts A's key — taking A's peer off the node
     * BEFORE it can tell A — and A's tunnel goes quiet. A's re-dial would evict
     * B, whose own dead tunnel would evict A, forever. Asked around the tunnel,
     * the server says "evicted", and A stops.
     */
    @Test
    fun `an evicted key's dead tunnel stops there instead of re-dialling into the other device`() = runTest {
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        // Live beats ride the tunnel; none has gone around it.
        coVerify(exactly = 0) { repository.sendHeartbeat(any(), true) }
        probeSays(valid = false, reason = "evicted", serverOnline = false)

        tunnelDies()
        advanceTimeBy(10_000)

        coVerify(exactly = 1) { repository.sendHeartbeat("key-123", true) }
        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(SessionCopy.EVICTED, stringExtras[BirdoVpnService.EXTRA_STOP_REASON])
        assertEquals(FailureKind.EVICTED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
        verify { prefs.sessionShouldBeUp = false }
        serviceEmits(VpnState.Error(SessionCopy.EVICTED, FailureKind.EVICTED))
        advanceTimeBy(30 * 60_000L)
        // B keeps the slot: A never dialled again.
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `a remote Disconnect is not undone by the re-dial, on today's backend too`() = runTest {
        connectAndEstablish()
        // birdo-web main before WEB-HB: no reason, just a key that is gone
        // while beats were flowing — the live path's inference, a revoke.
        probeSays(valid = false, reason = null, serverOnline = false)

        tunnelDies()
        advanceTimeBy(10_000)

        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(FailureKind.REVOKED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
        serviceEmits(VpnState.Error(SessionCopy.REVOKED, FailureKind.REVOKED))
        advanceTimeBy(30 * 60_000L)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `the Free allowance's end is heard around the dead tunnel and releases the block`() = runTest {
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        probeSays(valid = false, reason = "quota_exceeded", serverOnline = true, quota = true)

        tunnelDies()
        advanceTimeBy(10_000)

        // A full STOP (it releases the block), as a plan decision: View plans.
        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        assertEquals(FailureKind.QUOTA_EXCEEDED.name, stringExtras[BirdoVpnService.EXTRA_STOP_KIND])
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
    }

    /**
     * The second line of defence: the probe's answer was lost too, so the
     * re-dial meets the connect gate's quota refusal. It used to be a generic
     * REFUSED that kept an established session's block: a Free user fully
     * blocked behind "can't connect", with no View plans.
     */
    @Test
    fun `a re-dial refused for the Free allowance ends as QUOTA_EXCEEDED and releases the block`() = runTest {
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        probeAnswers(ApiResult.Error("Couldn't reach BirdoVPN.", 0))
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(
            ConnectResponse(success = false, quotaExceeded = true, message = "You've used your free data for this month."),
        )

        tunnelDies()
        advanceTimeBy(30 * 60_000L)

        val error = vpnManager.state.value as VpnState.Error
        assertEquals(FailureKind.QUOTA_EXCEEDED, error.kind)
        assertEquals("You've used your free data for this month.", error.message)
        assertTrue(BirdoVpnService.ACTION_RELEASE_BLOCK in dispatchedActions)
        verify { prefs.sessionShouldBeUp = false }
        // One re-dial, never another.
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
    }

    @Test
    fun `a drained server is left for another one instead of re-dialled into a refusal`() = runTest {
        connectAndEstablish()
        coEvery { repository.getServers(any()) } returns ApiResult.Success(
            listOf(makeServer(id = "srv-1", load = 1), makeServer(id = "srv-2", load = 50)),
        )
        probeSays(valid = false, reason = "server_offline", serverOnline = false)

        tunnelDies()
        advanceTimeBy(30_000)

        // srv-1 has the lowest load, and is the node that went away.
        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
        quiesce()
    }

    @Test
    fun `a dead tunnel whose key is still live re-dials after one probe, and a silent probe holds it at most 5 s`() = runTest {
        connectAndEstablish()
        probeAnswers(ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse()))
        tunnelDies()
        advanceTimeBy(30_000)
        coVerify(exactly = 1) { repository.sendHeartbeat("key-123", true) }
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }

        // The re-dial connected; its tunnel dies too, and this time the probe
        // never answers: the re-dial waits out the cap, no longer.
        serviceEmits(VpnState.Connected)
        runCurrent()
        coEvery { repository.sendHeartbeat(any(), true) } coAnswers { kotlinx.coroutines.awaitCancellation() }
        tunnelDies()
        // The first re-dial is due ~2 s after the drop; the probe holds it.
        advanceTimeBy(2_000 + VpnManager.DEAD_SESSION_PROBE_TIMEOUT_MS - 500)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        advanceTimeBy(15_000)
        coVerify(exactly = 3) { repository.connectVpn(any(), any()) }
        quiesce()
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

        assertTrue(vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON))
        runCurrent()
        // Always-on and our own MY_PACKAGE_REPLACED can both start us.
        assertFalse(vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON))
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

        vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON)
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
        every { prefs.lastKnownPlan } returns "SOVEREIGN"
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(makeMultiHopResponse())

        vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON)
        runCurrent()

        coVerify { repository.connectMultiHop("de-1", "nl-1", any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    /**
     * REVIEW-AND-007: an ex-Sovereign user with Always-on booted into a
     * refused Multi-Hop dial and a held block on every reboot, because the
     * headless dial read the raw pref, which a lapsed plan never clears.
     */
    private fun armMultiHopPrefs() {
        every { prefs.multiHopEnabled } returns true
        every { prefs.multiHopEntryNodeId } returns "de-1"
        every { prefs.multiHopExitNodeId } returns "nl-1"
        every { prefs.lastServerId } returns "srv-1"
    }

    @Test
    fun `a headless start dials a single hop for a lapsed plan`() = runTest {
        armMultiHopPrefs()
        every { prefs.lastKnownPlan } returns "OPERATIVE"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON)
        runCurrent()

        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        coVerify(exactly = 0) { repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        quiesce()
    }

    @Test
    fun `a headless start refuses to guess an unknown plan for an armed Multi-Hop pref`() = runTest {
        armMultiHopPrefs()
        every { prefs.lastKnownPlan } returns null

        vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON)
        runCurrent()

        val error = vpnManager.state.value as VpnState.Error
        assertEquals(FailureKind.SETUP_REQUIRED, error.kind)
        coVerify(exactly = 0) { repository.connectVpn(any(), any()) }
        coVerify(exactly = 0) { repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a remembered Multi-Hop route is not rebuilt once the user disarmed Multi-Hop`() = runTest {
        // A live multi-hop session...
        every { prefs.lastKnownPlan } returns "SOVEREIGN"
        coEvery {
            repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns ApiResult.Success(makeMultiHopResponse())
        vpnManager.connectMultiHop("de-1", "nl-1")
        serviceEmits(VpnState.Connected)
        runCurrent()
        // ...the user turns Multi-Hop off, and later taps Reconnect (REVIEW-AND-019).
        every { prefs.multiHopEnabled } returns false
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        vpnManager.connectPreferred()
        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        coVerify(exactly = 1) { repository.connectMultiHop(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        quiesce()
    }

    @Test
    fun `the dialled node, not the selection, is what the Servers list marks connected`() = runTest {
        connectAndEstablish()
        assertEquals("srv-1", vpnManager.connectedServerId.value)
        vpnManager.disconnect()
        assertEquals(null, vpnManager.connectedServerId.value)
    }

    // ── REVIEW-AND-001: the block must not erase the session's verdict ──

    @Test
    fun `a signed-out system start keeps its reason under the block, and signing in dials`() = runTest {
        every { prefs.sessionShouldBeUp } returns true
        every { prefs.lastServerId } returns "srv-1"
        vpnManager.reportHeadlessBlocked(FailureKind.SIGN_IN_REQUIRED)
        // The block, armed on the service's executor, lands a hop later.
        every { BirdoVpnService.killSwitchActive } returns true
        serviceEmits(VpnState.KillSwitchActive)
        runCurrent()
        assertEquals(FailureKind.SIGN_IN_REQUIRED, (vpnManager.state.value as VpnState.Error).kind)

        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        vpnManager.onSignedIn()
        runCurrent()
        coVerify(exactly = 1) { repository.connectVpn("srv-1", any()) }
        quiesce()
    }

    @Test
    fun `an offline boot keeps waiting for the network under the block, and dials when it returns`() = runTest {
        every { prefs.lastServerId } returns "srv-1"
        onlineFlow.value = false
        runCurrent()
        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Error("Unable to resolve host", 0)
        vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON)
        runCurrent()
        val waiting = vpnManager.state.value
        assertTrue("$waiting", waiting is VpnState.Reconnecting && waiting.waitingForNetwork)

        // The block's establish() finishes after the dial failed.
        every { BirdoVpnService.killSwitchActive } returns true
        serviceEmits(VpnState.KillSwitchActive)
        runCurrent()
        assertTrue(vpnManager.state.value is VpnState.Reconnecting)

        coEvery { repository.connectVpn(any(), any()) } returns ApiResult.Success(makeConnectResponse())
        onlineFlow.value = true
        advanceTimeBy(10_000)
        coVerify(exactly = 2) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a session in progress makes a second system start a no-op`() = runTest {
        assertFalse(vpnManager.sessionInProgress())
        connectAndEstablish()
        assertTrue(vpnManager.sessionInProgress())
        assertFalse(vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON))
        quiesce()
    }

    // ── REVIEW-AND2-004: the tap that started the process joins its resume ──

    private fun gatedDial(): CompletableDeferred<ApiResult<ConnectResponse>> {
        every { prefs.lastServerId } returns "srv-1"
        val gate = CompletableDeferred<ApiResult<ConnectResponse>>()
        coEvery { repository.connectVpn(any(), any()) } coAnswers { gate.await() }
        return gate
    }

    /**
     * After a crash, the tile tap is what starts the process, and BirdoApp's
     * resume dials before SystemUI delivers the click. Decided on the resume's
     * Connecting, the tap was a DISCONNECT of the session it had just brought
     * back.
     */
    @Test
    fun `the tile tap that started the process joins its resume instead of disconnecting it`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        val gate = gatedDial()

        assertTrue(vpnManager.connectHeadless(SystemStartKind.PROCESS_RESTART))
        runCurrent()
        now = 600L
        val action = QuickToggle.decide(
            vpnManager.state.value, false, true, true, true,
            joinsResume = vpnManager.claimTapForResume(),
        )
        assertEquals(QuickToggle.Action.NONE, action)
        // One tap per resume: the next one is the user's own decision.
        assertFalse(vpnManager.claimTapForResume())

        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()
        assertTrue(BirdoVpnService.ACTION_START in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_STOP in dispatchedActions)
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    /**
     * The widget's callback read Disconnected and chose CONNECT, and the
     * resume began dialling before that CONNECT ran: it used to supersede the
     * resume only after the resume's /connect had minted a peer.
     */
    @Test
    fun `a widget tap decided before the resume began joins it and mints nothing more`() = runTest {
        val gate = gatedDial()
        assertEquals(
            QuickToggle.Action.CONNECT,
            QuickToggle.decide(VpnState.Disconnected, false, true, true, true, vpnManager.claimTapForResume()),
        )

        vpnManager.connectHeadless(SystemStartKind.PROCESS_RESTART)
        runCurrent()
        vpnManager.requestConnectPreferred()
        runCurrent()
        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()

        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
        // The resume's own peer is the session's: nothing released as superseded.
        coVerify(exactly = 0) { repository.disconnectVpn(any()) }
        assertTrue(BirdoVpnService.ACTION_START in dispatchedActions)
        quiesce()
    }

    @Test
    fun `a tap whose own dial got there first makes the resume a no-op`() = runTest {
        val gate = gatedDial()

        launch { vpnManager.connectPreferred() }
        runCurrent()
        assertFalse(vpnManager.connectHeadless(SystemStartKind.PROCESS_RESTART))
        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()

        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
        quiesce()
    }

    @Test
    fun `a tap past the window acts on the state it sees`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        val gate = gatedDial()

        vpnManager.connectHeadless(SystemStartKind.PROCESS_RESTART)
        runCurrent()
        now = QuickToggle.RESUME_TAP_WINDOW_MS + 1

        assertFalse(vpnManager.claimTapForResume())
        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()
        quiesce()
    }

    @Test
    fun `no other system start claims a tap`() = runTest {
        val gate = gatedDial()

        vpnManager.connectHeadless(SystemStartKind.ALWAYS_ON)
        runCurrent()

        // Always-on starts at boot, unlock or a setting change, never because
        // of a tap: a tap during it is the user's own.
        assertFalse(vpnManager.claimTapForResume())
        gate.complete(ApiResult.Success(makeConnectResponse()))
        runCurrent()
        quiesce()
    }

    /**
     * REVIEW-AND-002: heartbeats stop at a 401, so the backend may reap the
     * peer while the user is signed out. Signing back in restarted the
     * "last good beat" clock, and the reap read as a revoke: block released,
     * no re-dial.
     */
    @Test
    fun `signing in after a 401 judges the next beat against the real gap`() = runTest {
        var now = 0L
        vpnManager.elapsedRealtime = { now }
        vpnManager.ioDispatcher = StandardTestDispatcher(testScheduler)
        connectAndEstablish()
        every { BirdoVpnService.killSwitchActive } returns true
        coEvery { repository.sendHeartbeat(any()) } returns ApiResult.Error("Session expired", 401)
        now = 60_000L
        advanceTimeBy(61_000)
        assertTrue(vpnManager.sessionExpired.value)

        // Signed back in seven minutes later; the server reaped the peer meanwhile.
        now = 7 * 60_000L
        coEvery { repository.sendHeartbeat(any()) } returns
            ApiResult.Success(app.birdo.vpn.data.model.HeartbeatResponse(valid = false))
        vpnManager.onSignedIn()
        advanceTimeBy(61_000)

        assertTrue("rebuilt behind the block", BirdoVpnService.ACTION_KILL_SWITCH_BLOCK in dispatchedActions)
        assertFalse("never the revoke teardown", BirdoVpnService.ACTION_STOP in dispatchedActions)
        quiesce()
    }

    // ── A1-034: the in-place live rebuild ───────────────────────────────

    private fun rebuildAnswers(result: ApiResult<ConnectResponse>) {
        coEvery {
            repository.connectVpn(
                serverNodeId = any(), deviceName = any(), stealthMode = any(), fallbackReason = any(),
                quantumProtection = any(), pqClientPublicKey = any(), integrityToken = any(),
                dnsFiltering = any(), rebuildOf = "key-123",
            )
        } returns result
    }

    private fun rebuiltConfig(deferredKeyId: String? = "key-123") =
        makeConnectResponse().copy(keyId = "key-456", deferredKeyId = deferredKeyId)

    @Test
    fun `a switch rides the live tunnel, swaps in place, and releases the old key only after the new peer answers`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))

        val switch = async { vpnManager.connect("srv-2") }
        runCurrent()
        // Nothing torn down, nothing released, the swap handed to the service.
        assertFalse(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        assertTrue(BirdoVpnService.ACTION_LIVE_REBUILD in dispatchedActions)
        coVerify(exactly = 0) { repository.disconnectVpn(any()) }
        assertEquals(VpnState.Connected, vpnManager.state.value)

        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.NEW_PEER_HANDSHAKED)
        runCurrent()

        assertTrue(switch.await() is ApiResult.Success)
        coVerify(exactly = 1) { repository.disconnectVpn("key-123") }
        verify { prefs.lastServerId = "srv-2" }
        assertEquals("srv-2", vpnManager.connectedServerId.value)
        quiesce()
    }

    @Test
    fun `a switch whose request fails keeps the live session untouched and says so`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Error("Couldn't reach BirdoVPN.", 0))

        val result = vpnManager.connect("srv-2")

        assertTrue(result is ApiResult.Error)
        assertTrue((result as ApiResult.Error).message.endsWith(SessionCopy.STILL_ON_PREVIOUS))
        assertEquals(VpnState.Connected, vpnManager.state.value)
        assertFalse(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK in dispatchedActions)
        coVerify(exactly = 0) { repository.disconnectVpn(any()) }
        quiesce()
    }

    /**
     * REVIEW-AND2-003: a tunnel that handshakes but carries no TLS (an MTU
     * black hole) answers nothing through itself, so a rebuild there used to
     * keep the broken session forever — the switch, and the MTU change that
     * would fix it, impossible. No answer now takes today's path: teardown
     * behind the block, then a fresh dial around the tunnel.
     */
    @Test
    fun `a switch the live tunnel cannot carry falls back to the teardown path`() = runTest {
        connectAndEstablish()
        rebuildAnswers(
            ApiResult.Error("Couldn't reach BirdoVPN.", 0, app.birdo.vpn.data.repository.FailureReason.UNREACHABLE),
        )
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.connect("srv-2")

        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_LIVE_REBUILD in dispatchedActions)
        // The old key is released by the teardown, and the switch is dialled fresh.
        coVerify(exactly = 1) { repository.disconnectVpn("key-123") }
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
        quiesce()
    }

    @Test
    fun `a settings change the live tunnel cannot carry is applied through the teardown path`() = runTest {
        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        connectAndEstablish()
        rebuildAnswers(
            ApiResult.Error("Couldn't reach BirdoVPN.", 0, app.birdo.vpn.data.repository.FailureReason.UNREACHABLE),
        )
        coEvery { repository.connectVpn("srv-1", any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.requestSettingsReapply()
        advanceTimeBy(10_000)

        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        // The original dial and the rebuild's fresh one.
        coVerify(exactly = 2) { repository.connectVpn("srv-1", any()) }
        quiesce()
    }

    @Test
    fun `a swap the service refuses before touching the tunnel keeps the session and gives the new key back`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))

        val switch = async { vpnManager.connect("srv-2") }
        runCurrent()
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.FAILED_BEFORE_SWAP)
        runCurrent()

        assertEquals(SessionCopy.SWITCH_KEPT_PREVIOUS, (switch.await() as ApiResult.Error).message)
        coVerify(exactly = 1) { repository.disconnectVpn("key-456") }
        coVerify(exactly = 0) { repository.disconnectVpn("key-123") }
        verify { repository.rememberKeyId("key-123") }
        quiesce()
    }

    @Test
    fun `a rebuild the service finds no session for takes today's path and gives both keys back`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())

        val switch = async { vpnManager.connect("srv-2") }
        runCurrent()
        // The server minted key-456 and held key-123 back; the device has no
        // live session left to move.
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.NO_LIVE_SESSION)
        runCurrent()
        switch.await()

        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        coVerify(exactly = 1) { repository.disconnectVpn("key-456") }
        // The teardown releases the session key, put back to the old one: as
        // CANNOT_REBUILD_HERE it released key-456 a second time and left
        // key-123 out until the stale sweep (second-pass review, NEW-2).
        coVerify(exactly = 1) { repository.disconnectVpn("key-123") }
        quiesce()
    }

    // ── NEW-1: one recovery when the old tunnel dies under a live rebuild ──

    @Test
    fun `a switch whose old tunnel dies during the rebuild recovers once, and stays wanted`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())

        val switch = async { vpnManager.connect("srv-2") }
        runCurrent()
        // The dead-tunnel handler's Error lands while the swap is with the
        // service, which then finds no session to move.
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.NO_LIVE_SESSION)
        runCurrent()
        switch.await()
        advanceTimeBy(30_000)

        // The supervisor used to take the Error first: a user switch had not
        // connected yet, so it gave up as NEVER_CONNECTED and cleared the
        // intent; the legacy dial then ran on a session nobody wanted.
        verify(exactly = 0) { prefs.sessionShouldBeUp = false }
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
        quiesce()
    }

    @Test
    fun `a settings rebuild whose old tunnel dies does not race a second recovery dial`() = runTest {
        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))
        coEvery { repository.connectVpn("srv-1", any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.requestSettingsReapply()
        advanceTimeBy(1_500)
        assertTrue(BirdoVpnService.ACTION_LIVE_REBUILD in dispatchedActions)
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.NO_LIVE_SESSION)
        advanceTimeBy(30_000)

        // The first dial and today's path — not a supervisor re-dial ~2 s
        // later racing it (two STARTs, two peers).
        coVerify(exactly = 2) { repository.connectVpn("srv-1", any()) }
        quiesce()
    }

    @Test
    fun `overlapping rebuilds keep the hold with the newer one`() = runTest {
        connectAndEstablish()
        val firstRequest = CompletableDeferred<ApiResult<ConnectResponse>>()
        val secondRequest = CompletableDeferred<ApiResult<ConnectResponse>>()
        var requests = 0
        coEvery {
            repository.connectVpn(
                serverNodeId = any(), deviceName = any(), stealthMode = any(), fallbackReason = any(),
                quantumProtection = any(), pqClientPublicKey = any(), integrityToken = any(),
                dnsFiltering = any(), rebuildOf = "key-123",
            )
        } coAnswers { if (requests++ == 0) firstRequest.await() else secondRequest.await() }
        coEvery { repository.connectVpn("srv-3", any()) } returns ApiResult.Success(makeConnectResponse())

        // Tap A, then B while A's /connect is still out.
        val a = async { vpnManager.connect("srv-2") }
        runCurrent()
        val b = async { vpnManager.connect("srv-3") }
        runCurrent()
        // A comes back (superseded by B) and finishes first.
        firstRequest.complete(ApiResult.Success(rebuiltConfig()))
        runCurrent()
        a.await()
        // The old tunnel dies in B's window, and B's swap finds no session.
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        secondRequest.complete(ApiResult.Success(rebuiltConfig().copy(keyId = "key-789")))
        runCurrent()
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.NO_LIVE_SESSION)
        runCurrent()
        b.await()

        // A's finally used to clear the shared flag: B's Error reached the
        // supervisor (NEVER_CONNECTED, the intent cleared), and B's legacy
        // dial then ran on a session nobody wanted (final review, #1).
        verify(exactly = 0) { prefs.sessionShouldBeUp = false }
        coVerify(exactly = 1) { repository.connectVpn("srv-3", any()) }
        quiesce()
    }

    @Test
    fun `a failure held during a rebuild that keeps the session is recovered after all`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))
        coEvery { repository.connectVpn("srv-1", any()) } returns ApiResult.Success(makeConnectResponse())

        val switch = async { vpnManager.connect("srv-2") }
        runCurrent()
        serviceEmits(VpnState.Error("Connection lost. Reconnecting…", FailureKind.DIED_AFTER_HANDSHAKE))
        runCurrent()
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.FAILED_BEFORE_SWAP)
        runCurrent()
        switch.await()
        advanceTimeBy(30_000)

        // Kept, but dead: the supervisor re-dials it.
        coVerify(atLeast = 2) { repository.connectVpn("srv-1", any()) }
        quiesce()
    }

    @Test
    fun `a server that cannot defer the live key takes today's teardown path`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(makeConnectResponse(success = false).copy(rebuildRefused = "unknown-current-key")))
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.connect("srv-2")

        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        coVerify(exactly = 1) { repository.connectVpn("srv-2", any()) }
        quiesce()
    }

    @Test
    fun `a deferral that was not honoured gives the new key back before today's path`() = runTest {
        connectAndEstablish()
        rebuildAnswers(ApiResult.Success(rebuiltConfig(deferredKeyId = null)))
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.connect("srv-2")

        coVerify { repository.disconnectVpn("key-456") }
        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        quiesce()
    }

    @Test
    fun `Stealth is never rebuilt in place`() = runTest {
        connectAndEstablish()
        every { BirdoVpnService.stealthActive } returns true
        coEvery { repository.connectVpn("srv-2", any()) } returns ApiResult.Success(makeConnectResponse())

        vpnManager.connect("srv-2")

        assertFalse(BirdoVpnService.ACTION_LIVE_REBUILD in dispatchedActions)
        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        quiesce()
    }

    // ── A1-024: BirdoShield is never claimed for a tunnel that does not use it ──

    @Test
    fun `with Custom DNS on, the server is not told BirdoShield is on`() = runTest {
        every { prefs.dnsFilteringEnabled } returns true
        every { prefs.customDnsEnabled } returns true
        coEvery { repository.connectVpn(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Success(makeConnectResponse())

        vpnManager.connect("srv-1")

        coVerify {
            repository.connectVpn(
                serverNodeId = "srv-1", deviceName = any(), stealthMode = any(), fallbackReason = any(),
                quantumProtection = any(), pqClientPublicKey = any(), integrityToken = any(),
                dnsFiltering = false, rebuildOf = any(),
            )
        }
    }

    // ── A1-031: "prefer Stealth" only after Stealth actually worked ─────────

    private fun emitTransportBlocked() {
        assertTrue(transportBlocked.tryEmit(Unit))
    }

    private suspend fun TestScope.dialThenFallBack() {
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Success(makeConnectResponse().copy(stealthEnabled = true))
        vpnManager.connect("srv-1")
        // The probe found no handshake on plain WireGuard.
        emitTransportBlocked()
        advanceTimeBy(6_000)
    }

    @Test
    fun `a stealth fallback that never connects does not steer the next 24 h onto Stealth`() = runTest {
        try {
            dialThenFallBack()
            // The server granted stealth, and then nothing handshaked.
            serviceEmits(VpnState.Error(SessionCopy.NO_TUNNEL, FailureKind.NEVER_ESTABLISHED))
            advanceTimeBy(70_000)

            verify(exactly = 0) { prefs.stealthPreferredSince = any() }
        } finally {
            quiesce()
        }
    }

    @Test
    fun `a stealth fallback that connects over Stealth is remembered`() = runTest {
        try {
            dialThenFallBack()
            every { BirdoVpnService.stealthActive } returns true
            serviceEmits(VpnState.Connected)
            advanceTimeBy(1_000)

            verify(exactly = 1) { prefs.stealthPreferredSince = any() }
        } finally {
            // A failed check must not leave a live session re-dialling forever
            // under runTest's final drain.
            quiesce()
        }
    }

    // ── REVIEW-AND-018 ──────────────────────────────────────────────────

    @Test
    fun `a settings change that ends a kill-switch-off session ends its intent too`() = runTest {
        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        every { prefs.killSwitchEnabled } returns false
        connectAndEstablish()
        // The server cannot rebuild in place, and the rebuild's fresh dial fails.
        rebuildAnswers(ApiResult.Success(makeConnectResponse(success = false).copy(rebuildRefused = "unknown-current-key")))
        coEvery { repository.connectVpn("srv-1", any()) } returns ApiResult.Error("timeout", 0)

        vpnManager.requestSettingsReapply()
        advanceTimeBy(60_000)

        // "Couldn't apply settings - disconnected." is the end of the session:
        // nothing (an app update, a reboot) may bring it back.
        assertTrue(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        verify { prefs.sessionShouldBeUp = false }
        quiesce()
    }

    /**
     * REVIEW-AND2-012: Quantum turned on while connected, and the server
     * answered the in-place rebuild with an error: the session is kept, and
     * nobody asked to switch — "Couldn't switch… previous location" misread it.
     */
    @Test
    fun `a settings change that keeps the session says so, not that a switch failed`() = runTest {
        val toasts = mutableListOf<String>()
        mockkStatic(android.widget.Toast::class)
        every { android.widget.Toast.makeText(any(), any<CharSequence>(), any()) } answers {
            toasts += secondArg<CharSequence>().toString()
            mockk(relaxed = true)
        }
        connectAndEstablish()
        rebuildAnswers(ApiResult.Error("The server is busy.", 502))

        vpnManager.requestSettingsReapply()
        advanceTimeBy(5_000)

        assertTrue("toasts: $toasts", "The server is busy. ${SessionCopy.CONNECTION_UNCHANGED}" in toasts)
        assertTrue(toasts.none { it.contains("switch") || it.contains("location") })
        assertEquals(VpnState.Connected, vpnManager.state.value)
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

    /**
     * REVIEW-AND2-002: the deletion's success now arrives while connected, and
     * the server has already revoked this device's peer. The tunnel comes down
     * on that success (A2-005), with no DELETE for a key the server removed and
     * no re-dial when the dead tunnel's own error lands afterwards.
     */
    @Test
    fun `a confirmed account deletion tears the session down without releasing or re-dialling`() = runTest {
        connectAndEstablish()

        vpnManager.onAccountDeleted()
        runCurrent()
        assertEquals(BirdoVpnService.ACTION_STOP, dispatchedActions.last())
        verify { prefs.sessionShouldBeUp = false }

        tunnelDies()
        advanceTimeBy(30 * 60_000L)
        coVerify(exactly = 0) { repository.disconnectVpn(any()) }
        coVerify(exactly = 0) { repository.sendHeartbeat(any(), true) }
        coVerify(exactly = 1) { repository.connectVpn(any(), any()) }
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

    // ── P2-2: the not-armed warning ends with the user's Disconnect ──────

    @Test
    fun `a user Disconnect clears the kill-switch warning at once`() = runTest {
        val field = BirdoVpnService::class.java.getDeclaredField("_killSwitchNotArmedFlow")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val notArmed = field.get(null) as MutableStateFlow<String?>
        notArmed.value = SessionCopy.KILL_SWITCH_NOT_ARMED
        try {
            vpnManager.disconnect()

            // Not only when the service's stop lands: Home must not keep
            // saying it over the user's own Disconnect.
            assertNull(BirdoVpnService.killSwitchNotArmedFlow.value)
        } finally {
            notArmed.value = null
        }
    }

    // ── Stealth on a plan without it (review of #463, P1 on main) ────────

    /** The boolean extras of every Intent VpnManager builds. */
    private fun recordBooleanExtras(): MutableMap<String, Boolean> {
        val extras = mutableMapOf<String, Boolean>()
        every { anyConstructed<Intent>().putExtra(any<String>(), any<Boolean>()) } answers {
            extras[firstArg()] = secondArg()
            self as Intent
        }
        return extras
    }

    @Test
    fun `the stored Stealth setting is asked for, whatever a stale plan says`() = runTest {
        // Re-upgraded on the web while the app slept: lastKnownPlan still says
        // RECON. The server is the one fresh authority on the plan.
        every { prefs.stealthModeEnabled } returns true
        every { prefs.lastKnownPlan } returns "RECON"
        val extras = recordBooleanExtras()
        coEvery { repository.connectVpn(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Success(makeConnectResponse().copy(stealthEnabled = true, xrayEndpoint = "203.0.113.7:443"))

        vpnManager.connect("srv-1")

        // A plan gate here dialled direct, with no notice, a user who had
        // just paid for Stealth (second review of #463, N4).
        coVerify { repository.connectVpn(serverNodeId = "srv-1", deviceName = any(), stealthMode = true, fallbackReason = any(), quantumProtection = any(), pqClientPublicKey = any(), integrityToken = any(), dnsFiltering = any(), rebuildOf = any()) }
        assertEquals(true, extras[BirdoVpnService.EXTRA_STEALTH_REQUESTED])
        assertNull(vpnManager.stealthNotice.value)
    }

    @Test
    fun `a Stealth request the server turns down for the plan connects, and Home is told`() = runTest {
        // A downgraded user: the stored setting is still on (nothing clears
        // it), the dial asks, and the backend connects it WITHOUT Stealth
        // (birdo-web vpn.service.ts).
        every { prefs.stealthModeEnabled } returns true
        every { prefs.lastKnownPlan } returns "RECON"
        val extras = recordBooleanExtras()
        coEvery { repository.connectVpn(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Success(makeConnectResponse().copy(stealthEnabled = false, stealthUnavailableReason = "entitlement"))

        val result = vpnManager.connect("srv-1")

        assertTrue(result is ApiResult.Success)
        assertEquals(true, extras[BirdoVpnService.EXTRA_STEALTH_REQUESTED])
        assertEquals(SessionCopy.STEALTH_NOT_IN_PLAN, vpnManager.stealthNotice.value)

        vpnManager.disconnect()
        assertNull(vpnManager.stealthNotice.value)
    }

    @Test
    fun `a Multi-Hop dial carries the server's Stealth downgrade to the service and to Home`() = runTest {
        every { prefs.stealthModeEnabled } returns true
        coEvery { repository.connectMultiHop(any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Success(makeMultiHopResponse().copy(stealthEnabled = false, stealthUnavailableReason = "entitlement"))

        val result = vpnManager.connectMultiHop("de-1", "nl-1")

        assertTrue(result is ApiResult.Success)
        // toConnectResponse() dropped the reason: the service then read the
        // downgrade as "not granted" and refused (second review of #463, N8).
        verify { BirdoVpnService.setConfig(match { it.stealthUnavailableReason == "entitlement" }) }
        assertEquals(SessionCopy.STEALTH_NOT_IN_PLAN, vpnManager.stealthNotice.value)
    }

    // ── NEW-5: a downgraded user's way out ───────────────────────────────

    /** Connected on a dial the server answered "Stealth is not in your plan". */
    private suspend fun TestScope.connectDowngraded() {
        every { prefs.stealthModeEnabled } returns true
        every { prefs.lastServerId } returns "srv-1"
        coEvery { repository.connectVpn(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ApiResult.Success(makeConnectResponse().copy(stealthEnabled = false, stealthUnavailableReason = "entitlement"))
        vpnManager.connect("srv-1")
        serviceEmits(VpnState.Connected)
        runCurrent()
        assertEquals(SessionCopy.STEALTH_NOT_IN_PLAN, vpnManager.stealthNotice.value)
    }

    @Test
    fun `a downgraded session's switch still rides the live rebuild`() = runTest {
        connectDowngraded()
        rebuildAnswers(ApiResult.Success(rebuiltConfig()))

        val switch = async { vpnManager.connect("srv-2") }
        runCurrent()

        // Its stored Stealth setting made it "Stealth-wanted", so every switch
        // went through the legacy teardown: a blackout each time.
        assertTrue(BirdoVpnService.ACTION_LIVE_REBUILD in dispatchedActions)
        assertFalse(BirdoVpnService.ACTION_SWITCH_TEARDOWN in dispatchedActions)
        BirdoVpnService.completeLiveRebuild(1L, LiveRebuildPolicy.Event.NEW_PEER_HANDSHAKED)
        runCurrent()
        switch.await()
        quiesce()
    }

    @Test
    fun `the notice's action turns Stealth off and ends the notice`() = runTest {
        connectDowngraded()

        vpnManager.turnOffStealthNotInPlan()

        verify { prefs.stealthModeEnabled = false }
        assertNull(vpnManager.stealthNotice.value)
        quiesce()
    }
}
