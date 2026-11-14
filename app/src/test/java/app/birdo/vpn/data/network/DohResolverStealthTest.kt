package app.birdo.vpn.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * MR-953 F5 — while Stealth runs, a failed DoH lookup is never answered by the
 * network's own resolver.
 *
 * During a Stealth session the app's UID is outside its tunnel (Xray is a child
 * process), so the bypass client's lookups leave from the real IP. They used to
 * fall back to the system resolver whenever DoH failed: a cleartext lookup of
 * api.birdo.app to the network operator, on exactly the networks Stealth hides
 * from. These drive [DohResolver.resolveWith] with fake collaborators; no
 * network is used.
 */
class DohResolverStealthTest {

    private val dohAnswer = listOf(InetAddress.getByAddress("api.birdo.app", byteArrayOf(192.toByte(), 0, 2, 10)))
    private val systemAnswer = listOf(InetAddress.getByAddress("api.birdo.app", byteArrayOf(198.toByte(), 51, 100, 7)))

    private var systemCalls = 0
    private val system: (String) -> List<InetAddress> = { systemCalls++; systemAnswer }
    private val dohFails: (String) -> List<InetAddress> = { throw IOException("DoH blocked") }
    private val dohWorks: (String) -> List<InetAddress> = { dohAnswer }

    private fun resolve(
        doh: (String) -> List<InetAddress>,
        stealth: Boolean,
        cache: MutableMap<String, List<InetAddress>> = mutableMapOf(),
        host: String = "api.birdo.app",
    ) = DohResolver.resolveWith(host, doh, system, { stealth }, cache)

    @Test
    fun `stealth with DoH failing fails the lookup and never asks the system resolver`() {
        try {
            resolve(dohFails, stealth = true)
            fail("the lookup must fail during Stealth when DoH fails")
        } catch (e: UnknownHostException) {
            assertTrue(e.cause is IOException)
        }
        assertEquals("the system resolver was asked during Stealth", 0, systemCalls)
    }

    @Test
    fun `stealth with DoH failing reuses an earlier DoH answer for the same host`() {
        val cache = mutableMapOf<String, List<InetAddress>>()
        assertEquals(dohAnswer, resolve(dohWorks, stealth = true, cache = cache))
        assertEquals(dohAnswer, resolve(dohFails, stealth = true, cache = cache, host = "API.birdo.app"))
        assertEquals(0, systemCalls)
    }

    @Test
    fun `an earlier answer for ANOTHER host is not used`() {
        val cache = mutableMapOf<String, List<InetAddress>>("cloudflare-dns.com" to dohAnswer)
        try {
            resolve(dohFails, stealth = true, cache = cache)
            fail("expected the lookup to fail")
        } catch (_: UnknownHostException) {
        }
        assertEquals(0, systemCalls)
    }

    /** Only DoH answers are remembered: a system answer must never be replayed in Stealth. */
    @Test
    fun `a system resolver answer outside stealth is not remembered`() {
        val cache = mutableMapOf<String, List<InetAddress>>()
        assertEquals(systemAnswer, resolve(dohFails, stealth = false, cache = cache))
        assertTrue(cache.isEmpty())
        try {
            resolve(dohFails, stealth = true, cache = cache)
            fail("expected the lookup to fail")
        } catch (_: UnknownHostException) {
        }
        assertEquals(1, systemCalls)
    }

    @Test
    fun `outside stealth the system resolver is still the fail-open fallback`() {
        assertSame(systemAnswer, resolve(dohFails, stealth = false))
        assertEquals(1, systemCalls)
    }

    @Test
    fun `a working DoH answers in both modes without the system resolver`() {
        assertEquals(dohAnswer, resolve(dohWorks, stealth = true))
        assertEquals(dohAnswer, resolve(dohWorks, stealth = false))
        assertEquals(0, systemCalls)
    }

    /** The Stealth flag is read only when DoH has failed, never on the hot path. */
    @Test
    fun `stealth is consulted only after DoH fails`() {
        var reads = 0
        DohResolver.resolveWith("api.birdo.app", dohWorks, system, { reads++; true }, mutableMapOf())
        assertEquals(0, reads)
        try {
            DohResolver.resolveWith("api.birdo.app", dohFails, system, { reads++; true }, mutableMapOf())
        } catch (_: UnknownHostException) {
        }
        assertEquals(1, reads)
    }

    @Test
    fun `a blank hostname never reaches either resolver`() {
        var dohCalls = 0
        try {
            DohResolver.resolveWith(" ", { dohCalls++; dohAnswer }, system, { false }, mutableMapOf())
            fail("expected UnknownHostException")
        } catch (_: UnknownHostException) {
        }
        assertEquals(0, dohCalls)
        assertEquals(0, systemCalls)
    }

    /** The production path wires the live Stealth flag and the system resolver as the fallback. */
    @Test
    fun `resolve is wired to the live stealth flag`() {
        var dir = File("").absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile ?: error("repo root not found")
        val src = File(dir, "app/src/main/java/app/birdo/vpn/data/network/DohResolver.kt").readText()
        val resolve = src.substringAfter("fun resolve(hostname: String)").substringBefore("internal fun resolveWith(")
        assertTrue(resolve.contains("stealthActive = { BirdoVpnService.stealthActive }"))
        assertTrue(resolve.contains("system = okhttp3.Dns.SYSTEM::lookup"))
        val body = src.substringAfter("internal fun resolveWith(")
        assertFalse(
            "resolveWith must use the injected system resolver, not Dns.SYSTEM directly",
            body.substringBefore("val dns: okhttp3.Dns").contains("okhttp3.Dns.SYSTEM.lookup("),
        )
    }
}
