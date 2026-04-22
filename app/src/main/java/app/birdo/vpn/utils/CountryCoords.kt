package app.birdo.vpn.utils

/**
 * Approximate latitude/longitude lookup for ISO-3166-1 alpha-2 country codes.
 * Used by the world-map view on the Connect screen to plot server markers.
 *
 * Coordinates are city-level approximations of the most common VPN POP for
 * each country, not country centroids — they look better on a small map.
 */
object CountryCoords {
    private val coords: Map<String, Pair<Double, Double>> = mapOf(
        // Lat,  Lon
        "US" to (39.83 to -98.58),
        "CA" to (45.42 to -75.69),
        "MX" to (19.43 to -99.13),
        "BR" to (-23.55 to -46.63),
        "AR" to (-34.61 to -58.38),
        "CL" to (-33.45 to -70.66),
        "CO" to (4.71 to -74.07),
        "PE" to (-12.05 to -77.04),

        "GB" to (51.51 to -0.13),
        "IE" to (53.35 to -6.26),
        "FR" to (48.86 to 2.35),
        "DE" to (52.52 to 13.41),
        "NL" to (52.37 to 4.90),
        "BE" to (50.85 to 4.35),
        "LU" to (49.61 to 6.13),
        "ES" to (40.42 to -3.70),
        "PT" to (38.72 to -9.14),
        "IT" to (41.90 to 12.50),
        "CH" to (47.38 to 8.54),
        "AT" to (48.21 to 16.37),
        "CZ" to (50.08 to 14.44),
        "SK" to (48.15 to 17.11),
        "PL" to (52.23 to 21.01),
        "HU" to (47.50 to 19.04),
        "RO" to (44.43 to 26.10),
        "BG" to (42.70 to 23.32),
        "GR" to (37.98 to 23.73),
        "DK" to (55.68 to 12.57),
        "NO" to (59.91 to 10.75),
        "SE" to (59.33 to 18.07),
        "FI" to (60.17 to 24.94),
        "EE" to (59.44 to 24.75),
        "LV" to (56.95 to 24.11),
        "LT" to (54.69 to 25.28),
        "IS" to (64.13 to -21.82),
        "UA" to (50.45 to 30.52),
        "MD" to (47.01 to 28.86),
        "AL" to (41.33 to 19.82),
        "RS" to (44.79 to 20.45),
        "HR" to (45.81 to 15.98),
        "SI" to (46.06 to 14.51),
        "BA" to (43.86 to 18.41),
        "MK" to (41.99 to 21.43),
        "ME" to (42.44 to 19.26),

        "RU" to (55.75 to 37.62),
        "TR" to (41.01 to 28.98),
        "IL" to (32.08 to 34.78),
        "AE" to (25.20 to 55.27),
        "SA" to (24.71 to 46.68),
        "QA" to (25.29 to 51.53),
        "KW" to (29.38 to 47.99),
        "BH" to (26.23 to 50.59),
        "OM" to (23.59 to 58.41),
        "JO" to (31.95 to 35.93),

        "EG" to (30.04 to 31.24),
        "MA" to (33.57 to -7.59),
        "TN" to (36.81 to 10.18),
        "DZ" to (36.75 to 3.04),
        "ZA" to (-26.20 to 28.04),
        "KE" to (-1.29 to 36.82),
        "NG" to (6.52 to 3.38),

        "IN" to (28.61 to 77.21),
        "PK" to (33.69 to 73.05),
        "BD" to (23.81 to 90.41),
        "LK" to (6.93 to 79.86),

        "CN" to (39.90 to 116.41),
        "HK" to (22.32 to 114.17),
        "TW" to (25.03 to 121.57),
        "JP" to (35.69 to 139.69),
        "KR" to (37.57 to 126.98),
        "VN" to (10.82 to 106.63),
        "TH" to (13.76 to 100.50),
        "MY" to (3.14 to 101.69),
        "SG" to (1.35 to 103.82),
        "ID" to (-6.21 to 106.85),
        "PH" to (14.60 to 120.98),

        "AU" to (-33.87 to 151.21),
        "NZ" to (-36.85 to 174.76),
    )

    /** Returns (lat, lon) for the given ISO country code, or `null` if unknown. */
    fun forCountry(code: String?): Pair<Double, Double>? {
        if (code.isNullOrBlank()) return null
        return coords[code.trim().uppercase()]
    }
}
