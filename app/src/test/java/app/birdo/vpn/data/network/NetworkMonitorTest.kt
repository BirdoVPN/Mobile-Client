package app.birdo.vpn.data.network

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.birdo.vpn.data.network.NetworkMonitor.Connectivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * NetworkMonitor watches PHYSICAL networks only (D-6). Live on the emulator
 * (2026-09-30, airplane mode, kill switch on) the app's own block-all VPN
 * kept the old default-network monitor "online", so the supervisor counted
 * reconnect attempts through the whole outage instead of waiting. With the
 * app inside its own tunnel the default network IS the VPN, which would make
 * that permanent.
 */
class NetworkMonitorTest {

    @After
    fun tearDown() = unmockkAll()

    private fun caps(vararg present: Int): NetworkCapabilities = mockk {
        every { hasCapability(any()) } answers { firstArg<Int>() in present }
    }

    private val wifi = caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
        NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    private val unvalidated = caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    private val captive = caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_VPN,
        NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)

    /** Our block-all interface, or our tunnel: INTERNET, no NOT_VPN. */
    private val ourVpn = caps(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    @Test
    fun `the request asks for physical networks with internet only`() {
        mockkConstructor(NetworkRequest.Builder::class)
        val added = mutableListOf<Int>()
        every { anyConstructed<NetworkRequest.Builder>().addCapability(any()) } answers {
            added += firstArg<Int>()
            self as NetworkRequest.Builder
        }
        every { anyConstructed<NetworkRequest.Builder>().build() } returns mockk()

        NetworkMonitor.underlyingNetworkRequest()

        assertEquals(
            setOf(NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
            added.toSet(),
        )
        verify(exactly = 0) { anyConstructed<NetworkRequest.Builder>().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) }
    }

    @Test
    fun `airplane mode with our block up is OFFLINE`() {
        assertEquals(Connectivity.OFFLINE, NetworkMonitor.classify(emptyList()))
        // Even if a VPN ever reached the tracked set, it is not a network
        // this app can be carried over.
        assertEquals(Connectivity.OFFLINE, NetworkMonitor.classify(listOf(ourVpn)))
    }

    @Test
    fun `a physical network is ONLINE whether or not it has validated yet`() {
        assertEquals(Connectivity.ONLINE, NetworkMonitor.classify(listOf(wifi)))
        assertEquals(Connectivity.ONLINE, NetworkMonitor.classify(listOf(unvalidated)))
        assertEquals(Connectivity.ONLINE, NetworkMonitor.classify(listOf(ourVpn, wifi)))
        // Announced before its capabilities: the request guaranteed INTERNET.
        assertEquals(Connectivity.ONLINE, NetworkMonitor.classify(listOf(null)))
    }

    @Test
    fun `a network held behind a sign-in page is CAPTIVE_PORTAL, until a usable one exists (A1-026)`() {
        assertEquals(Connectivity.CAPTIVE_PORTAL, NetworkMonitor.classify(listOf(captive)))
        // Captive Wi-Fi, but cellular works: the device is online.
        assertEquals(Connectivity.ONLINE, NetworkMonitor.classify(listOf(captive, unvalidated)))
    }
}
