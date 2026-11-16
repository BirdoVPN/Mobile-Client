import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

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

    /// Every token scales with the user's Dynamic Type setting (A-M38) and
    /// is EXACTLY the old point size at the default `.large` category.
    /// Use `.font(BirdoTheme.Fonts.x)` (scaled overload below) or, for a
    /// one-off size, `.birdoFont(13, weight: .semibold)`.
    enum Fonts {
        static let displayLarge   = BirdoFontToken(size: 36, weight: .bold)
        static let displayMedium  = BirdoFontToken(size: 30, weight: .bold)
        static let headlineLarge  = BirdoFontToken(size: 26, weight: .bold)
        static let headlineMedium = BirdoFontToken(size: 20, weight: .semibold)
        static let titleLarge     = BirdoFontToken(size: 18, weight: .semibold)
        static let titleMedium    = BirdoFontToken(size: 16, weight: .medium)
        static let titleSmall     = BirdoFontToken(size: 14, weight: .medium)
        static let bodyLarge      = BirdoFontToken(size: 16)
        static let bodyMedium     = BirdoFontToken(size: 14)
        static let bodySmall      = BirdoFontToken(size: 12)
        static let labelLarge     = BirdoFontToken(size: 14, weight: .semibold)
        static let labelMedium    = BirdoFontToken(size: 12, weight: .medium)
        static let labelSmall     = BirdoFontToken(size: 10, weight: .medium)
        /// Section headers: uppercase + `.tracking(1.5)`, color `white60`.
        static let sectionHeader  = BirdoFontToken(size: 11, weight: .semibold)
        /// Stat tile value — pair with `.monospacedDigit()`.
        static let statValue      = BirdoFontToken(size: 12, weight: .semibold)
        /// Stat tile caption: uppercase + tracked, color `white55`.
        static let statCaption    = BirdoFontToken(size: 9, weight: .medium)
        /// Monospace (anonymous account number, IPs).
        static let mono           = BirdoFontToken(size: 14, design: .monospaced)
    }
}

/// A point size that follows Dynamic Type. `size` is the size at the default
/// `.large` category; `textStyle` picks the scaling curve (the system text
/// style whose default size is nearest), as `Font.custom(_:size:relativeTo:)`
/// does for a custom font.
struct BirdoFontToken: Equatable {
    let size: CGFloat
    let weight: Font.Weight
    let design: Font.Design
    let textStyle: Font.TextStyle
    /// The largest size it may grow to, or nil for no limit. Never below
    /// `size`, so the default category always renders the size asked for.
    let maxSize: CGFloat?

    init(size: CGFloat,
         weight: Font.Weight = .regular,
         design: Font.Design = .default,
         relativeTo textStyle: Font.TextStyle? = nil,
         maxSize: CGFloat? = nil) {
        self.size = size
        self.weight = weight
        self.design = design
        self.textStyle = textStyle ?? Self.nearestTextStyle(for: size)
        self.maxSize = maxSize.map { Swift.max($0, size) }
    }

    /// A Dynamic Type size for this token, held to `maxSize`.
    func clamped(_ scaled: CGFloat) -> CGFloat {
        maxSize.map { Swift.min(scaled, $0) } ?? scaled
    }

    /// The ONE place a scaled point size becomes a SwiftUI `Font`
    /// (ScaledFontGuardTests exempts exactly this line).
    func font(scaled: CGFloat) -> Font {
        let scaledSize = clamped(scaled)
        return .system(size: scaledSize, weight: weight, design: design)
    }

    /// The system text style whose default (`.large`) size is nearest.
    static func nearestTextStyle(for size: CGFloat) -> Font.TextStyle {
        switch size {
        case ..<11.5:  return .caption2   // 11
        case ..<12.5:  return .caption    // 12
        case ..<14:    return .footnote   // 13
        case ..<15.5:  return .subheadline // 15 (14 rounds here)
        case ..<16.5:  return .callout    // 16
        case ..<19:    return .body       // 17
        case ..<21:    return .title3     // 20
        case ..<25:    return .title2     // 22
        case ..<31:    return .title      // 28
        default:       return .largeTitle // 34
        }
    }
}

/// Applies a `BirdoFontToken`, scaled by the Dynamic Type environment.
/// `@ScaledMetric` reads the environment, so this follows both the system
/// setting and an `.environment(\.dynamicTypeSize, ...)` override.
struct BirdoScaledFont: ViewModifier {
    private let token: BirdoFontToken
    @ScaledMetric private var scaledSize: CGFloat

