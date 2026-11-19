import SwiftUI

/// The app-wide button. Default (`.primary`) is the auth CTA: solid white
/// fill, black text, min height 52pt, radius 12, with loading-spinner and
/// disabled states. Variants mirror Android's BirdoButton set.
/// Spec: spec-auth-flow.md §1 (primary submit style),
/// spec-home-servers-consent.md §8 / spec-secondary-screens.md §0.3 (variants).
struct PrimaryButton: View {
    enum Variant {
        /// Solid white, black content (auth submit, "View plans").
        case primary
        /// Deep emerald gradient (0x047857 → 0x064E3B), white content
        /// (consent accept, "Add rule") — the green-budget brand fill.
        case brand
        /// 6%-white glass fill + glass-stroke border, white content.
        case secondary
        /// 12%-red fill, red content (destructive confirms).
        case danger
        /// Transparent, 80%-white content.
        case ghost
    }

    let title: String
    /// The price a purchase button names after its title (MR-2430). Kept
    /// apart from `title` so `PricedTitle` can move it to its own line.
    var price: StorePriceParts?
    var loadingTitle: String?
    var variant: Variant
    var icon: String?
    var isLoading: Bool
    var isEnabled: Bool
    var height: CGFloat
    var fontSize: CGFloat
    let action: () -> Void

    init(_ title: String,
         price: StorePriceParts? = nil,
         loadingTitle: String? = nil,
         variant: Variant = .primary,
         icon: String? = nil,
         isLoading: Bool = false,
         isEnabled: Bool = true,
         height: CGFloat = 52,
         fontSize: CGFloat = 14,
         action: @escaping () -> Void) {
        self.title = title
        self.price = price
        self.loadingTitle = loadingTitle
        self.variant = variant
        self.icon = icon
        self.isLoading = isLoading
        self.isEnabled = isEnabled
        self.height = height
        self.fontSize = fontSize
        self.action = action
    }

    private var active: Bool { isEnabled && !isLoading }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                if isLoading {
                    ProgressView()
                        .progressViewStyle(.circular)
                        .tint(contentColor)
                        .scaleEffect(0.8)
                } else if let icon {
                    Image(systemName: icon)
                        .birdoFont(18, weight: .semibold)
                }
                if !isLoading, let price {
                    PricedTitle(title: title, price: price)
                        .birdoFont(fontSize, weight: .semibold)
                } else {
                    Text(isLoading ? (loadingTitle ?? title) : title)
                        .birdoFont(fontSize, weight: .semibold)
                        .lineLimit(2)
                        .minimumScaleFactor(0.85)
                        .multilineTextAlignment(.center)
                }
            }
            .foregroundStyle(active ? contentColor : BirdoTheme.white40)
            .frame(maxWidth: .infinity, minHeight: height)
            .background(
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                    .fill(active ? fillStyle : AnyShapeStyle(BirdoTheme.white10))
            )
            .overlay(borderOverlay)
        }
        .buttonStyle(PressScaleButtonStyle())
        .disabled(!active)
        // One element, read in full: the price and its period included.
        .accessibilityLabel(spokenLabel)
    }

    private var spokenLabel: String {
        if isLoading { return loadingTitle ?? title }
        return price.map { PricedTitle.oneLine(title, $0) } ?? title
    }

    private var contentColor: Color {
        switch variant {
        case .primary:   return .black
        case .brand:     return .white
        case .secondary: return .white
        case .danger:    return BirdoTheme.red
        case .ghost:     return BirdoTheme.white80
        }
    }

    private var fillStyle: AnyShapeStyle {
        switch variant {
        case .primary:   return AnyShapeStyle(Color.white)
        case .brand:     return AnyShapeStyle(BirdoTheme.Gradients.primary)
        case .secondary: return AnyShapeStyle(BirdoTheme.glassStrong)
        case .danger:    return AnyShapeStyle(BirdoTheme.red.opacity(0.12))
        case .ghost:     return AnyShapeStyle(Color.clear)
        }
    }

    @ViewBuilder
    private var borderOverlay: some View {
        if case .secondary = variant {
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                .strokeBorder(BirdoTheme.Gradients.glassStroke, lineWidth: 1)
        }
    }
}

/// A purchase button's title and price (MR-2430). On one line when it fits,
/// "Subscribe · £38/yr" as before; otherwise the price takes its own line
/// under the title, and if it is still too wide the period ("/yr") goes under
/// the amount, as in the plan card's price (MR-2383). No lineLimit and no
/// scaling: as one Text under the button's two-line limit, a long localised
/// price lost its period at AX5, and a price without its period is unclear
/// pricing (App Review 3.1.2). The font comes from the button.
struct PricedTitle: View {
    let title: String
    let price: StorePriceParts

    /// The one-line form, which is also what VoiceOver reads.
    static func oneLine(_ title: String, _ price: StorePriceParts) -> String {
        "\(title) · \(price.whole)"
    }

    var body: some View {
        ViewThatFits(in: .horizontal) {
            Text(Self.oneLine(title, price))
            VStack(spacing: 0) {
                Text(title)
                ViewThatFits(in: .horizontal) {
                    Text(price.whole)
                    VStack(spacing: 0) {
                        Text(price.amount)
                        if !price.period.isEmpty {
                            Text(price.period)
                        }
                    }
                }
            }
        }
        .multilineTextAlignment(.center)
    }
}

/// Press feedback: scale to 0.97 over 120ms with the standard curve
/// (desktop's hover-lift has no iOS equivalent).
struct PressScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(BirdoTheme.Motion.easeStandard(0.12), value: configuration.isPressed)
    }
}
