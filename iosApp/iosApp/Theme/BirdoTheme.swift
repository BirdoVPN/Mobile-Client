import SwiftUI

/// Birdo VPN colour palette — matches Android BirdoTheme (Color.kt / Brand.kt) exactly.
enum BirdoTheme {
    static let black          = Color(hex: 0x000000)
    static let background     = Color(hex: 0x050505)
    static let surface        = Color(hex: 0x0D0D0D)
    static let surfaceVariant = Color(hex: 0x1A1A1A)
    static let card           = Color(hex: 0x14141A).opacity(0.7)
    static let border         = Color.white.opacity(0.08)

    // Primary accent — EMERALD (the violet era is over).
    static let accent     = Color(hex: 0x10B981)  // emerald-500
    static let accentDeep = Color(hex: 0x059669)  // emerald-600
    static let accentSoft = Color(hex: 0x6EE7B7)  // emerald-300 — focus rings, links
    static let accentBg   = Color(hex: 0x10B981).opacity(0.1)

    // Connected state — luminous mint. With a green brand, hue can no longer
    // carry connection state; the idle CTA is the dark `primaryGradient` and
    // CONNECTED is this luminous green + glow. Luminance, not hue.
    static let green       = Color(hex: 0x34D399)  // emerald-400 — Connected
    static let greenLight  = Color(hex: 0x6EE7B7)  // emerald-300 — text
    static let greenBg     = Color(hex: 0x34D399).opacity(0.1)
    static let greenShadow = Color(hex: 0x34D399).opacity(0.3)

    static let red         = Color(hex: 0xF87171)  // red-400 — Error state
    static let redBg       = Color(hex: 0xF87171).opacity(0.1)

    static let yellow      = Color(hex: 0xEAB308)  // yellow-500 — Connecting state
    static let yellowLight = Color(hex: 0xFACC15)  // yellow-400 — text
    static let yellowBg    = Color(hex: 0xEAB308).opacity(0.1)

    static let blue        = Color(hex: 0x3B82F6)  // blue-500 — Info / P2P

    static let white       = Color(hex: 0xF2F2F2)
    static let white80     = Color.white.opacity(0.8)
    static let white60     = Color.white.opacity(0.6)
    static let white40     = Color.white.opacity(0.4)
    static let white20     = Color.white.opacity(0.2)
    static let white10     = Color.white.opacity(0.1)
    static let white05     = Color.white.opacity(0.05)

    static let glassStrong = Color(hex: 0x050505).opacity(0.85)
    static let glassLight  = Color.white.opacity(0.05)
    static let glassInput  = Color.white.opacity(0.04)

    /// Idle Connect CTA gradient — deliberately DARK (emerald-700 → emerald-900).
    /// The connected state is the luminous `green` + `greenShadow` glow; a
    /// bright emerald here would make "protected" and "not protected" look
    /// alike, which on a VPN is a privacy bug, not a style one.
    static let primaryGradient = LinearGradient(
        colors: [Color(hex: 0x047857), Color(hex: 0x064E3B)],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
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
