package app.birdo.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Base64
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.birdo.vpn.data.model.ConnectResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MR-975 / MA-AND-007: the real [BirdoVpnService], on a real emulator, through
 * the real platform VpnService (Builder.establish, the real wg-go library).
 *
 * The JVM suites (BirdoVpnServiceLifecycleTest, KillSwitchOrderingTest,
 * TransportProbeTest) mock the platform and the native layer. What they cannot
 * show is that the pieces still meet on a device: that the service really
 * holds a block interface, really releases it, and really stays out of
 * "Connected" while no handshake has landed.
 *
 * THE FAKE BACKEND. The "server" is a loopback UDP port that nobody listens
 * on, handed to the service through [BirdoVpnService.setConfig] exactly as
 * VpnManager hands it a real /connect answer. wg-go dials it, no handshake
 * can ever complete, and the handshake gate has to do its job.
 *
 * WHAT IS NOT HERE. "Handshake -> Protected" needs a live WireGuard responder
 * (a second tun, which one app cannot hold); that half stays in
 * TransportProbeTest (decision) and BirdoVpnServiceLifecycleTest (publish).
 *
 * CONSENT. VpnService.prepare() would show a dialog nobody can tap on CI. The
 * ACTIVATE_VPN appop is granted through the instrumentation's shell identity
 * (the same switch `adb shell appops set` flips), which is what the consent
 * dialog itself does.
 */
