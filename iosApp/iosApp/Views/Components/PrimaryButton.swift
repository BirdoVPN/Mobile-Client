import SwiftUI

/// The app-wide button. Default (`.primary`) is the auth CTA: solid white
/// fill, black text, min height 52pt, radius 12, with loading-spinner and
/// disabled states. Variants mirror Android's BirdoButton set.
/// Spec: spec-auth-flow.md §1 (primary submit style),
/// spec-home-servers-consent.md §8 / spec-secondary-screens.md §0.3 (variants).
struct PrimaryButton: View {
    enum Variant {
        /// Solid white, black content (auth submit, "Upgrade for unlimited").
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
    var loadingTitle: String?
    var variant: Variant
    var icon: String?
    var isLoading: Bool
    var isEnabled: Bool
    var height: CGFloat
    var fontSize: CGFloat
    let action: () -> Void

    init(_ title: String,
         loadingTitle: String? = nil,
         variant: Variant = .primary,
         icon: String? = nil,
         isLoading: Bool = false,
         isEnabled: Bool = true,
         height: CGFloat = 52,
         fontSize: CGFloat = 14,
         action: @escaping () -> Void) {
        self.title = title
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
                        .font(.system(size: 18, weight: .semibold))
                }
                Text(isLoading ? (loadingTitle ?? title) : title)
                    .font(.system(size: fontSize, weight: .semibold))
                    .lineLimit(1)
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
        .accessibilityLabel(isLoading ? (loadingTitle ?? title) : title)
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

/// Press feedback: scale to 0.97 over 120ms with the standard curve
/// (desktop's hover-lift has no iOS equivalent).
struct PressScaleButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(BirdoTheme.Motion.easeStandard(0.12), value: configuration.isPressed)
    }
}
