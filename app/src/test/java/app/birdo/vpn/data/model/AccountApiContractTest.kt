package app.birdo.vpn.data.model

import app.birdo.vpn.di.NetworkModule
import app.birdo.vpn.utils.accountNumberOf
import app.birdo.vpn.utils.filterTwoFactorInput
import app.birdo.vpn.utils.isAnonymousUser
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ACCOUNT-API-2026-10-01 (items 85, 86 and 40). The backend is being built to
 * these names and is not deployed yet, so every new field is optional: today's
 * JSON and tomorrow's must both work, through THE wire serializer
 * ([NetworkModule.json]), not a test-local one.
 */
class AccountApiContractTest {

    private val json = NetworkModule.json

    private fun profile(body: String): UserProfile = json.decodeFromString(body)

    private val number = "123456789012345678901234"

    // ── Item 86: GET /auth/me ────────────────────────────────────────────

    @Test
    fun `today's profile decodes, and an anonymous one is known by its email`() {
        val user = profile("""{"id":"u1","email":"anon_$number@anonymous.local","hasPassword":false}""")
        assertNull(user.accountType)
        assertNull(user.isAnonymous)
        assertNull(user.accountNumber)
        assertTrue(isAnonymousUser(user))
        assertEquals(number, accountNumberOf(user))

        val standard = profile("""{"id":"u2","email":"user@birdo.app"}""")
        assertFalse(isAnonymousUser(standard))
        assertNull(accountNumberOf(standard))
    }

    @Test
    fun `the new fields decide, ahead of the email's shape`() {
        val user = profile(
            """{"id":"u1","email":"anon_${"9".repeat(24)}@anonymous.local",
               "accountType":"anonymous","isAnonymous":true,"accountNumber":"$number"}""",
        )
        assertTrue(isAnonymousUser(user))
        assertEquals("the server's own number wins", number, accountNumberOf(user))

        val standard = profile(
            """{"id":"u2","email":"user@birdo.app","accountType":"standard","isAnonymous":false,"accountNumber":null}""",
        )
        assertFalse(isAnonymousUser(standard))
        assertNull(accountNumberOf(standard))
    }

    @Test
    fun `isAnonymous first, then accountType`() {
        assertTrue(isAnonymousUser(profile("""{"id":"u","email":"user@birdo.app","accountType":"anonymous"}""")))
        assertFalse(
            isAnonymousUser(profile("""{"id":"u","email":"anon_$number@anonymous.local","accountType":"standard"}""")),
        )
        assertTrue(
            isAnonymousUser(profile("""{"id":"u","email":"user@birdo.app","accountType":"standard","isAnonymous":true}""")),
        )
    }

    /** Phase 2 of the API sends no email for an anonymous account. A required field would fail the profile. */
    @Test
    fun `an anonymous profile with a null or absent email still decodes, with its number`() {
        val nullEmail = profile("""{"id":"u1","email":null,"isAnonymous":true,"accountNumber":"$number"}""")
        assertEquals("", nullEmail.email)
        assertTrue(isAnonymousUser(nullEmail))
        assertEquals(number, accountNumberOf(nullEmail))

        val noEmail = profile("""{"id":"u1","accountType":"anonymous","accountNumber":"$number"}""")
        assertEquals(number, accountNumberOf(noEmail))
    }

    /** The number is the account's only credential: a stray log of the profile must not carry it. */
    @Test
    fun `the profile never prints its account number or email`() {
        val user = profile("""{"id":"u1","email":"anon_$number@anonymous.local","isAnonymous":true,"accountNumber":"$number"}""")
        assertFalse(user.toString().contains(number))
        assertFalse(user.toString().contains("@"))
    }

    // ── Item 85: deletion with 2FA ───────────────────────────────────────

    @Test
    fun `a deletion without a code sends today's body, with one it adds twoFactorCode`() {
        assertEquals("{}", json.encodeToString(DeleteAccountRequest()))
        assertEquals("""{"password":"pw"}""", json.encodeToString(DeleteAccountRequest(password = "pw")))
        assertEquals(
            """{"password":"pw","twoFactorCode":"123456"}""",
            json.encodeToString(DeleteAccountRequest(password = "pw", twoFactorCode = "123456")),
        )
        assertEquals(
            """{"twoFactorCode":"abcd-ef01-2345-6789"}""",
            json.encodeToString(DeleteAccountRequest(twoFactorCode = "abcd-ef01-2345-6789")),
        )
    }

    @Test
    fun `the deletion code field keeps what the sign-in code field keeps`() {
        assertEquals("123456", filterTwoFactorInput("123 456"))
        assertEquals("ABCD-ef01", filterTwoFactorInput("ABCD-ef01!"))
        assertEquals(19, filterTwoFactorInput("a".repeat(40)).length)
    }

    // ── Item 40: client-config ───────────────────────────────────────────

    /**
     * `features.<PLAN>.customDns` is in the per-plan map the app does not
     * model: Custom DNS is ungated on Android (D6), which is the contract's
     * default. What must hold is that today's and tomorrow's payloads decode.
     */
    @Test
    fun `client-config decodes with and without the per-plan customDns flag`() {
        val today = json.decodeFromString<ClientConfigResponse>(
            """{"dnsFilteringAvailable":true,"features":{"RECON":{"customDns":false,"dnsFiltering":true}}}""",
        )
        assertEquals(true, today.dnsFilteringAvailable)
        val tomorrow = json.decodeFromString<ClientConfigResponse>(
            """{"dnsFilteringAvailable":false,"features":{"RECON":{"customDns":true},"OPERATIVE":{"customDns":true},
               "SOVEREIGN":{"customDns":true}}}""",
        )
        assertEquals(false, tomorrow.dnsFilteringAvailable)
    }
}
