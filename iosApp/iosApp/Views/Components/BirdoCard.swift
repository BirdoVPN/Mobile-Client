import SwiftUI

/// Glass card — the workhorse container. Solid `surface` fill with the
/// signature 1pt glass-stroke gradient border (white 18% → 4% → 12%).
/// Spec: spec-home-servers-consent.md §8, spec-pixelcanvas-design.md §5.1.
///
/// The card does not force a width — wrap content in
/// `.frame(maxWidth: .infinity, alignment: .leading)` for full-width rows.
struct BirdoCard<Content: View>: View {
    var cornerRadius: CGFloat
    var fill: Color
    var horizontalPadding: CGFloat
    var verticalPadding: CGFloat
    @ViewBuilder var content: () -> Content

    init(cornerRadius: CGFloat = BirdoTheme.Radius.card,
         fill: Color = BirdoTheme.surface,
         horizontalPadding: CGFloat = 16,
         verticalPadding: CGFloat = 16,
         @ViewBuilder content: @escaping () -> Content) {
        self.cornerRadius = cornerRadius
        self.fill = fill
        self.horizontalPadding = horizontalPadding
        self.verticalPadding = verticalPadding
        self.content = content
    }

    var body: some View {
        content()
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .background(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .fill(fill)
            )
            .overlay(
                RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                    .strokeBorder(BirdoTheme.Gradients.glassStroke, lineWidth: 1)
            )
    }
}
