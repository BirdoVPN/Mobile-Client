package app.birdo.vpn.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * InputValidator gates every user-supplied value on every platform before it
 * reaches the backend or the tunnel config. A validator that is too loose
 * lets a bad DNS server or port into a WireGuard config; one that is too
 * strict locks a real user out. Both edges are pinned here.
 */
class InputValidatorTest {

    // ── email ────────────────────────────────────────────────────

    @Test
    fun acceptsOrdinaryAddressesAndTrimsWhitespace() {
        assertTrue(InputValidator.isValidEmail("user@example.com"))
        assertTrue(InputValidator.isValidEmail("first.last+tag@sub.example.co.uk"))
        assertTrue(InputValidator.isValidEmail("  user@example.com  "))
    }

    @Test
    fun rejectsMalformedAddresses() {
        assertFalse(InputValidator.isValidEmail(""))
        assertFalse(InputValidator.isValidEmail("   "))
        assertFalse(InputValidator.isValidEmail("user"))
        assertFalse(InputValidator.isValidEmail("user@"))
        assertFalse(InputValidator.isValidEmail("@example.com"))
        assertFalse(InputValidator.isValidEmail("user@example"))
        assertFalse(InputValidator.isValidEmail("user@@example.com"))
        assertFalse(InputValidator.isValidEmail("us er@example.com"))
    }

    @Test
    fun emailLengthCapIs254() {
        val local = "a".repeat(254 - "@example.com".length)
        assertTrue(InputValidator.isValidEmail("$local@example.com"))
        assertFalse(InputValidator.isValidEmail("${local}a@example.com"))
    }

    // ── password ─────────────────────────────────────────────────

    @Test
    fun passwordLengthWindowIs6To256() {
        assertFalse(InputValidator.isValidPassword("12345"))
        assertTrue(InputValidator.isValidPassword("123456"))
        assertTrue(InputValidator.isValidPassword("x".repeat(256)))
        assertFalse(InputValidator.isValidPassword("x".repeat(257)))
    }

    // ── IPv4 ─────────────────────────────────────────────────────

    @Test
    fun ipv4LiteralsAreValidatedPerOctet() {
        assertTrue(InputValidator.isValidIpv4("192.168.1.1"))
        assertTrue(InputValidator.isValidIpv4("0.0.0.0"))
        assertTrue(InputValidator.isValidIpv4("255.255.255.255"))
        assertTrue(InputValidator.isValidIpv4(" 10.0.0.1 "))
        assertFalse(InputValidator.isValidIpv4("256.1.1.1"))
        assertFalse(InputValidator.isValidIpv4("1.2.3"))
        assertFalse(InputValidator.isValidIpv4("1.2.3.4.5"))
        assertFalse(InputValidator.isValidIpv4("1.2.3.4a"))
        assertFalse(InputValidator.isValidIpv4("example.com"))
    }

    @Test
    fun ipLiteralSniffSeparatesAddressesFromHostnames() {
        assertTrue(InputValidator.looksLikeIpLiteral("1.1.1.1"))
        assertTrue(InputValidator.looksLikeIpLiteral("2606:4700:4700::1111"))
        assertTrue(InputValidator.looksLikeIpLiteral("fe80::1%25"))
        assertFalse(InputValidator.looksLikeIpLiteral("dns.google"))
        assertFalse(InputValidator.looksLikeIpLiteral("1.1.1.1/"))
    }

    // ── DNS address (platform actual) ────────────────────────────

    @Test
    fun publicResolversAreAccepted() {
        assertTrue(InputValidator.isValidDnsAddress("1.1.1.1"))
        assertTrue(InputValidator.isValidDnsAddress("9.9.9.9"))
        assertTrue(InputValidator.isValidDnsAddress(" 8.8.8.8 "))
        assertTrue(InputValidator.isValidDnsAddress("2606:4700:4700::1111"))
    }

