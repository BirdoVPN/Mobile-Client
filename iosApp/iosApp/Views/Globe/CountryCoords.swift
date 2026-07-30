import Foundation

/// Approximate (lat, lon) lookup for ISO-3166-1 alpha-2 country codes, used to
/// plot server markers on the globe. City-level approximations of the most
/// common VPN POP per country (they read better on a small globe than true
/// centroids). Ported verbatim from the Android `CountryCoords`.
enum CountryCoords {
    // (lat, lon)
    private static let coords: [String: (Double, Double)] = [
        "US": (39.83, -98.58), "CA": (45.42, -75.69), "MX": (19.43, -99.13),
        "BR": (-23.55, -46.63), "AR": (-34.61, -58.38), "CL": (-33.45, -70.66),
        "CO": (4.71, -74.07), "PE": (-12.05, -77.04),

        "GB": (51.51, -0.13), "IE": (53.35, -6.26), "FR": (48.86, 2.35),
        "DE": (52.52, 13.41), "NL": (52.37, 4.90), "BE": (50.85, 4.35),
        "LU": (49.61, 6.13), "ES": (40.42, -3.70), "PT": (38.72, -9.14),
        "IT": (41.90, 12.50), "CH": (47.38, 8.54), "AT": (48.21, 16.37),
        "CZ": (50.08, 14.44), "SK": (48.15, 17.11), "PL": (52.23, 21.01),
        "HU": (47.50, 19.04), "RO": (44.43, 26.10), "BG": (42.70, 23.32),
        "GR": (37.98, 23.73), "DK": (55.68, 12.57), "NO": (59.91, 10.75),
        "SE": (59.33, 18.07), "FI": (60.17, 24.94), "EE": (59.44, 24.75),
        "LV": (56.95, 24.11), "LT": (54.69, 25.28), "IS": (64.13, -21.82),
        "UA": (50.45, 30.52), "MD": (47.01, 28.86), "AL": (41.33, 19.82),
        "RS": (44.79, 20.45), "HR": (45.81, 15.98), "SI": (46.06, 14.51),
        "BA": (43.86, 18.41), "MK": (41.99, 21.43), "ME": (42.44, 19.26),

        "RU": (55.75, 37.62), "TR": (41.01, 28.98), "IL": (32.08, 34.78),
        "AE": (25.20, 55.27), "SA": (24.71, 46.68), "QA": (25.29, 51.53),
        "KW": (29.38, 47.99), "BH": (26.23, 50.59), "OM": (23.59, 58.41),
        "JO": (31.95, 35.93),

        "EG": (30.04, 31.24), "MA": (33.57, -7.59), "TN": (36.81, 10.18),
        "DZ": (36.75, 3.04), "ZA": (-26.20, 28.04), "KE": (-1.29, 36.82),
        "NG": (6.52, 3.38),

        "IN": (28.61, 77.21), "PK": (33.69, 73.05), "BD": (23.81, 90.41),
        "LK": (6.93, 79.86),

        "CN": (39.90, 116.41), "HK": (22.32, 114.17), "TW": (25.03, 121.57),
        "JP": (35.69, 139.69), "KR": (37.57, 126.98), "VN": (10.82, 106.63),
        "TH": (13.76, 100.50), "MY": (3.14, 101.69), "SG": (1.35, 103.82),
        "ID": (-6.21, 106.85), "PH": (14.60, 120.98),

        "AU": (-33.87, 151.21), "NZ": (-36.85, 174.76),
    ]

    /// (lat, lon) for the given ISO country code, or nil if unknown.
    static func forCountry(_ code: String?) -> (lat: Double, lon: Double)? {
        guard let code, !code.trimmingCharacters(in: .whitespaces).isEmpty else { return nil }
        if let c = coords[code.trimmingCharacters(in: .whitespaces).uppercased()] {
            return (c.0, c.1)
        }
        return nil
    }
}
