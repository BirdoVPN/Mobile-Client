package app.birdo.vpn.service

import android.net.VpnService
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins the kill-switch arming order in [BirdoVpnService.activateKillSwitch]:
 * the blocking Builder.establish() MUST run BEFORE the wg-go teardown
 * (WgNative.turnOff).
 *
 * In the normal connected state the tunnel's sole tun fd lives inside wg-go
 * (startTunnel detachFd()s it and vpnInterface is null), so tearing the data
 * plane down first closes that fd and destroys the interface — routing reverts
 * to the physical network and cleartext egresses for the whole
 * wg-go-shutdown + establish() window. establish() atomically supersedes the
 * live tun for the same app, so establish-then-teardown has no such window.
 *
 * The service is an Android Service, so these tests drive the private method
 * via reflection with the framework seams mocked: [WgNative] (internal object,
 * mockkObject) and [VpnService.Builder] (mockkConstructor). android.jar stubs
 * are non-throwing here (isReturnDefaultValues = true).
 */
class KillSwitchOrderingTest {

    /** Records the relative order of establish() vs WgNative.turnOff(). */
    private val callOrder = mutableListOf<String>()

    private lateinit var service: BirdoVpnService

    @Before
    fun setup() {
        callOrder.clear()

        mockkObject(WgNative)
        every { WgNative.turnOff(any()) } answers {
            callOrder += "turnOff"
        }

        mockkConstructor(VpnService.Builder::class)
        // Builder is a fluent API — every setter must return the builder itself.
        every { anyConstructed<VpnService.Builder>().setSession(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setMtu(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addAddress(any<String>(), any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addRoute(any<String>(), any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addDnsServer(any<String>()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().setBlocking(any()) } answers { self as VpnService.Builder }
        every { anyConstructed<VpnService.Builder>().addDisallowedApplication(any()) } answers { self as VpnService.Builder }

        service = BirdoVpnService()
        // Simulate the live-tunnel state the finding describes: wg-go owns the
        // sole tun fd (handle >= 0), vpnInterface is null (post-detachFd).
        setInstanceField("tunnelHandle", 7)
    }

    @After
    fun tearDown() {
        unmockkAll()
        // Reset companion state mutated by updateState(KillSwitchActive) so the
        // shared-process companion doesn't leak into other test classes.
        val updateState = BirdoVpnService.Companion::class.java
            .getDeclaredMethod("updateState", VpnState::class.java)
        updateState.isAccessible = true
        updateState.invoke(BirdoVpnService.Companion, VpnState.Disconnected)
        // Companion property backing fields are compiled onto the OUTER class.
        val flowField = BirdoVpnService::class.java
            .getDeclaredField("_killSwitchActiveFlow")
        flowField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = flowField.get(null)
            as kotlinx.coroutines.flow.MutableStateFlow<Boolean>
        flow.value = false
    }

    private fun setInstanceField(name: String, value: Any) {
        val field = BirdoVpnService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(service, value)
    }

    /** What the arm achieved. A Unit return (the old signature) fails the cast, by design. */
    private fun invokeActivateKillSwitch(): BlockArm {
        val m = BirdoVpnService::class.java.getDeclaredMethod("activateKillSwitch")
        m.isAccessible = true
        return m.invoke(service) as BlockArm
    }

    @Test
    fun `activateKillSwitch establishes the block BEFORE turning wg-go off`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            mockk<ParcelFileDescriptor>(relaxed = true)
        }

        assertEquals("an armed block must say so", BlockArm.ARMED, invokeActivateKillSwitch())

        // The blocking interface must be up (superseding the live tun) before
        // the live tunnel's fd is closed — never the reverse.
        assertEquals(listOf("establish", "turnOff"), callOrder)
        verify(exactly = 1) { WgNative.turnOff(7) }
    }

    @Test
    fun `activateKillSwitch still tears down wg-go when establish returns null`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            null
        }

        // P1-dk-killswitch-establish-failure-silent: the failure is the
        // RESULT now, not a report nobody downstream could see.
        assertEquals("a refused block must not read as armed", BlockArm.FAILED, invokeActivateKillSwitch())

        // Even when the block cannot be established (e.g. consent revoked) the
        // data plane must still be torn down — and still only after the
        // attempts (the first and its one retry), with the live tun still up
        // in between.
        assertEquals(listOf("establish", "establish", "turnOff"), callOrder)
        assertFalse(BirdoVpnService.killSwitchActive)
    }

    @Test
    fun `activateKillSwitch still tears down wg-go when establish throws`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            throw IllegalStateException("VPN not prepared")
        }

        assertEquals("a block that threw must not read as armed", BlockArm.FAILED, invokeActivateKillSwitch())

        // The catch path preserves the always-tear-down contract.
        assertEquals(listOf("establish", "establish", "turnOff"), callOrder)
    }

    @Test
    fun `a block refused once is armed by the one retry, before wg-go comes down`() {
        var attempts = 0
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            attempts++
            if (attempts == 1) null else mockk<ParcelFileDescriptor>(relaxed = true)
        }

        assertEquals(BlockArm.ARMED, invokeActivateKillSwitch())

        assertEquals(listOf("establish", "establish", "turnOff"), callOrder)
        assertTrue(BirdoVpnService.killSwitchActive)
    }

    @Test
    fun `the retry is bounded - one, not a loop`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            null
        }

        invokeActivateKillSwitch()
        invokeActivateKillSwitch()

        // Two calls, two attempts each: a refusal that will not change (a
        // revoked consent) costs one short retry per arm, never a spin.
        assertEquals(4, callOrder.count { it == "establish" })
    }

    // ── P2-1: the retry and onDestroy (A1-012) ───────────────────────────

    @Test
    fun `onDestroy beginning during the retry's wait stops the second establish`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            // onDestroy lands on the main thread while the retry sleeps.
            Thread {
                Thread.sleep(80)
                setInstanceField("destroyed", true)
            }.start()
            null
        }

        // A teardown, not a failure: nothing to tell the user about.
        assertEquals(BlockArm.ABANDONED, invokeActivateKillSwitch())

        // The check used to run only BEFORE the 250 ms sleep, so the retry
        // called establish() on a service onDestroy had already reached.
        assertEquals(listOf("establish", "turnOff"), callOrder)
    }

    @Test
    fun `an interrupt during the retry stops it, and is restored only after the teardown`() {
        var interruptedAtTeardown: Boolean? = null
        every { WgNative.turnOff(any()) } answers {
            callOrder += "turnOff"
            interruptedAtTeardown = Thread.currentThread().isInterrupted
        }
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            Thread.currentThread().interrupt()
            null
        }

        try {
            // Given up, not refused (N7). Whether that is silent is the
            // caller's call: only for a teardown or a superseded transition;
            // on a live, current one it is a failure to arm (NEW-3, pinned in
            // BirdoVpnServiceLifecycleTest).
            assertEquals(BlockArm.ABANDONED, invokeActivateKillSwitch())
            assertEquals(listOf("establish", "turnOff"), callOrder)
            // Set during the teardown, the flag made its bounded probe join
            // throw at once instead of waiting for the probe to exit.
            assertEquals(false, interruptedAtTeardown)
            assertTrue("the interrupt must not be swallowed", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }
}
