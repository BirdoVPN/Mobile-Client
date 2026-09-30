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
        val updateState = BirdoVpnService.Companion::class.java
            .getDeclaredMethod("updateState", VpnState::class.java)
        updateState.isAccessible = true
        updateState.invoke(BirdoVpnService.Companion, VpnState.Disconnected)
        val flowField = BirdoVpnService::class.java.getDeclaredField("_killSwitchActiveFlow")
        flowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (flowField.get(null) as kotlinx.coroutines.flow.MutableStateFlow<Boolean>).value = false
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

        assertEquals(Service.START_STICKY, result)
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
        every { XrayManager.setVpnService(any()) } just Runs
        every { XrayManager.getLocalPort() } returns 10808
        every { XrayManager.stop() } just Runs
        val generation = field("transitionGen") as AtomicLong
        // The STOP arrives on the main thread while Xray is starting.
        coEvery { XrayManager.start(any(), any()) } answers { generation.incrementAndGet(); true }
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

    @Test
    fun `the tunnel inherits the meteredness of the network under it`() {
        val build = BirdoVpnService::class.java.getDeclaredMethod("buildVpnInterface", ConnectResponse::class.java)
        build.isAccessible = true

        build.invoke(
            service,
            ConnectResponse(
                success = true,
                assignedIp = "10.100.0.2",
                dns = listOf("1.1.1.1"),
                allowedIps = listOf("0.0.0.0/0", "::/0"),
            ),
        )

        verify(exactly = 1) { anyConstructed<VpnService.Builder>().setMetered(false) }
    }
}
