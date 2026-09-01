import SwiftUI

/// Birdo VPN design tokens — emerald brand, dark-only, corporate.
///
/// Values come from the rebuild specs (Android semantic palette + desktop
/// emerald visual identity):
///   - spec-home-servers-consent.md §1  (Android palette, plan identity, motion)
///   - spec-pixelcanvas-design.md   §1  (surfaces, brand stops, radii, motion)
///   - spec-auth-flow.md            §10 (auth surface tokens)
///
/// THE GREEN BUDGET (hard rule, do not "fix"): the brand is green, so
/// connection state is separated by LUMINANCE, not hue. Idle CTA = DEEP
/// emerald (`Gradients.connectIdle`, 0x047857 → 0x064E3B); connected =
/// luminous mint (`green` 0x34D399 →) + glow + pulsing dot. Never brighten
/// the idle button to brand green.
enum BirdoTheme {

    // MARK: - Surfaces (Android dark palette)

    /// Pure black — root window background behind PixelCanvas.
    static let black       = Color(hex: 0x000000)
    /// App background (s0).
    static let background  = Color(hex: 0x050507)
    /// Card surface (s1) — BirdoCard default fill.
    static let surface     = Color(hex: 0x0B0B10)
    /// Raised surface (s2) — sub-rows, icon chips, modals' base.
    static let surfaceRaised   = Color(hex: 0x12121A)
    /// Elevated surface (s3) — dialogs, popovers, toasts, sheets.
    static let surfaceElevated = Color(hex: 0x1A1A24)
    /// Legacy consent-card surface (Android BirdoSurfaceVariant).
    static let surfaceVariant  = Color(hex: 0x1A1A1A)
    /// Alias kept for call-site compatibility (was 0x161616).
    static let card        = Color(hex: 0x0B0B10)
    /// Glass card fill — rgba(20,20,25,0.70).
    static let cardGlass   = Color(hex: 0x141419).opacity(0.70)

    // MARK: - Brand (emerald)

    /// emerald-700 — deep brand stop (idle CTA gradient start).
    static let accentDeep  = Color(hex: 0x047857)
    /// emerald-600.
    static let accentMid   = Color(hex: 0x059669)
    /// emerald-500 — brand primary.
    static let accent      = Color(hex: 0x10B981)
    /// emerald-400 — luminous mint (== `green`).
    static let accentLight = Color(hex: 0x34D399)
    /// emerald-300 — accent text on dark, focus borders, links.
    static let accentSoft  = Color(hex: 0x6EE7B7)
    /// 10% emerald wash for chips/armed toggles.
    static let accentBg    = Color(hex: 0x10B981).opacity(0.10)
    /// Brand accent at an arbitrary alpha (focus ring 0.16, border 0.55, …).
    static func accentA(_ alpha: Double) -> Color { Color(hex: 0x10B981).opacity(alpha) }

    // Extra brand stops (plan identity, info accents).
    static let teal         = Color(hex: 0x14B8A6)
    static let cyanAccent   = Color(hex: 0x22D3EE)
    static let indigoAccent = Color(hex: 0x6366F1)
    /// slate-500 — RECON / free-tier identity.
    static let slate        = Color(hex: 0x64748B)

    // Legacy accent names — the emerald rebrand kept call sites compiling.
    static let purple      = Color(hex: 0x10B981)
    static let purpleLight = Color(hex: 0x6EE7B7)

    // MARK: - Status

    /// Connected — "luminous mint" (emerald-400). Only opaque green fill.
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
    static let blueBg      = Color(hex: 0x3B82F6).opacity(0.1)

    // MARK: - Text roles

    /// Foreground on the app background (#F2F2F2).
    static let onBackground   = Color(hex: 0xF2F2F2)
    static let onSurface      = Color.white
    /// 60% white — secondary text (smallest AA-passing tier is 55%+).
    static let onSurfaceMuted = Color.white.opacity(0.6)
    /// 40% white — decorative/large text only (fails AA below 14pt).
    static let onSurfaceFaint = Color.white.opacity(0.4)

    // MARK: - White scale

