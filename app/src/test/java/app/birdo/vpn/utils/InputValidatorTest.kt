package app.birdo.vpn.utils

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for InputValidator.
 *
 * Covers:
 *  - Email validation (valid, invalid, edge cases, max length)
 *  - Password validation (min/max length boundaries)
 *  - DNS address validation (IPv4, IPv6, rejection of hostnames, loopback, etc.)
 *  - IPv4 strict validation
 *  - Port validation (numeric, string, "auto")
 *  - MTU validation and clamping
 *  - Error message sanitization (HTML, stack traces, length)
 *  - Server ID validation
 */
class InputValidatorTest {

    // ── Email ────────────────────────────────────────────────────

    @Test
    fun `valid email addresses`() {
        assertTrue(InputValidator.isValidEmail("user@example.com"))
        assertTrue(InputValidator.isValidEmail("user.name@example.co.uk"))
        assertTrue(InputValidator.isValidEmail("user+tag@domain.org"))
        assertTrue(InputValidator.isValidEmail("a@b.cd"))
        assertTrue(InputValidator.isValidEmail("first-last@domain.com"))
        assertTrue(InputValidator.isValidEmail("user%name@domain.com"))
        assertTrue(InputValidator.isValidEmail("user_name@domain.com"))
    }

    @Test
    fun `valid email with whitespace is trimmed`() {
        assertTrue(InputValidator.isValidEmail("  user@example.com  "))
    }

    @Test
    fun `invalid emails rejected`() {
        assertFalse(InputValidator.isValidEmail(""))
        assertFalse(InputValidator.isValidEmail("   "))
        assertFalse(InputValidator.isValidEmail("notanemail"))
        assertFalse(InputValidator.isValidEmail("@example.com"))
        assertFalse(InputValidator.isValidEmail("user@"))
        assertFalse(InputValidator.isValidEmail("user@.com"))
        assertFalse(InputValidator.isValidEmail("user@com"))
        assertFalse(InputValidator.isValidEmail("user @example.com")) // space in local part
        assertFalse(InputValidator.isValidEmail("user@exam ple.com")) // space in domain
    }

    @Test
    fun `email exceeding 254 chars is rejected`() {
        val longLocal = "a".repeat(245)
        val email = "$longLocal@b.cd" // 245 + 1 + 4 = 250, OK
        assertTrue(InputValidator.isValidEmail(email))

        val tooLongLocal = "a".repeat(250)
        val tooLongEmail = "$tooLongLocal@b.cd" // 250 + 1 + 4 = 255, rejected
        assertFalse(InputValidator.isValidEmail(tooLongEmail))
    }

    @Test
    fun `email TLD must be at least 2 chars`() {
        assertFalse(InputValidator.isValidEmail("user@example.a"))
        assertTrue(InputValidator.isValidEmail("user@example.ab"))
    }

    // ── Password ─────────────────────────────────────────────────

    @Test
    fun `valid passwords match length requirements`() {
        assertTrue(InputValidator.isValidPassword("123456")) // exactly 6 (min)
        assertTrue(InputValidator.isValidPassword("a".repeat(256))) // exactly 256 (max)
        assertTrue(InputValidator.isValidPassword("normalPassword!"))
    }

    @Test
    fun `password too short is rejected`() {
        assertFalse(InputValidator.isValidPassword(""))
        assertFalse(InputValidator.isValidPassword("12345")) // 5 chars
    }

    @Test
    fun `password too long is rejected`() {
        assertFalse(InputValidator.isValidPassword("a".repeat(257))) // 257 chars
    }

    @Test
    fun `password with special characters is valid`() {
        assertTrue(InputValidator.isValidPassword("p@\$\$w0rd!#%^"))
        assertTrue(InputValidator.isValidPassword("пароль123")) // unicode
    }

    // ── DNS Address ──────────────────────────────────────────────

    @Test
    fun `valid IPv4 DNS addresses accepted`() {
        assertTrue(InputValidator.isValidDnsAddress("1.1.1.1"))
        assertTrue(InputValidator.isValidDnsAddress("8.8.8.8"))
        assertTrue(InputValidator.isValidDnsAddress("9.9.9.9"))
        assertTrue(InputValidator.isValidDnsAddress("208.67.222.222"))
    }

    @Test
    fun `valid IPv6 DNS addresses accepted`() {
        assertTrue(InputValidator.isValidDnsAddress("2606:4700:4700::1111"))
        assertTrue(InputValidator.isValidDnsAddress("2001:4860:4860::8888"))
    }

