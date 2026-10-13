package app.birdo.vpn.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MR-1870: the PKCE verifier is the half of native SSO that never leaves the
 * device, and the pair does not print it. Failure messages name the class and
 * never quote what it printed.
 */
class PkceToStringTest {

    @Test
    fun `the PKCE pair prints no verifier`() {
        val printed = PkceGenerator.Pkce(verifier = "pkce-verifier-abc", challenge = "pkce-challenge-xyz").toString()
        assertFalse("Pkce printed its verifier", printed.contains("pkce-verifier-abc"))
        // The challenge is public: it goes out in the authorize URL.
        assertTrue("Pkce lost its challenge", printed.contains("challenge=pkce-challenge-xyz"))
    }
}
