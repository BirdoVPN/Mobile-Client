import SwiftUI

/// Birdo VPN colour palette — matches Android BirdoTheme exactly.
enum BirdoTheme {
    static let black       = Color(hex: 0x0A0A0A)
    static let background  = Color(hex: 0x111111)
    static let surface     = Color(hex: 0x1A1A1A)
    static let card        = Color(hex: 0x161616)
    static let border      = Color.white.opacity(0.06)

    // Primary accent — EMERALD. Names kept for call-site compatibility; the
    // values are Android's BirdoAccent / BirdoAccentSoft (emerald-500 / -300).
    static let purple      = Color(hex: 0x10B981)
    static let purpleLight = Color(hex: 0x6EE7B7)

    // Connected state — emerald-400. The brand is green, so connection state is
    // separated from the accent by LUMINANCE, not hue (see Android Color.kt).
    static let green       = Color(hex: 0x34D399)
    static let greenLight  = Color(hex: 0x6EE7B7)
    static let greenBg     = Color(hex: 0x34D399).opacity(0.1)
    static let greenShadow = Color(hex: 0x34D399).opacity(0.3)

    static let red         = Color(hex: 0xF87171)
    static let redBg       = Color(hex: 0xF87171).opacity(0.1)

    static let yellow      = Color(hex: 0xEAB308)
    static let yellowLight = Color(hex: 0xFACC15)
    static let yellowBg    = Color(hex: 0xEAB308).opacity(0.1)

    static let blue        = Color(hex: 0x3B82F6)

    static let white       = Color.white
    static let white80     = Color.white.opacity(0.8)
    static let white60     = Color.white.opacity(0.6)
    static let white40     = Color.white.opacity(0.4)
    static let white20     = Color.white.opacity(0.2)
    static let white10     = Color.white.opacity(0.1)
    static let white05     = Color.white.opacity(0.05)

    static let glassStrong = Color(hex: 0x111111).opacity(0.85)
    static let glassLight  = Color.white.opacity(0.05)
    static let glassInput  = Color.white.opacity(0.04)
}

extension Color {
    init(hex: UInt, alpha: Double = 1.0) {
        self.init(
            .sRGB,
            red:   Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8)  & 0xFF) / 255,
            blue:  Double(hex         & 0xFF) / 255,
            opacity: alpha
        )
    }
}