    @Test
    fun `loopback addresses rejected for DNS`() {
        assertFalse(InputValidator.isValidDnsAddress("127.0.0.1"))
        assertFalse(InputValidator.isValidDnsAddress("127.0.0.2"))
        assertFalse(InputValidator.isValidDnsAddress("::1")) // IPv6 loopback
    }

    @Test
    fun `wildcard address rejected for DNS`() {
        assertFalse(InputValidator.isValidDnsAddress("0.0.0.0"))
    }

    @Test
    fun `link-local addresses rejected for DNS`() {
        assertFalse(InputValidator.isValidDnsAddress("169.254.1.1"))
    }

    @Test
    fun `multicast addresses rejected for DNS`() {
        assertFalse(InputValidator.isValidDnsAddress("224.0.0.1"))
    }

    @Test
    fun `private RFC1918 addresses rejected for DNS - unreachable through the tunnel`() {
        // Leak (local network sharing on) or blackhole (off) — never usable.
        assertFalse(InputValidator.isValidDnsAddress("192.168.1.1"))
        assertFalse(InputValidator.isValidDnsAddress("10.0.0.1"))
        assertFalse(InputValidator.isValidDnsAddress("172.16.0.1"))
        assertFalse(InputValidator.isValidDnsAddress("172.31.255.254"))
    }

    @Test
    fun `IPv6 unique-local addresses rejected for DNS`() {
        assertFalse(InputValidator.isValidDnsAddress("fd00::1"))
        assertFalse(InputValidator.isValidDnsAddress("fc00::53"))
    }

    @Test
    fun `public addresses adjacent to private ranges still accepted for DNS`() {
        assertTrue(InputValidator.isValidDnsAddress("172.32.0.1"))
        assertTrue(InputValidator.isValidDnsAddress("11.0.0.1"))
        assertTrue(InputValidator.isValidDnsAddress("192.169.0.1"))
    }

    @Test
    fun `blank and empty DNS addresses rejected`() {
        assertFalse(InputValidator.isValidDnsAddress(""))
        assertFalse(InputValidator.isValidDnsAddress("   "))
    }

    @Test
    fun `hostnames rejected for DNS - prevents DNS lookup`() {
        // FIX-1-9: looksLikeIpLiteral guard prevents resolving hostnames
        assertFalse(InputValidator.isValidDnsAddress("evil.com"))
        assertFalse(InputValidator.isValidDnsAddress("dns.google"))
        assertFalse(InputValidator.isValidDnsAddress("localhost"))
    }

    @Test
    fun `DNS address with whitespace is trimmed`() {
        assertTrue(InputValidator.isValidDnsAddress("  1.1.1.1  "))
    }

    // ── IPv4 Strict ──────────────────────────────────────────────

    @Test
    fun `valid IPv4 addresses`() {
        assertTrue(InputValidator.isValidIpv4("0.0.0.0"))
        assertTrue(InputValidator.isValidIpv4("192.168.1.1"))
        assertTrue(InputValidator.isValidIpv4("255.255.255.255"))
        assertTrue(InputValidator.isValidIpv4("10.0.0.1"))
    }

    @Test
    fun `invalid IPv4 addresses`() {
        assertFalse(InputValidator.isValidIpv4("256.0.0.1"))
        assertFalse(InputValidator.isValidIpv4("1.2.3"))
        assertFalse(InputValidator.isValidIpv4("1.2.3.4.5"))
        assertFalse(InputValidator.isValidIpv4(""))
        assertFalse(InputValidator.isValidIpv4("not-an-ip"))
        assertFalse(InputValidator.isValidIpv4("1.2.3.999"))
    }

    @Test
    fun `IPv4 with whitespace is trimmed`() {
        assertTrue(InputValidator.isValidIpv4(" 10.0.0.1 "))
    }

    // ── Port ─────────────────────────────────────────────────────

    @Test
    fun `valid numeric ports`() {
        assertTrue(InputValidator.isValidPort(1))
        assertTrue(InputValidator.isValidPort(80))
        assertTrue(InputValidator.isValidPort(443))
        assertTrue(InputValidator.isValidPort(51820)) // WireGuard default
        assertTrue(InputValidator.isValidPort(65535))
    }

    @Test
    fun `invalid numeric ports`() {
        assertFalse(InputValidator.isValidPort(0))
        assertFalse(InputValidator.isValidPort(-1))
        assertFalse(InputValidator.isValidPort(65536))
        assertFalse(InputValidator.isValidPort(100000))
    }