@RunWith(AndroidJUnit4::class)
class VpnServiceInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantConsent() {
        setVpnConsent(allowed = true)
        assertNull(
            "VpnService.prepare must return null once ACTIVATE_VPN is allowed; the emulator would otherwise ask for consent",
            VpnService.prepare(context),
        )
    }

    @After
    fun tearDown() {
        // Always leave the process with no tunnel, no block and no service,
        // whatever the test did: the state is process-wide.
        setVpnConsent(allowed = true)
        send(BirdoVpnService.ACTION_STOP) { putExtra(BirdoVpnService.EXTRA_USER_INITIATED, true) }
        await(15_000) { BirdoVpnService.currentState is VpnState.Disconnected && !BirdoVpnService.killSwitchActive }
        await(10_000) { !BirdoVpnService.running }
    }

    /**
     * Connect with no peer answering. The service must dial (Connecting),
     * the probe must reach its BLOCKED verdict through the real service, and
     * at no instant may the state read Connected: nothing claims Protected
     * without a handshake.
     */
    @Test
    fun noHandshakeIsNeverProtected() {
        val scope = CoroutineScope(Dispatchers.Default)
        val blocked = CompletableDeferred<Unit>()
        // Collect BEFORE the start: the flow has no replay.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            BirdoVpnService.transportBlockedFlow.first()
            blocked.complete(Unit)
        }
        try {
            BirdoVpnService.setConfig(fakePeerConfig())
            send(BirdoVpnService.ACTION_START) {
                putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, true)
                putExtra(BirdoVpnService.EXTRA_STEALTH_REQUESTED, false)
            }

            var sawConnecting = false
            var sawConnected = false
            val deadline = System.currentTimeMillis() + TransportProbe.WINDOW_MS + 8_000
            while (System.currentTimeMillis() < deadline && !blocked.isCompleted) {
                val s = BirdoVpnService.currentState
                if (s is VpnState.Connected) sawConnected = true
                if (s.isConnectingPhase) sawConnecting = true
                Thread.sleep(25)
            }
            if (BirdoVpnService.currentState is VpnState.Connected) sawConnected = true

            assertFalse("Connected was published with no handshake", sawConnected)
            assertTrue("the service never reached a connecting state (state=${BirdoVpnService.currentState})", sawConnecting)
            assertTrue("the probe never reported the unanswered tunnel as blocked", blocked.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    /**
     * A drop while a session is up (here: the invalidated-session block, the
     * same path the tunnel-drop handler takes): the block interface must come
     * up, must HOLD, nothing may claim Connected meanwhile, and a reconnect
     * must dial again from behind it. The user's Disconnect then releases it.
     */
    @Test
    fun dropArmsBlockWhichHoldsThenReconnects() {
        BirdoVpnService.setConfig(fakePeerConfig())
        send(BirdoVpnService.ACTION_START) { putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, true) }
        assertTrue(
            "no connecting state after the start (state=${BirdoVpnService.currentState})",
            await(15_000) { BirdoVpnService.currentState.isConnectingPhase },
        )

        send(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK)
        assertTrue("the kill switch block never came up", await(15_000) { BirdoVpnService.killSwitchActive })
        assertNull("the block was armed but a not-armed warning is showing", BirdoVpnService.killSwitchNotArmedFlow.value)

        // It HOLDS: three seconds in which a leaky service would have dropped
        // the flag or claimed protection.
        val holdUntil = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < holdUntil) {
            assertTrue("the block let go on its own", BirdoVpnService.killSwitchActive)
            assertFalse("Connected over a dropped tunnel", BirdoVpnService.currentState is VpnState.Connected)
            Thread.sleep(50)
        }

        // Reconnect from behind the block.
        BirdoVpnService.setConfig(fakePeerConfig())
        send(BirdoVpnService.ACTION_START) { putExtra(BirdoVpnService.EXTRA_KILL_SWITCH, true) }
        assertTrue(
            "the reconnect never dialled (state=${BirdoVpnService.currentState})",
            await(15_000) { BirdoVpnService.currentState.isConnectingPhase },
        )
        assertFalse("Connected on the reconnect with no handshake", BirdoVpnService.currentState is VpnState.Connected)

        // The user's Disconnect releases everything.
        send(BirdoVpnService.ACTION_STOP) { putExtra(BirdoVpnService.EXTRA_USER_INITIATED, true) }
        assertTrue(
            "Disconnect left a state of ${BirdoVpnService.currentState}",
            await(15_000) { BirdoVpnService.currentState is VpnState.Disconnected },
        )
        assertTrue("Disconnect left the block up", await(10_000) { !BirdoVpnService.killSwitchActive })
    }

    /**
     * The re-arm cannot happen (consent revoked): the service must say so, not
     * sit silent claiming a block that is not there.
     */
    @Test
    fun blockRefusedWithoutConsentIsReportedNotSilent() {
        setVpnConsent(allowed = false)
        send(BirdoVpnService.ACTION_KILL_SWITCH_BLOCK)
        assertTrue(
            "a refused kill-switch arm was not reported",
            await(20_000) { BirdoVpnService.killSwitchNotArmedFlow.value != null },
        )
        assertFalse("killSwitchActive is true though establish() was refused", BirdoVpnService.killSwitchActive)
        assertNotNull(BirdoVpnService.killSwitchNotArmedFlow.value)
        assertFalse(BirdoVpnService.currentState is VpnState.Connected)
    }

    // -- helpers ----------------------------------------------------------

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(context, BirdoVpnService::class.java).setAction(action).apply(extras)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }

    private fun setVpnConsent(allowed: Boolean) {
        val mode = if (allowed) "allow" else "deny"
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("appops set ${context.packageName} ACTIVATE_VPN $mode")
        // Drain it: the command has not finished until its stream closes.
        java.io.FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
        pfd.close()
    }

    /** Throwaway key bytes, built at run time; this is not a real credential. */
    private fun fakeKey(seed: Int): String =
        Base64.encodeToString(ByteArray(32) { (it * 7 + seed).toByte() }, Base64.NO_WRAP)

    private fun fakePeerConfig() = ConnectResponse(
        success = true,
        privateKey = fakeKey(1),
        serverPublicKey = fakeKey(2),
        // Loopback, no listener: the handshake can never be answered.
        endpoint = "127.0.0.1:51999",
        assignedIp = "10.77.0.2",
        allowedIps = listOf("0.0.0.0/0"),
        persistentKeepalive = 25,
    )
}
