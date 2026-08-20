package app.birdo.vpn.data.network

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/**
 * Unit tests for [DohResolver].
 *
 * Since DohResolver is a singleton with a hardcoded DoH endpoint (Cloudflare),
 * full resolution tests require network access. These tests validate the object's
 * public API contract and the Dns adapter interface.
 */
class DohResolverTest {

    @Test
    fun `dns property is not null`() {
        assertNotNull(DohResolver.dns)
    }

    @Test
    fun `dns property implements okhttp3 Dns`() {
        val dns = DohResolver.dns
        assertTrue(dns is okhttp3.Dns)
    }

    @Test
    fun `resolve throws on empty hostname`() {
        try {
            DohResolver.resolve("")
            fail("Expected exception for empty hostname")
        } catch (_: Exception) {
            // Expected — invalid hostname should throw
        }
    }

    @Test
    fun `dns lookup delegates to resolve`() {
        // Verify the dns adapter and resolve() behave the same for invalid input
        var resolveException: Exception? = null
        var dnsException: Exception? = null

        try {
            DohResolver.resolve("invalid..hostname..test")
        } catch (e: Exception) {
            resolveException = e
        }

        try {
            DohResolver.dns.lookup("invalid..hostname..test")
        } catch (e: Exception) {
            dnsException = e
        }

        // Both should either succeed or fail — they share the same code path
        assertEquals(resolveException == null, dnsException == null)
        if (resolveException != null && dnsException != null) {
            assertEquals(resolveException::class, dnsException::class)
        }
    }

    @Test
    fun `cloudflare client is initialized`() {
        // Use reflection to verify the DoH client was constructed.
        val cfField = DohResolver::class.java.getDeclaredField("cloudflare")
        cfField.isAccessible = true
        assertNotNull(cfField.get(DohResolver))
    }

    /**
     * P6-CLI-A-06. This test previously also asserted a `quad9` field existed;
     * that assertion is gone because the provider is gone, and the check is
     * inverted here so the removal is pinned rather than merely un-asserted.
     *
     * Google and Quad9 have no other visibility into this app, so each lookup
     * gave them a datapoint they could not otherwise have — this IP is about to
     * use a VPN, right now. Cloudflare is a different case and stays: it already
     * terminates TLS for every api.birdo.app request (the host is Cloudflare
     * proxied), so its DoH endpoint learns nothing new, and dropping DoH
     * entirely would hand the lookup in cleartext to the local network, which is
     * the adversary that DPI-blocks WireGuard in the first place. See the
     * DohResolver kdoc.
     */
    @Test
    fun `third-party DoH providers stay removed`() {
        val fields = DohResolver::class.java.declaredFields.map { it.name }
        assertFalse(
            "P6-CLI-A-06 broken: a Google DoH resolver is back — it learns a " +
                "user's VPN timing and has no other reason to know it",
            fields.contains("google"),
        )
        assertFalse(
            "P6-CLI-A-06 broken: a Quad9 DoH resolver is back — same problem",
            fields.contains("quad9"),
        )
    }

    @Test
    fun `bootstrap client is initialized`() {
        val field = DohResolver::class.java.getDeclaredField("bootstrapClient")
        field.isAccessible = true
        val client = field.get(DohResolver)
        assertNotNull(client)
        assertTrue(client is okhttp3.OkHttpClient)
    }
}
