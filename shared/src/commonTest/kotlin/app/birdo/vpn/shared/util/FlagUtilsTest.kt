package app.birdo.vpn.shared.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FlagUtilsTest {

    private val globe = "🌐" // 🌐

    @Test
    fun upperCaseIsoCodeBecomesRegionalIndicatorPair() {
        // G = U+1F1EC, B = U+1F1E7 -> 🇬🇧
        assertEquals("🇬🇧", countryCodeToFlag("GB"))
        // J = U+1F1EF, P = U+1F1F5 -> 🇯🇵
        assertEquals("🇯🇵", countryCodeToFlag("JP"))
    }

    @Test
    fun lowerCaseIsAccepted() {
        assertEquals(countryCodeToFlag("US"), countryCodeToFlag("us"))
    }

    @Test
    fun everyLetterPairIsExactlyTwoSurrogatePairs() {
        for (a in 'A'..'Z') for (b in 'A'..'Z') {
            val flag = countryCodeToFlag("$a$b")
            assertEquals(4, flag.length, "$a$b")
            assertEquals(0x1F1E6 + (a - 'A'), codePointAt(flag, 0), "$a$b first")
            assertEquals(0x1F1E6 + (b - 'A'), codePointAt(flag, 2), "$a$b second")
        }
    }

    @Test
    fun wrongLengthFallsBackToTheGlobe() {
        assertEquals(globe, countryCodeToFlag(""))
        assertEquals(globe, countryCodeToFlag("G"))
        assertEquals(globe, countryCodeToFlag("GBR"))
    }

    @Test
    fun nonLettersFallBackToTheGlobe() {
        // Anything outside A..Z has no regional indicator; arithmetic on it
        // would produce an unrelated code point, not an "unknown" flag.
        assertEquals(globe, countryCodeToFlag("12"))
        assertEquals(globe, countryCodeToFlag("G!"))
        assertEquals(globe, countryCodeToFlag("  "))
    }

    private fun codePointAt(s: String, i: Int): Int {
        val hi = s[i].code
        val lo = s[i + 1].code
        return 0x10000 + ((hi - 0xD800) shl 10) + (lo - 0xDC00)
    }
}
