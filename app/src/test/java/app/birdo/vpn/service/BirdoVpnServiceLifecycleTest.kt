package app.birdo.vpn.service

import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import app.birdo.vpn.data.auth.TokenManager
import app.birdo.vpn.data.model.ConnectResponse
import app.birdo.vpn.data.preferences.AppPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * BirdoVpnService's lifecycle, driven on a bare instance (android.jar stubs
 * are non-throwing here, as in KillSwitchOrderingTest) with the framework
 * seams mocked: the notification manager, the preferences, VpnManager behind
 * the Hilt entry point, and VpnService.Builder.
 */
class BirdoVpnServiceLifecycleTest {

    private companion object {
        const val OWN_PACKAGE = "app.birdo.vpn"
        const val PRIVATE_KEY = "cHJpdmF0ZS1rZXktMzItYnl0ZXMtLS0tLS0tLS0tLS0="
        const val SERVER_KEY = "c2VydmVyLWtleS0zMi1ieXRlcy0tLS0tLS0tLS0tLS0="
    }

    private lateinit var service: BirdoVpnService
    private lateinit var prefs: AppPreferences
    private lateinit var notifications: VpnNotificationManager
    private lateinit var manager: VpnManager
    private lateinit var tokens: TokenManager

    @Before
    fun setup() {
        mockkConstructor(VpnService.Builder::class)
        val builder = { self: Any -> self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setSession(any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().setMtu(any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().setMetered(any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().setUnderlyingNetworks(any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().addAddress(any<String>(), any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().addDnsServer(any<String>()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().addDnsServer(any<java.net.InetAddress>()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().setBlocking(any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().addDisallowedApplication(any()) } answers { builder(self) }
        every { anyConstructed<VpnService.Builder>().establish() } returns mockk<ParcelFileDescriptor>(relaxed = true)

        mockkObject(WgNative)
        every { WgNative.turnOff(any()) } just Runs

        mockkStatic(VpnService::class)
        every { VpnService.prepare(any()) } returns null

        prefs = mockk(relaxed = true)
        notifications = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        tokens = mockk(relaxed = true)
        every { manager.connectHeadless() } returns true
        every { manager.state } returns kotlinx.coroutines.flow.MutableStateFlow(VpnState.Disconnected)
        every { manager.switching } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { manager.sessionExpired } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { manager.activeMultiHopRoute } returns null

        // A spy, so the stub's null getApplicationContext() can be answered.
        service = spyk(BirdoVpnService())
        every { service.applicationContext } returns mockk(relaxed = true)
        every { service.packageName } returns OWN_PACKAGE
        setLazy("notifManager", notifications)
        setLazy("appPrefs", prefs)
        val entryPoint = mockk<VpnManagerEntryPoint> {
            every { vpnManager() } returns manager
            every { tokenManager() } returns tokens
        }
        service.entryPointProvider = { entryPoint }
        // The spy copied the original's lazy, which would ask the original's provider.
        setLazy("entryPoint", entryPoint)
    }

    @After
    fun tearDown() {
        unmockkAll()
        setKillSwitchEnabled(true)
        val updateState = BirdoVpnService.Companion::class.java
            .getDeclaredMethod("updateState", VpnState::class.java)
        updateState.isAccessible = true
        updateState.invoke(BirdoVpnService.Companion, VpnState.Disconnected)
        // Companion flows are compiled onto the outer class; a tunnel this
        // class brought up must not leak into the next test class.
        mapOf(
            "_killSwitchActiveFlow" to false,
            "_connectedServerFlow" to null,
            "_connectedSinceFlow" to 0L,
            "_publicIpFlow" to null,
            "_rxBytesFlow" to 0L,
            "_txBytesFlow" to 0L,
            "_stealthActiveFlow" to false,
            "_quantumActiveFlow" to false,
        ).forEach { (name, initial) ->
            val flowField = BirdoVpnService::class.java.getDeclaredField(name)
            flowField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            (flowField.get(null) as kotlinx.coroutines.flow.MutableStateFlow<Any?>).value = initial
        }
    }

    /** Kotlin compiles `by lazy` to a `<name>$delegate` field holding the Lazy. */
    private fun setLazy(name: String, value: Any) {
        val field = BirdoVpnService::class.java.getDeclaredField("$name\$delegate")
        field.isAccessible = true
        field.set(service, lazyOf(value))
    }

    private fun field(name: String): Any? {
        val f = BirdoVpnService::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(service)
    }

    private fun setField(name: String, value: Any) {
        val f = BirdoVpnService::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(service, value)
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (!condition()) {
            assertTrue("timed out waiting for $what", System.currentTimeMillis() < deadline)
            Thread.sleep(10)
        }
    }

    private fun intentWith(action: String?): Intent = mockk(relaxed = true) {
        every { this@mockk.action } returns action
    }

    // ── A1-014: Always-on ────────────────────────────────────────────────

    @Test
    fun `an Always-on start arms the block first, then connects headlessly, once`() {
        every { prefs.killSwitchEnabled } returns true
        every { prefs.sessionShouldBeUp } returns false
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokens.isLoggedIn() } returns true

        val result = service.onStartCommand(intentWith(VpnService.SERVICE_INTERFACE), 0, 1)

        // Not sticky any more: see the next section.
        assertEquals(Service.START_NOT_STICKY, result)
        verify(exactly = 1) { manager.connectHeadless() }
        waitFor("the block") { BirdoVpnService.killSwitchActive }
        // The foreground notification of a start that connects says Connecting
        // because it IS connecting — never for a start that connects nothing.
        verify { notifications.buildForegroundNotification(state = VpnState.Connecting, body = any(), killSwitchActive = true) }
    }

    @Test
    fun `a signed-out Always-on start keeps the block and says why instead of connecting`() {
        every { prefs.killSwitchEnabled } returns true
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokens.isLoggedIn() } returns false

        service.onStartCommand(intentWith(VpnService.SERVICE_INTERFACE), 0, 1)

        verify(exactly = 0) { manager.connectHeadless() }
        verify(exactly = 1) { manager.reportHeadlessBlocked(FailureKind.SIGN_IN_REQUIRED) }
        waitFor("the block") { BirdoVpnService.killSwitchActive }
    }

    @Test
    fun `a sticky restart with nothing wanted and the kill switch off stops without a trace`() {
        every { prefs.killSwitchEnabled } returns false
        every { prefs.sessionShouldBeUp } returns false

        val result = service.onStartCommand(null, 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        verify(exactly = 0) { manager.connectHeadless() }
        verify(exactly = 0) { anyConstructed<VpnService.Builder>().establish() }
    }

    @Test
    fun `an app update restores a session the user wanted`() {
        every { prefs.killSwitchEnabled } returns false
        every { prefs.sessionShouldBeUp } returns true
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokens.isLoggedIn() } returns true

        service.onStartCommand(intentWith(BirdoVpnService.ACTION_HEADLESS_CONNECT), 0, 1)

        verify(exactly = 1) { manager.connectHeadless() }
    }

    // ── Process death (live, API 35, 2026-09-30) ────────────────────────

    @Test
    fun `a new process that finds the session dead resumes it, block first, saying Reconnecting`() {
        every { prefs.killSwitchEnabled } returns true
        every { prefs.sessionShouldBeUp } returns true
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokens.isLoggedIn() } returns true

        val result = service.onStartCommand(intentWith(BirdoVpnService.ACTION_RESUME_SESSION), 0, 1)

        assertEquals(Service.START_NOT_STICKY, result)
        verify(exactly = 1) { manager.connectHeadless() }
        waitFor("the block") { BirdoVpnService.killSwitchActive }
        // The dead process's "Protected" is replaced from the first frame.
        verify {
            notifications.buildForegroundNotification(
                state = VpnState.Reconnecting(1),
                body = any(),
                killSwitchActive = true,
            )
        }
    }

    @Test
    fun `no command asks Android to restart a dead service with a stale notification`() {
        // START_STICKY bought no restart on API 35 and kept the dead service's
        // "Protected" notification on screen; BirdoApp resumes instead.
        listOf(
            BirdoVpnService.ACTION_UPDATE_SETTINGS,
            BirdoVpnService.ACTION_USER_RECONNECT,
            BirdoVpnService.ACTION_RELEASE_BLOCK,
        ).forEach { action ->
            assertEquals(action, Service.START_NOT_STICKY, service.onStartCommand(intentWith(action), 0, 1))
        }
    }

    @Test
    fun `a second system start while the first one's session is in flight changes nothing`() {
        every { prefs.killSwitchEnabled } returns true
        every { prefs.sessionShouldBeUp } returns true
        every { prefs.hasAcceptedCurrentConsent } returns true
        every { tokens.isLoggedIn() } returns true
        every { manager.sessionInProgress() } returns true
        val generation = field("transitionGen") as AtomicLong
        val before = generation.get()

        service.onStartCommand(intentWith(VpnService.SERVICE_INTERFACE), 0, 1)

        // REVIEW-AND-022: no second block arm to supersede the first dial.
        assertEquals(before, generation.get())
        verify(exactly = 0) { manager.connectHeadless() }
        verify(exactly = 0) { anyConstructed<VpnService.Builder>().establish() }
    }

    @Test
    fun `releasing the block of a session nobody wants any more stops the service`() {
        every { prefs.sessionShouldBeUp } returns false
        BirdoVpnService::class.java.getDeclaredMethod("handleReleaseBlock").apply { isAccessible = true }.invoke(service)
        // REVIEW-AND-004: it used to stay foreground, and come back after a
        // process kill as a block nobody asked for.
        verify(exactly = 1) { service.stopSelf() }

        every { prefs.sessionShouldBeUp } returns true
        BirdoVpnService::class.java.getDeclaredMethod("handleReleaseBlock").apply { isAccessible = true }.invoke(service)
        // A spent budget keeps the process for the cooldown's re-dial.
        verify(exactly = 1) { service.stopSelf() }
    }

    @Test
    fun `Android's lockdown is read on every command, not only at a system start`() {
        every { service.isLockdownEnabled } returns true
        service.onStartCommand(intentWith(BirdoVpnService.ACTION_UPDATE_SETTINGS), 0, 1)
        assertTrue(BirdoVpnService.lockdownActive)

        // The user turned "Block connections without VPN" off; no system start follows.
        every { service.isLockdownEnabled } returns false
        service.onStartCommand(intentWith(BirdoVpnService.ACTION_RELEASE_BLOCK), 0, 1)
        // REVIEW-AND-005: a give-up must not claim Android still blocks.
        assertFalse(BirdoVpnService.lockdownActive)
    }

    // ── A1-009: the notification's Disconnect goes through VpnManager ────

    @Test
    fun `the notification's Disconnect asks VpnManager, it does not stop the tunnel itself`() {
        service.onStartCommand(intentWith(BirdoVpnService.ACTION_USER_DISCONNECT), 0, 1)
        verify(exactly = 1) { manager.requestDisconnect() }
    }

    // ── A1-012: serialised transitions ───────────────────────────────────

    @Test
    fun `a Disconnect that lands during stealth setup abandons it without a block or an Error`() {
        every { prefs.stealthModeEnabled } returns true
        every { prefs.quantumProtectionEnabled } returns false
        mockkObject(XrayManager)
        every { XrayManager.isAvailable(any()) } returns true
        every { XrayManager.getLocalPort() } returns 10808
        every { XrayManager.stop() } just Runs
        val generation = field("transitionGen") as AtomicLong
        // The STOP arrives on the main thread while Xray is starting.
        coEvery { XrayManager.start(any(), any(), any(), any()) } answers { generation.incrementAndGet(); true }
        BirdoVpnService.setConfig(
            ConnectResponse(
                success = true,
                privateKey = "cHJpdmF0ZS1rZXktMzItYnl0ZXMtLS0tLS0tLS0tLS0=",
                serverPublicKey = "c2VydmVyLWtleS0zMi1ieXRlcy0tLS0tLS0tLS0tLS0=",
                endpoint = "1.2.3.4:51820",
                assignedIp = "10.100.0.2",
                stealthEnabled = true,
                xrayEndpoint = "1.2.3.4:443",
            ),
        )
        every { anyConstructed<VpnService.Builder>().establish() } returns null

        val startTunnel = BirdoVpnService::class.java.getDeclaredMethod("startTunnel", Long::class.javaPrimitiveType)
        startTunnel.isAccessible = true
        startTunnel.invoke(service, generation.get())

        verify(exactly = 0) { anyConstructed<VpnService.Builder>().establish() }
        assertFalse(BirdoVpnService.currentState is VpnState.Error)
        verify { XrayManager.stop() }
    }

    @Test
    fun `nothing establishes a block on a destroyed service`() {
        setField("destroyed", true)
        val activate = BirdoVpnService::class.java.getDeclaredMethod("activateKillSwitch")
        activate.isAccessible = true

        activate.invoke(service)

        verify(exactly = 0) { anyConstructed<VpnService.Builder>().establish() }
        assertFalse(BirdoVpnService.killSwitchActive)
    }

    // ── A1-029: another VPN took over ────────────────────────────────────

    @Test
    fun `a revoke ends in a typed reason and an alert, not an ordinary disconnect`() {
        service.onRevoke()

        waitFor("the revoke") { BirdoVpnService.currentState is VpnState.Error }
        val error = BirdoVpnService.currentState as VpnState.Error
        assertEquals(FailureKind.VPN_TAKEN_OVER, error.kind)
        assertEquals(SessionCopy.VPN_TAKEN_OVER, error.message)
        verify(timeout = 3_000) { notifications.postAlert(any()) }
        verify(exactly = 0) { notifications.postDisconnectedNotification() }
    }

    // ── A1-013: meteredness ──────────────────────────────────────────────

    private fun buildVpnInterface(config: ConnectResponse, stealth: Boolean) {
        val build = BirdoVpnService::class.java.getDeclaredMethod(
            "buildVpnInterface",
            ConnectResponse::class.java,
            Boolean::class.javaPrimitiveType,
        )
        build.isAccessible = true
        build.invoke(service, config, stealth)
    }

    private val plainConfig = ConnectResponse(
        success = true,
        assignedIp = "10.100.0.2",
        dns = listOf("1.1.1.1"),
        allowedIps = listOf("0.0.0.0/0", "::/0"),
    )

    @Test
    fun `the tunnel inherits the meteredness of the network under it`() {
        buildVpnInterface(plainConfig, stealth = false)

        verify(exactly = 1) { anyConstructed<VpnService.Builder>().setMetered(false) }
        // No underlying network is declared: the system default network is
        // the one wg-go's protected socket uses. Naming the app's "active"
        // network would name the VPN itself now that the app is inside it.
        verify(exactly = 0) { anyConstructed<VpnService.Builder>().setUnderlyingNetworks(any()) }
    }

    // ── D-6 (A1-016): the app's own traffic ─────────────────────────────

    @Test
    fun `the app is inside its own tunnel but outside the kill-switch block`() {
        buildVpnInterface(plainConfig, stealth = false)
        verify(exactly = 0) { anyConstructed<VpnService.Builder>().addDisallowedApplication(OWN_PACKAGE) }

        val activate = BirdoVpnService::class.java.getDeclaredMethod("activateKillSwitch")
        activate.isAccessible = true
        activate.invoke(service)
        verify(exactly = 1) { anyConstructed<VpnService.Builder>().addDisallowedApplication(OWN_PACKAGE) }
    }

    @Test
    fun `Stealth keeps the app out of the tunnel and carves Xray's server out of the routes`() {
        buildVpnInterface(
            plainConfig.copy(endpoint = "127.0.0.1:51821", stealthEnabled = true, xrayEndpoint = "203.0.113.7:8443"),
            stealth = true,
        )

        verify(exactly = 1) { anyConstructed<VpnService.Builder>().addDisallowedApplication(OWN_PACKAGE) }
        // API 29-32 (the unit-test SDK level is 0): the default route is split
        // around the server, so it is never added whole.
        verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("0.0.0.0", 0) }
        verify(exactly = 32) {
            anyConstructed<VpnService.Builder>().addRoute(match<String> { !it.contains(':') }, any())
        }
        verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute("203.0.113.6", 32) }
        verify(exactly = 0) { anyConstructed<VpnService.Builder>().addRoute("203.0.113.7", 32) }
        verify(exactly = 1) { anyConstructed<VpnService.Builder>().addRoute("::", 0) }
    }

    /** The service-wide kill-switch flag, normally captured from the START intent. */
    private fun setKillSwitchEnabled(enabled: Boolean) {
        val field = BirdoVpnService::class.java.getDeclaredField("isKillSwitchEnabled")
        field.isAccessible = true
        field.setBoolean(null, enabled)
    }

    /** Arrange a plain WireGuard setup that reaches wgTurnOn; returns the call order. */
    private fun arrangeTunnelStart(protectSucceeds: Boolean): MutableList<String> {
        val order = mutableListOf<String>()
        setKillSwitchEnabled(true)
        every { prefs.stealthModeEnabled } returns false
        every { prefs.quantumProtectionEnabled } returns false
        every { prefs.killSwitchEnabled } returns true
        every { WgNative.init() } returns true
        every { WgNative.turnOn(any(), any(), any()) } answers { order += "turnOn"; 7 }
        every { WgNative.getSocketV4(7) } returns 41
        every { WgNative.getSocketV6(7) } returns 42
        every { service.protect(any<Int>()) } answers { order += "protect:${firstArg<Int>()}"; protectSucceeds }
        mockkObject(WireGuardConfigBuilder)
        every { WireGuardConfigBuilder.build(any(), any()) } returns mockk(relaxed = true)
        BirdoVpnService.setConfig(
            ConnectResponse(
                success = true,
                privateKey = PRIVATE_KEY,
                serverPublicKey = SERVER_KEY,
                endpoint = "203.0.113.7:51820",
                assignedIp = "10.100.0.2",
                allowedIps = listOf("0.0.0.0/0", "::/0"),
            ),
        )
        return order
    }

    private fun startTunnel() {
        val generation = field("transitionGen") as AtomicLong
        val startTunnel = BirdoVpnService::class.java.getDeclaredMethod("startTunnel", Long::class.javaPrimitiveType)
        startTunnel.isAccessible = true
        startTunnel.invoke(service, generation.get())
    }

    @Test
    fun `wg-go's sockets are protected once, synchronously, right after wgTurnOn`() {
        val order = arrangeTunnelStart(protectSucceeds = true)

        startTunnel()

        // Done before startTunnel returned — nothing left to a poller (A1-037).
        assertEquals(listOf("turnOn", "protect:41", "protect:42"), order)
        verify(exactly = 1) { service.protect(41) }
        verify(exactly = 1) { service.protect(42) }
        // Let the probe's verdict land before tearDown resets the companion.
        waitFor("the probe verdict") { BirdoVpnService.currentState == VpnState.Connected }
    }

    @Test
    fun `a socket that cannot be protected fails the connect, block first`() {
        val order = arrangeTunnelStart(protectSucceeds = false)

        startTunnel()

        assertEquals("turnOn", order.first())
        val error = BirdoVpnService.currentState as VpnState.Error
        assertEquals(SessionCopy.ENGINE_FAILED, error.message)
        // The block went up (kill switch on) and wg-go came down after it.
        assertTrue(BirdoVpnService.killSwitchActive)
        verify { WgNative.turnOff(7) }
    }
}