    static let white       = Color.white
    static let white80     = Color.white.opacity(0.8)
    static let white60     = Color.white.opacity(0.6)
    static let white55     = Color.white.opacity(0.55)
    static let white40     = Color.white.opacity(0.4)
    static let white20     = Color.white.opacity(0.2)
    static let white10     = Color.white.opacity(0.1)
    static let white06     = Color.white.opacity(0.06)
    static let white05     = Color.white.opacity(0.05)
    static let white04     = Color.white.opacity(0.04)
    static let white03     = Color.white.opacity(0.03)

    // MARK: - Hairlines & glass

    /// 12% white — strong hairline (selected borders, dividers on light rows).
    static let hairline     = Color.white.opacity(0.12)
    /// 8% white — the standard card/divider hairline.
    static let hairlineSoft = Color.white.opacity(0.08)
    /// 6% white — top bars, secondary button fill (Android GlassStrong).
    static let glassStrong  = Color.white.opacity(0.06)
    /// 3% white (Android GlassLight).
    static let glassLight   = Color.white.opacity(0.03)
    /// 4% white — text field container (Android GlassInput).
    static let glassInput   = Color.white.opacity(0.04)
    /// Alias kept for call-site compatibility (was white 6%).
    static let border       = Color.white.opacity(0.08)

    // MARK: - Plan identity (single source of truth; slug is case-insensitive)

    /// SOVEREIGN → emerald, OPERATIVE → teal, RECON/other → slate.
    static func planAccent(_ plan: String) -> Color {
        switch plan.uppercased() {
        case "SOVEREIGN": return accent
        case "OPERATIVE": return teal
        default:          return slate
        }
    }

    /// Plan chip / hero gradients (SOVEREIGN 0x059669→0x064E3B, OPERATIVE
    /// 0x0D9488→0x115E59, RECON 0x475569→0x334155).
    static func planGradient(_ plan: String) -> LinearGradient {
        switch plan.uppercased() {
        case "SOVEREIGN": return Gradients.planSovereign
        case "OPERATIVE": return Gradients.planOperative
        default:          return Gradients.planRecon
        }
    }

    /// RECON → "Free plan", OPERATIVE → "Operative plan", SOVEREIGN →
    /// "Sovereign plan", unknown → "{Slug} plan".
    static func planDisplayName(_ plan: String) -> String {
        switch plan.uppercased() {
        case "RECON":     return "Free plan"
        case "OPERATIVE": return "Operative plan"
        case "SOVEREIGN": return "Sovereign plan"
        default:          return "\(plan.capitalized) plan"
        }
    }

    // MARK: - Gradients (computed — LinearGradient is not guaranteed Sendable)

