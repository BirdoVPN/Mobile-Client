package app.birdo.vpn.utils

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The 24-digit anonymous ID is the account's only credential and users copy it
 * out by hand from the "save your account ID" dialog. These pin the grouping so
 * what they write down reads back identically to the Anonymous login field's
 * advertised shape ("XXXX XXXX XXXX XXXX XXXX XXXX").
 */
class AnonymousIdTest {

    @Test
    fun `a full 24-digit id renders as six groups of four`() {
        assertEquals(
            "1234 5678 9012 3456 7890 1234",
            formatAnonymousId("123456789012345678901234"),
        )
    }

    @Test
    fun `grouping matches the login field placeholder shape`() {
        // Regression guard: the dialog and the login field must agree, or a user
        // transcribing from one into the other thinks they made a mistake.
        val placeholder = "XXXX XXXX XXXX XXXX XXXX XXXX"
        val formatted = formatAnonymousId("123456789012345678901234")
        assertEquals(placeholder.length, formatted.length)
        assertEquals(
            placeholder.map { if (it == ' ') ' ' else 'd' },
            formatted.map { if (it == ' ') ' ' else 'd' },
        )
    }

    @Test
    fun `separators and letters in the input are stripped before grouping`() {
        assertEquals(
            "1234 5678 9012 3456 7890 1234",
            formatAnonymousId(" 1234-5678 9012_3456\n7890abc1234 "),
        )
    }

    @Test
    fun `a short or odd-length id still renders — this is display, not validation`() {
        // The 24-digit length check lives in AuthViewModel.loginAnonymous; this
        // formatter must never swallow digits it was handed.
        assertEquals("1234 56", formatAnonymousId("123456"))
        assertEquals("1", formatAnonymousId("1"))
    }

    @Test
    fun `an empty id renders as empty rather than throwing`() {
        assertEquals("", formatAnonymousId(""))
        assertEquals("", formatAnonymousId("no digits here"))
    }
}