    @Test
    fun unroutableOrUnsafeResolversAreRejected() {
        assertFalse(InputValidator.isValidDnsAddress(""))
        assertFalse(InputValidator.isValidDnsAddress("   "))
        // hostnames would trigger a resolution: never
        assertFalse(InputValidator.isValidDnsAddress("dns.google"))
        assertFalse(InputValidator.isValidDnsAddress("127.0.0.1"))   // loopback
        assertFalse(InputValidator.isValidDnsAddress("0.0.0.0"))     // any-local
        assertFalse(InputValidator.isValidDnsAddress("169.254.1.1")) // link-local
        assertFalse(InputValidator.isValidDnsAddress("224.0.0.251")) // multicast
        assertFalse(InputValidator.isValidDnsAddress("192.168.1.1")) // RFC1918
        assertFalse(InputValidator.isValidDnsAddress("10.0.0.53"))   // RFC1918
        assertFalse(InputValidator.isValidDnsAddress("::1"))         // v6 loopback
        assertFalse(InputValidator.isValidDnsAddress("fe80::1"))     // v6 link-local
        assertFalse(InputValidator.isValidDnsAddress("fd00::53"))    // v6 ULA
        assertFalse(InputValidator.isValidDnsAddress("ff02::fb"))    // v6 multicast
    }

    // ── port ─────────────────────────────────────────────────────

    @Test
    fun portRangeIs1To65535AndAutoIsAllowedAsText() {
        assertFalse(InputValidator.isValidPort(0))
        assertTrue(InputValidator.isValidPort(1))
        assertTrue(InputValidator.isValidPort(51820))
        assertTrue(InputValidator.isValidPort(65535))
        assertFalse(InputValidator.isValidPort(65536))
        assertFalse(InputValidator.isValidPort(-1))

        assertTrue(InputValidator.isValidPort("auto"))
        assertTrue(InputValidator.isValidPort("53"))
        assertFalse(InputValidator.isValidPort("0"))
        assertFalse(InputValidator.isValidPort("65536"))
        assertFalse(InputValidator.isValidPort(""))
        assertFalse(InputValidator.isValidPort("Auto"))
        assertFalse(InputValidator.isValidPort("51820 "))
        assertFalse(InputValidator.isValidPort("1e3"))
    }

    // ── MTU ──────────────────────────────────────────────────────

    @Test
    fun mtuZeroMeansAutoAndOtherwiseClampsTo1280To1500() {
        assertEquals(0, InputValidator.clampMtu(0))
        assertEquals(1280, InputValidator.clampMtu(1))
        assertEquals(1280, InputValidator.clampMtu(1279))
        assertEquals(1280, InputValidator.clampMtu(1280))
        assertEquals(1420, InputValidator.clampMtu(1420))
        assertEquals(1500, InputValidator.clampMtu(1500))
        assertEquals(1500, InputValidator.clampMtu(9000))

        assertTrue(InputValidator.isValidMtu(0))
        assertFalse(InputValidator.isValidMtu(1279))
        assertTrue(InputValidator.isValidMtu(1280))
        assertTrue(InputValidator.isValidMtu(1500))
        assertFalse(InputValidator.isValidMtu(1501))
        assertFalse(InputValidator.isValidMtu(-1))
    }

    // ── error-message sanitiser ──────────────────────────────────

    @Test
    fun shortPlainServerMessagesPassThroughTrimmed() {
        assertEquals("Invalid voucher", InputValidator.sanitizeErrorMessage("  Invalid voucher \n", "fallback"))
    }

    @Test
    fun anythingThatLooksLikeAnInternalLeakBecomesTheFallback() {
        val fb = "Something went wrong"
        assertEquals(fb, InputValidator.sanitizeErrorMessage(null, fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("", fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("   ", fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("<HTML><body>502 Bad Gateway", fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("NullPointerException: x", fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("failed at line 3", fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("{\"stackTrace\":[]}", fb))
        assertEquals(fb, InputValidator.sanitizeErrorMessage("x".repeat(201), fb))
        // exactly 200 characters of plain text is still shown
        assertEquals("x".repeat(200), InputValidator.sanitizeErrorMessage("x".repeat(200), fb))
    }

    // ── server id ────────────────────────────────────────────────

    @Test
    fun serverIdsAreSlugsUpTo64Characters() {
        assertTrue(InputValidator.isValidServerId("tokyo-01"))
        assertTrue(InputValidator.isValidServerId("de_fra_2"))
        assertTrue(InputValidator.isValidServerId("a".repeat(64)))
        assertFalse(InputValidator.isValidServerId("a".repeat(65)))
        assertFalse(InputValidator.isValidServerId(""))
        assertFalse(InputValidator.isValidServerId("tokyo 01"))
        assertFalse(InputValidator.isValidServerId("tokyo/01"))
        assertFalse(InputValidator.isValidServerId("tokyo.01"))
    }
}
