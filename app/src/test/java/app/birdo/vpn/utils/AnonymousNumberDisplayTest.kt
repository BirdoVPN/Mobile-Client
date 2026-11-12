package app.birdo.vpn.utils

import app.birdo.vpn.data.model.UserProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MR-1707: the masked number's display, and the flag that hides the replace entry. */
class AnonymousNumberDisplayTest {

    private val masked = "*".repeat(20) + "1234"
    private val whole = "111111111111111111111234"

    private fun anon(number: String?, email: String = "anon_${"*".repeat(20)}1234@anonymous.local") =
        UserProfile(id = "u1", email = email, accountType = "anonymous", isAnonymous = true, accountNumber = number)

    @Test
    fun `a masked number reads as bullets, an ellipsis and its last four`() {
        assertTrue(isMaskedAccountNumber(masked))
        assertEquals("1234", maskedNumberTail(masked))
        assertEquals("•••• … 1234", maskedAccountNumberDisplay(maskedNumberTail(masked)!!))
        assertEquals("1234", maskedAccountTailOf(anon(masked)))
    }

    @Test
    fun `a masked number is never a number to reveal or copy`() {
        // Before MR-1707 the digit filter turned the mask into a 4-digit "credential".
        assertNull(accountNumberOf(anon(masked)))
    }

    @Test
    fun `a whole number behaves exactly as before`() {
        val user = anon(whole, email = "anon_${whole}@anonymous.local")
        assertFalse(isMaskedAccountNumber(whole))
        assertNull(maskedAccountTailOf(user))
        assertEquals(whole, accountNumberOf(user))
    }

    @Test
    fun `no tail without stars, a standard account or a tail-less mask`() {
        assertNull(maskedNumberTail(null))
        assertNull(maskedNumberTail(whole))
        assertNull(maskedNumberTail("*".repeat(24)))
        assertNull(maskedAccountTailOf(UserProfile(id = "u2", email = "user@birdo.app", accountNumber = masked)))
    }

    @Test
    fun `the replace entry shows only with the flag ON and an anonymous account`() {
        val user = anon(masked)
        assertFalse("the flag defaults OFF", replaceNumberEntryVisible(user, flag = false))
        assertTrue(replaceNumberEntryVisible(user, flag = true))
        assertFalse(replaceNumberEntryVisible(UserProfile(id = "u2", email = "user@birdo.app"), flag = true))
        assertFalse(replaceNumberEntryVisible(null, flag = true))
    }

    @Test
    fun `the shipped flag is OFF`() {
        assertFalse(app.birdo.vpn.BuildConfig.ANON_NUMBER_ROTATION)
    }
}
