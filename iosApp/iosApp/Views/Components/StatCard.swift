import SwiftUI

/// Connected-stats tile: BirdoCard radius 12, padding 8x8, centered 14pt icon
/// + 6pt gap + 12pt semibold tabular value. The label is ACCESSIBILITY-ONLY —
/// Android renders icon + value with no visible caption (spec warning 2).
/// Spec: spec-home-servers-consent.md §3.5.
struct StatCard: View {
    let icon: String
    let iconColor: Color
    let value: String
    /// VoiceOver label ("Duration", "Download", "Upload") — never rendered.
    let label: String

    init(icon: String, iconColor: Color, value: String, label: String) {
        self.icon = icon
        self.iconColor = iconColor
        self.value = value
        self.label = label
    }

    var body: some View {
        BirdoCard(cornerRadius: BirdoTheme.Radius.sub,
                  horizontalPadding: 8,
                  verticalPadding: 8) {
            HStack(spacing: 6) {
                Image(systemName: icon)
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(iconColor)
                Text(value)
                    .font(BirdoTheme.Fonts.statValue)
                    .monospacedDigit()
                    .foregroundStyle(BirdoTheme.onSurface)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(label): \(value)")
    }
}
