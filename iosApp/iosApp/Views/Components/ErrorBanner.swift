import SwiftUI

/// Error banner. Plain style (no icon) is the Login form banner: 10%-red fill,
/// radius 12, centered 12pt red text (spec-auth-flow.md §1 item 4). Passing an
/// `icon` switches to the Home banner layout: radius 14, red@30% border,
/// leading 18pt icon + left-aligned 13pt text (spec-home-servers-consent.md
/// §3.4). Announced assertively to VoiceOver on appearance and change.
///
/// Present conditionally and wrap the state change in `withAnimation` — the
/// fade + scale(0.95) transition is attached here.
struct ErrorBanner: View {
    let message: String
    var icon: String?

    init(_ message: String, icon: String? = nil) {
        self.message = message
        self.icon = icon
    }

    var body: some View {
        Group {
            if let icon {
                HStack(spacing: 10) {
                    Image(systemName: icon)
                        .font(.system(size: 18))
                    Text(message)
                        .font(.system(size: 13))
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .foregroundStyle(BirdoTheme.red)
                .padding(14)
                .background(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                        .fill(BirdoTheme.redBg)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.md, style: .continuous)
                        .strokeBorder(BirdoTheme.red.opacity(0.3), lineWidth: 1)
                )
            } else {
                Text(message)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.red)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
                    .padding(12)
                    .background(
                        RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                            .fill(BirdoTheme.redBg)
                    )
            }
        }
        .transition(.opacity.combined(with: .scale(scale: 0.95)))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(message)
        .onChange(of: message, initial: true) { _, newValue in
            AccessibilityNotification.Announcement(newValue).post()
        }
    }
}