    enum Gradients {
        /// Signature 1pt glass card border: white 18% → 4% → 12%, 135°.
        static var glassStroke: LinearGradient {
            LinearGradient(
                stops: [
                    .init(color: .white.opacity(0.18), location: 0),
                    .init(color: .white.opacity(0.04), location: 0.5),
                    .init(color: .white.opacity(0.12), location: 1),
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
        }

        /// Brand CTA fill — DEEP emerald 0x047857 → 0x064E3B (the green budget).
        static var primary: LinearGradient {
            linear(0x047857, 0x064E3B)
        }

        /// Connect button — idle (same deep emerald as `primary`).
        static var connectIdle: LinearGradient { linear(0x047857, 0x064E3B) }
        /// Connect button — connecting/disconnecting wash.
        static var connectBusy: LinearGradient { linear(0x6EE7B7, 0x047857) }
        /// Connect button — connected, luminous mint.
        static var connectConnected: LinearGradient { linear(0x34D399, 0x059669) }
        /// Connect button — multi-hop armed & ready.
        static var connectMultiHop: LinearGradient { linear(0x10B981, 0x047857) }

        /// White → 55%-white vertical mask for hero headlines.
        static var headlineText: LinearGradient {
            LinearGradient(colors: [.white, .white.opacity(0.55)],
                           startPoint: .top, endPoint: .bottom)
        }

        static var planSovereign: LinearGradient { linear(0x059669, 0x064E3B) }
        static var planOperative: LinearGradient { linear(0x0D9488, 0x115E59) }
        static var planRecon: LinearGradient { linear(0x475569, 0x334155) }

        private static func linear(_ from: UInt, _ to: UInt) -> LinearGradient {
            LinearGradient(colors: [Color(hex: from), Color(hex: to)],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    // MARK: - Radii

    enum Radius {
        static let xs: CGFloat    = 6
        static let sm: CGFloat    = 10
        static let md: CGFloat    = 14
        static let lg: CGFloat    = 18
        static let xl: CGFloat    = 24
        /// BirdoCard default.
        static let card: CGFloat  = 16
        /// Sub-card / stat tile / text field / segmented track / buttons.
        static let sub: CGFloat   = 12
        static let pill: CGFloat  = 999
    }

    // MARK: - Spacing (4pt grid)

    enum Spacing {
        static let xxs: CGFloat  = 2
        static let xs: CGFloat   = 4
        static let sm: CGFloat   = 8
        static let md: CGFloat   = 12
        static let lg: CGFloat   = 16
        static let xl: CGFloat   = 20
        static let xxl: CGFloat  = 24
        static let xxxl: CGFloat = 32
        /// Screen edge insets.
        static let screenH: CGFloat = 20
        static let screenV: CGFloat = 16
        /// Minimum touch target.
        static let minTouch: CGFloat = 48
    }

    // MARK: - Motion (BirdoMotion durations + easings)

    enum Motion {
        static let instant: TimeInterval  = 0.09
        static let quick: TimeInterval    = 0.16
        static let standard: TimeInterval = 0.24
        static let emphasis: TimeInterval = 0.36
        static let slow: TimeInterval     = 0.52

        /// cubic-bezier(0.2, 0, 0, 1) — the standard curve for everything.
        static func easeStandard(_ duration: TimeInterval = standard) -> Animation {
            .timingCurve(0.2, 0, 0, 1, duration: duration)
        }
        /// cubic-bezier(0.3, 0, 0.8, 0.15) — exits.
        static func accel(_ duration: TimeInterval = quick) -> Animation {
            .timingCurve(0.3, 0, 0.8, 0.15, duration: duration)
        }
        /// cubic-bezier(0.05, 0.7, 0.1, 1) — entrances.
        static func decel(_ duration: TimeInterval = standard) -> Animation {
            .timingCurve(0.05, 0.7, 0.1, 1, duration: duration)
        }
        /// cubic-bezier(0.34, 1.56, 0.64, 1) — overshoot spring.
        static func spring(_ duration: TimeInterval = emphasis) -> Animation {
            .timingCurve(0.34, 1.56, 0.64, 1, duration: duration)
        }
    }

    // MARK: - Typography (system font, Android type scale)

    enum Fonts {
        static let displayLarge   = Font.system(size: 36, weight: .bold)
        static let displayMedium  = Font.system(size: 30, weight: .bold)
        static let headlineLarge  = Font.system(size: 26, weight: .bold)
        static let headlineMedium = Font.system(size: 20, weight: .semibold)
        static let titleLarge     = Font.system(size: 18, weight: .semibold)
        static let titleMedium    = Font.system(size: 16, weight: .medium)
        static let titleSmall     = Font.system(size: 14, weight: .medium)
        static let bodyLarge      = Font.system(size: 16)
        static let bodyMedium     = Font.system(size: 14)
        static let bodySmall      = Font.system(size: 12)
        static let labelLarge     = Font.system(size: 14, weight: .semibold)
        static let labelMedium    = Font.system(size: 12, weight: .medium)
        static let labelSmall     = Font.system(size: 10, weight: .medium)
        /// Section headers: uppercase + `.tracking(1.5)`, color `white60`.
        static let sectionHeader  = Font.system(size: 11, weight: .semibold)
        /// Stat tile value — pair with `.monospacedDigit()`.
        static let statValue      = Font.system(size: 12, weight: .semibold)
        /// Stat tile caption: uppercase + tracked, color `white55`.
        static let statCaption    = Font.system(size: 9, weight: .medium)
        /// Monospace (anonymous account number, IPs).
        static let mono           = Font.system(size: 14, design: .monospaced)
    }
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
