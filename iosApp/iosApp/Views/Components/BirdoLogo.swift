import SwiftUI

/// Brand mark wrapper around `Image("BrandMark")` (the 1024px phoenix asset).
/// Spec: spec-pixelcanvas-design.md §6 — boxed icon for small identity slots,
/// hero mark (72pt + soft emerald halo) for Login/Consent. "Clean, rounded,
/// clear" — never a hard square box on hero placements.
struct BirdoLogo: View {
    enum Style {
        /// Bare mark at a given point size.
        case plain(size: CGFloat)
        /// Rounded-clipped mark (status badge 26/r6, top bar 32/r10).
        case boxed(size: CGFloat, cornerRadius: CGFloat)
        /// 72pt mark with a radial emerald halo behind it.
        case hero
    }

    let style: Style

    init(_ style: Style = .boxed(size: 32, cornerRadius: 10)) {
        self.style = style
    }

    /// 26pt / r6 — the "Secure Connection" status badge slot.
    static let badge = BirdoLogo(.boxed(size: 26, cornerRadius: 6))
    /// 32pt / r10 — the Home top-bar brand lockup slot.
    static let topBar = BirdoLogo(.boxed(size: 32, cornerRadius: 10))
    /// 72pt hero mark for Login/Consent.
    static let hero = BirdoLogo(.hero)

    var body: some View {
        Group {
            switch style {
            case .plain(let size):
                mark(size)
            case .boxed(let size, let radius):
                mark(size)
                    .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            case .hero:
                ZStack {
                    // Halo extends 22% beyond the mark on every side.
                    Circle()
                        .fill(
                            RadialGradient(
                                stops: [
                                    .init(color: BirdoTheme.accentA(0.20), location: 0),
                                    .init(color: BirdoTheme.accentA(0.06), location: 0.45),
                                    .init(color: .clear, location: 0.68),
                                ],
                                center: .center, startRadius: 0, endRadius: 52
                            )
                        )
                        .frame(width: 104, height: 104)
                    mark(72)
                }
            }
        }
        .accessibilityHidden(true) // decorative — screens carry their own titles
    }

    private func mark(_ size: CGFloat) -> some View {
        Image("BrandMark")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
    }
}
