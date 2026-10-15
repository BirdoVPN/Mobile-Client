package app.birdo.vpn.data.network

import app.birdo.vpn.di.NetworkModule
import io.mockk.mockk
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * MR-949: inside a tunnel to a node without IPv6, IPv6 is blackholed, so the
 * tunnel client must never try an AAAA answer before the A answer and sit out
 * its 10 s connect timeout there.
 */
class Ipv4FirstDnsTest {

    private fun v4(last: Int) = InetAddress.getByAddress("api.birdo.app", byteArrayOf(192.toByte(), 0, 2, last.toByte()))

    private fun v6(last: Int) = InetAddress.getByAddress(
        "api.birdo.app",
        ByteArray(16).also { it[0] = 0x20; it[1] = 0x01; it[2] = 0x0d; it[3] = 0xb8.toByte(); it[15] = last.toByte() },
    )

    private fun answering(vararg addresses: InetAddress): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = listOf(*addresses)
    }

    @Test
    fun `every IPv4 address comes before every IPv6 one, each family in the resolver's order`() {
        val answer = Ipv4FirstDns(answering(v6(1), v4(1), v6(2), v4(2))).lookup("api.birdo.app")
        assertEquals(listOf(v4(1), v4(2), v6(1), v6(2)), answer)
    }

    @Test
    fun `nothing is dropped - an IPv6-only answer is still the answer`() {
        assertEquals(listOf(v6(1), v6(2)), Ipv4FirstDns(answering(v6(1), v6(2))).lookup("api.birdo.app"))
        assertEquals(listOf(v4(3)), Ipv4FirstDns(answering(v4(3))).lookup("api.birdo.app"))
    }

    @Test
    fun `a failed lookup still fails`() {
        val failing = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("no such host")
        }
        try {
            Ipv4FirstDns(failing).lookup("api.birdo.app")
            fail("expected UnknownHostException")
        } catch (expected: UnknownHostException) {
            // The transport error path is unchanged.
        }
    }

    /** The client the app actually builds for a live tunnel uses it. */
    @Test
    fun `the tunnel client resolves IPv4 first`() {
        val client = NetworkModule.provideTunnelClient(mockk(relaxed = true))
        assertTrue(
            "the tunnel client resolves with ${client.dns::class.java.name}, not Ipv4FirstDns",
            client.dns is Ipv4FirstDns,
        )
    }
}
