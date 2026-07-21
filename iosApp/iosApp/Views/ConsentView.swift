import SwiftUI
import UIKit

/// GDPR consent gate — shown once, on first launch, BEFORE Login (routing:
/// `!authVM.hasConsented`). Copy is exact per spec-auth-flow.md §0 /
/// spec-home-servers-consent.md §5; visuals keep the legacy dark consent card
/// (`surfaceVariant`) over the PixelCanvas ambient grid.
///
/// Android's Decline terminates the app (finishAffinity). iOS cannot
/// self-exit, so declining stays here and briefly emphasises the
/// required-notice footnote instead (recon warning 12).
struct ConsentView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.openURL) private var openURL

    @StateObject private var pixelModel = PixelGridModel()

    /// Lifts the footnote out of its faint tier for a moment after Decline.
    @State private var declineEmphasis = false
    @State private var emphasisResetTask: Task<Void, Never>?

    var body: some View {
        ZStack {
            // Opaque black base + own canvas — Consent is a root route and
            // must fully occlude whatever sits behind it.
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView(model: pixelModel)

            ScrollView {
                VStack(spacing: 0) {
                    Image(systemName: "shield.fill")
                        .font(.system(size: 64))
                        .foregroundStyle(BirdoTheme.accent)
                        .accessibilityLabel("Privacy")

                    Text("Your Privacy Matters")
                        .font(.system(size: 24, weight: .bold))
                        .foregroundStyle(BirdoTheme.onBackground)
                        .multilineTextAlignment(.center)
                        .padding(.top, 16)

                    Text("Before using Birdo VPN, please review how your data is handled.")
                        .font(.system(size: 14))
                        .foregroundStyle(BirdoTheme.white60)
                        .multilineTextAlignment(.center)
                        .lineSpacing(4)
                        // Without this a multi-line Text can be truncated
                        // instead of growing at larger Dynamic Type sizes.
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)

                    dataSummaryCard
                        .padding(.top, 24)

                    Button {
                        if let url = URL(string: "https://birdo.app/privacy") { openURL(url) }
                    } label: {
                        Text("Read the full Privacy Policy")
                            .font(.system(size: 14))
                            .underline()
                            .foregroundStyle(BirdoTheme.accent)
                            .frame(minHeight: 44) // touch target
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    .padding(.top, 16)

                    // acceptConsent() persists the flag AND the
                    // privacyConsentTimestamp — nothing else to do here.
                    PrimaryButton("I Agree & Continue",
                                  variant: .brand,
                                  fontSize: 16,
                                  action: { authVM.acceptConsent() })
                        .padding(.top, 24)
                        .accessibilityIdentifier("consent_accept")

                    declineButton
                        .padding(.top, 12)

                    Text("You must accept the privacy policy to use Birdo VPN.")
                        .font(.system(size: 12))
                        .foregroundStyle(declineEmphasis ? BirdoTheme.white80 : BirdoTheme.white20)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)
                }
                .frame(maxWidth: 480) // iPad parity (Android AdaptiveContainer)
                .frame(maxWidth: .infinity)
                .padding(.horizontal, 24)
                .padding(.top, 48)
                .padding(.bottom, 32)
            }
        }
        .pixelCanvasTouchTrail(pixelModel)
        .onDisappear { emphasisResetTask?.cancel() }
    }

    // MARK: - Data summary card

    private var dataSummaryCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            consentItem(
                title: "No Activity Logs",
                description: "Birdo VPN operates a strict zero-logs policy on RAM-only volatile infrastructure. No browsing activity, DNS queries, traffic content, connection timestamps, or IP addresses are logged.")
            consentItem(
                title: "Account Data Only",
                description: "Only your email, subscription status, and aggregate bandwidth are stored in a separate account database — never on VPN servers.")
            consentItem(
                title: "Crash Reports",
                description: "Anonymous crash reports help fix bugs faster. No personal data is included.")
            consentItem(
                title: "No Data Sales",
                description: "Your data is never sold, shared with advertisers, or used for profiling.")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                .fill(BirdoTheme.surfaceVariant)
        )
    }

    private func consentItem(title: String, description: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(BirdoTheme.onBackground)
            Text(description)
                .font(.system(size: 13))
                .foregroundStyle(BirdoTheme.white60)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    // MARK: - Decline

    private var declineButton: some View {
        Button(action: decline) {
            Text("Decline")
                .font(.system(size: 14))
                .foregroundStyle(BirdoTheme.white40)
                .frame(maxWidth: .infinity, minHeight: 48)
                .overlay(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .strokeBorder(BirdoTheme.white20, lineWidth: 1)
                )
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityIdentifier("consent_decline")
    }

    /// iOS cannot terminate itself — stay on Consent and draw the eye to the
    /// footnote so declining reads as "blocked", not broken.
    private func decline() {
        authVM.declineConsent()
        UINotificationFeedbackGenerator().notificationOccurred(.warning)
        emphasisResetTask?.cancel()
        withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard)) {
            declineEmphasis = true
        }
        emphasisResetTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(1.6))
            guard !Task.isCancelled else { return }
            withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard)) {
                declineEmphasis = false
            }
        }
    }
}
