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

    private fun invokeActivateKillSwitch() {
        val m = BirdoVpnService::class.java.getDeclaredMethod("activateKillSwitch")
        m.isAccessible = true
        m.invoke(service)
    }

    @Test
    fun `activateKillSwitch establishes the block BEFORE turning wg-go off`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            mockk<ParcelFileDescriptor>(relaxed = true)
        }

        invokeActivateKillSwitch()

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

        invokeActivateKillSwitch()

        // Even when the block cannot be established (e.g. consent revoked) the
        // data plane must still be torn down — and still only after the attempt.
        assertEquals(listOf("establish", "turnOff"), callOrder)
    }

    @Test
    fun `activateKillSwitch still tears down wg-go when establish throws`() {
        every { anyConstructed<VpnService.Builder>().establish() } answers {
            callOrder += "establish"
            throw IllegalStateException("VPN not prepared")
        }

        invokeActivateKillSwitch()

        // The catch path preserves the always-tear-down contract.
        assertEquals(listOf("establish", "turnOff"), callOrder)
    }
}
