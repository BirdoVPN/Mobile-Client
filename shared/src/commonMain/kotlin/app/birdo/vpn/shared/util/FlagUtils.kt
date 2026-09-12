package app.birdo.vpn.shared.util

/**
 * Convert a 2-letter ISO 3166-1 alpha-2 country code to a flag emoji.
 * Uses Unicode Regional Indicator Symbol pairs (U+1F1E6 .. U+1F1FF).
 *
 * Each regional-indicator code point is in the supplementary plane, so it
 * has to be encoded as a UTF-16 surrogate pair manually (Kotlin/Common
 * does not have a code-point-array String constructor).
 */
fun countryCodeToFlag(countryCode: String): String {
    if (countryCode.length != 2) return "\uD83C\uDF10" // 🌐
    val upper = countryCode.uppercase()
    // Only A..Z have a regional indicator; arithmetic on anything else lands
    // on an unrelated code point (e.g. "12" -> two enclosed alphanumerics),
    // which would render as a wrong glyph rather than "unknown".
    if (upper.any { it !in 'A'..'Z' }) return "\uD83C\uDF10"
    val first = 0x1F1E6 + (upper[0].code - 'A'.code)
    val second = 0x1F1E6 + (upper[1].code - 'A'.code)
    return buildString(4) {
        appendCodePoint(first)
        appendCodePoint(second)
    }
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else {
        val offset = codePoint - 0x10000
        val high = (0xD800 + (offset shr 10)).toChar()
        val low = (0xDC00 + (offset and 0x3FF)).toChar()
        append(high)
        append(low)
    }
}