    init(_ token: BirdoFontToken) {
        self.token = token
        _scaledSize = ScaledMetric(wrappedValue: token.size,
                                   relativeTo: token.textStyle)
    }

    func body(content: Content) -> some View {
        content.font(token.font(scaled: scaledSize))
    }
}

/// A scaled `Font` VALUE, for a `Text` that has to stay a `Text` (a
/// TextField's `prompt:`), where the `birdoFont` modifier cannot go:
/// `@BirdoScaledFontValue(14) private var promptFont`, then
/// `Text("...").font(promptFont)`. Same token, same curve, same builder.
@propertyWrapper
struct BirdoScaledFontValue: DynamicProperty {
    private let token: BirdoFontToken
    @ScaledMetric private var scaledSize: CGFloat

    init(_ size: CGFloat,
         weight: Font.Weight = .regular,
         design: Font.Design = .default,
         relativeTo textStyle: Font.TextStyle? = nil,
         max maxSize: CGFloat? = nil) {
        let token = BirdoFontToken(size: size, weight: weight, design: design,
                                   relativeTo: textStyle, maxSize: maxSize)
        self.token = token
        _scaledSize = ScaledMetric(wrappedValue: token.size, relativeTo: token.textStyle)
    }

    var wrappedValue: Font { token.font(scaled: scaledSize) }
}

extension View {
    /// Scaled font from a theme token. Overload of `font(_:)`, so existing
    /// `.font(BirdoTheme.Fonts.bodySmall)` call sites scale unchanged.
    func font(_ token: BirdoFontToken) -> some View {
        modifier(BirdoScaledFont(token))
    }

    /// Scaled font for a one-off size (the size at the default category).
    ///
    /// `max`: the largest size it may grow to. Give it to every glyph that
    /// sits in a FIXED box (an icon tile, a round button, a fixed-width icon
    /// column): the box does not grow with the text, so an uncapped symbol
    /// spills out of it at the accessibility sizes. The cap is the size that
    /// still fits the box. Text that can wrap gets no cap.
    func birdoFont(_ size: CGFloat,
                   weight: Font.Weight = .regular,
                   design: Font.Design = .default,
                   relativeTo textStyle: Font.TextStyle? = nil,
                   max maxSize: CGFloat? = nil) -> some View {
        modifier(BirdoScaledFont(BirdoFontToken(size: size, weight: weight, design: design,
                                                relativeTo: textStyle, maxSize: maxSize)))
    }
}

#if canImport(UIKit)
extension BirdoTheme {
    /// UIKit twin of `.birdoFont(_:weight:max:)` for chrome that only takes
    /// a `UIFont` (the tab bar's `UITabBarAppearance`). `size` is the size at
    /// the default `.large` category; `UIFontMetrics` scales it along
    /// `textStyle`'s curve for the text size in `traits` (the current one when
    /// nil), never past `maximumPointSize`. UIKit chrome has a fixed height,
    /// so the cap is required here.
    static func scaledUIFont(_ size: CGFloat,
                             weight: UIFont.Weight = .regular,
                             relativeTo textStyle: UIFont.TextStyle,
                             maximumPointSize: CGFloat,
                             compatibleWith traits: UITraitCollection? = nil) -> UIFont {
        UIFontMetrics(forTextStyle: textStyle)
            .scaledFont(for: .systemFont(ofSize: size, weight: weight),
                        maximumPointSize: Swift.max(maximumPointSize, size),
                        compatibleWith: traits)
    }

    /// The tab bar's label: 11pt at the default size (Android bottom bar),
    /// SemiBold selected / Medium unselected, growing to at most 14pt so it
    /// stays inside the bar. Above that, UITabBar's own Large Content Viewer
    /// shows the item large on a long press at the accessibility sizes.
    static let tabBarLabelMaxPointSize: CGFloat = 14

    static func tabBarLabelFont(selected: Bool,
                                compatibleWith traits: UITraitCollection? = nil) -> UIFont {
        scaledUIFont(11, weight: selected ? .semibold : .medium, relativeTo: .caption2,
                     maximumPointSize: tabBarLabelMaxPointSize, compatibleWith: traits)
    }
}
#endif

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