    @Test
    fun `valid string ports`() {
        assertTrue(InputValidator.isValidPort("auto"))
        assertTrue(InputValidator.isValidPort("51820"))
        assertTrue(InputValidator.isValidPort("443"))
    }

    @Test
    fun `invalid string ports`() {
        assertFalse(InputValidator.isValidPort(""))
        assertFalse(InputValidator.isValidPort("abc"))
        assertFalse(InputValidator.isValidPort("-1"))
        assertFalse(InputValidator.isValidPort("0"))
        assertFalse(InputValidator.isValidPort("65536"))
    }

    // ── MTU ──────────────────────────────────────────────────────

    @Test
    fun `valid MTU values`() {
        assertTrue(InputValidator.isValidMtu(0)) // auto
        assertTrue(InputValidator.isValidMtu(1280)) // min
        assertTrue(InputValidator.isValidMtu(1420)) // WireGuard default
        assertTrue(InputValidator.isValidMtu(1500)) // max
    }

    @Test
    fun `invalid MTU values`() {
        assertFalse(InputValidator.isValidMtu(1)) // too low, not auto
        assertFalse(InputValidator.isValidMtu(1279)) // just below min
        assertFalse(InputValidator.isValidMtu(1501)) // just above max
    }

    @Test
    fun `clampMtu returns 0 for auto`() {
        assertEquals(0, InputValidator.clampMtu(0))
    }

    @Test
    fun `clampMtu clamps to range`() {
        assertEquals(1280, InputValidator.clampMtu(100))   // below min → 1280
        assertEquals(1500, InputValidator.clampMtu(9999))  // above max → 1500
        assertEquals(1420, InputValidator.clampMtu(1420))  // within range → unchanged
    }

    // ── Error Message Sanitization ───────────────────────────────

    @Test
    fun `null or blank message returns fallback`() {
        assertEquals("Fallback", InputValidator.sanitizeErrorMessage(null, "Fallback"))
        assertEquals("Fallback", InputValidator.sanitizeErrorMessage("", "Fallback"))
        assertEquals("Fallback", InputValidator.sanitizeErrorMessage("   ", "Fallback"))
    }

    @Test
    fun `clean message is returned as-is`() {
        assertEquals("Connection timed out",
            InputValidator.sanitizeErrorMessage("Connection timed out", "Error"))
    }

    @Test
    fun `HTML content is rejected`() {
        assertEquals("Error",
            InputValidator.sanitizeErrorMessage("<html><body>500 Internal Server Error</body></html>", "Error"))
    }

    @Test
    fun `stack traces are rejected`() {
        assertEquals("Error",
            InputValidator.sanitizeErrorMessage(
                "NullPointerException at com.example.Foo.bar(Foo.java:42)", "Error"))
    }

    @Test
    fun `messages containing Exception keyword are rejected`() {
        assertEquals("Error",
            InputValidator.sanitizeErrorMessage("java.lang.RuntimeException: something", "Error"))
    }

    @Test
    fun `messages exceeding 200 chars are rejected`() {
        val longMessage = "A".repeat(201)
        assertEquals("Error", InputValidator.sanitizeErrorMessage(longMessage, "Error"))

        val okMessage = "A".repeat(200)
        assertEquals(okMessage, InputValidator.sanitizeErrorMessage(okMessage, "Error"))
    }

    @Test
    fun `message with stackTrace keyword is rejected`() {
        assertEquals("Error",
            InputValidator.sanitizeErrorMessage("{\"stackTrace\":\"...\"}", "Error"))
    }

    // ── Server ID ────────────────────────────────────────────────

    @Test
    fun `valid server IDs`() {
        assertTrue(InputValidator.isValidServerId("srv-1"))
        assertTrue(InputValidator.isValidServerId("clxxxxxxxxxxxxxxxxxx12345"))
        assertTrue(InputValidator.isValidServerId("server_node_01"))
        assertTrue(InputValidator.isValidServerId("a"))
    }

    @Test
    fun `invalid server IDs`() {
        assertFalse(InputValidator.isValidServerId(""))
        assertFalse(InputValidator.isValidServerId("a".repeat(65))) // too long
        assertFalse(InputValidator.isValidServerId("srv 1")) // spaces
        assertFalse(InputValidator.isValidServerId("srv@1")) // special chars
        assertFalse(InputValidator.isValidServerId("../etc/passwd")) // path traversal
    }
}
