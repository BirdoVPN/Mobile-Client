import SwiftUI

/// BirdoSectionHeader: 3x12pt accent tick bar + uppercased 11pt semibold
/// title tracked 1.5 in 60%-white, padding leading 4 / top 20 / bottom 8,
/// with an optional emerald action label on the right.
/// Spec: spec-secondary-screens.md §0.3, spec-pixelcanvas-design.md §5.1.
struct SectionHeader: View {
    let title: String
    var actionLabel: String?
    var action: (() -> Void)?

    init(_ title: String, actionLabel: String? = nil, action: (() -> Void)? = nil) {
        self.title = title
        self.actionLabel = actionLabel
        self.action = action
    }

    var body: some View {
        HStack(spacing: 8) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(BirdoTheme.accent.opacity(0.85))
                .frame(width: 3, height: 12)
            Text(title.uppercased())
                .font(BirdoTheme.Fonts.sectionHeader)
                .tracking(1.5)
                .foregroundStyle(BirdoTheme.white60)
                .accessibilityAddTraits(.isHeader)
            Spacer(minLength: 0)
            if let actionLabel, let action {
                Button(action: action) {
                    Text(actionLabel)
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(BirdoTheme.accentSoft)
                }
            }
        }
        .padding(.leading, 4)
        .padding(.top, 20)
        .padding(.bottom, 8)
    }
}
