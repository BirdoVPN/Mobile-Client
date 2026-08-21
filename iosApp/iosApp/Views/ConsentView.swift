import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// GDPR privacy disclosure. Two presentations, one screen:
///
///   * FIRST LAUNCH (`isSheet == false`) — the app's first screen. "I Agree &
///     Continue" accepts; "Not now" DEFERS into the guest shell.
///   * ON THE SIGN-IN SHEET (`isSheet == true`) — shown ahead of LoginView to
///     a user who deferred, because creating or signing into an account is the
///     point at which personal data is actually processed. Accepting swaps the
///     sheet to LoginView; "Not now" closes the sheet.
///
/// Copy follows spec-auth-flow.md §0 / spec-home-servers-consent.md §5;
/// visuals keep the legacy dark consent card (`surfaceVariant`) over the
/// PixelCanvas ambient grid.
///
/// 🔴 The old secondary action was "Decline", which set the flag false and
/// kept the user on this screen forever with "You must accept the privacy
/// policy to use Birdo VPN" (Android exits via finishAffinity; iOS cannot
/// self-exit). That was a dead end AND untrue of the parts of the app that
/// process nothing — settings, the policies, the location list. It is now a
/// deferral. Do not restore the dead end.
struct ConsentView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.openURL) private var openURL

    /// Presented inside the sign-in sheet rather than as the first-launch
    /// route. Explicit init below: the private @State properties would
    /// otherwise make the synthesized memberwise init private.
    private let isSheet: Bool

    @StateObject private var pixelModel = PixelGridModel()

    init(isSheet: Bool = false) {
        self.isSheet = isSheet
    }

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

                    Text(isSheet
                            ? "Before you create or sign in to an account, please review how your data is handled."
                            : "Before using Birdo VPN, please review how your data is handled.")
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

                    deferButton
                        .padding(.top, 12)

                    Text(isSheet
                            ? "Accepting is required only to create or sign in to an account. "
                                + "The rest of the app keeps working without one."
                            : "You can use the app's settings, read the policies and browse "
                                + "locations without accepting. Accepting is required only to "
                                + "create or sign in to an account.")
                        .font(.system(size: 12))
                        .foregroundStyle(BirdoTheme.white40)
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
    }

    // MARK: - Data summary card

    private var dataSummaryCard: some View {
        VStack(alignment: .leading, spacing: 16) {
            consentItem(
                title: "No Activity Logs",
                // Scoped to the VPN traffic plane, where the claim is literally
                // true: the WireGuard nodes run RAM-only with no persistent
                // storage (backend abuse.service.ts / no-logs-enforcement).
                // The earlier copy also said "IP addresses are logged" in
                // absolute terms, which the account DB contradicts (see next
                // item) — App Review / regulators penalise false absolutes.
                description: "On our VPN servers, Birdo operates a strict zero-logs policy on RAM-only volatile infrastructure. Your browsing activity, DNS queries, traffic content, and the IP addresses you visit are never monitored, logged, or stored.")
            consentItem(
                title: "Account Data Only",
                // Truthful disclosure: the separate account database keeps
                // login/session timestamps and a non-reversible hash of your
                // IP for security and abuse prevention — it is not stored in
                // the clear and never lives on the VPN servers.
                description: "Your email, subscription status, and aggregate bandwidth are stored in a separate account database — never on the VPN servers. For security and abuse prevention that database also keeps sign-in timestamps and a non-reversible hash of your IP address, not your raw IP.")
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

    // MARK: - Not now (deferral, never a dead end)

    private var deferButton: some View {
        Button(action: defer_) {
            Text(isSheet ? "Not now" : "Not now — look around first")
                .font(.system(size: 14))
                .foregroundStyle(BirdoTheme.white60)
                .frame(maxWidth: .infinity, minHeight: 48)
                .overlay(
                    RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                        .strokeBorder(BirdoTheme.white20, lineWidth: 1)
                )
        }
        .buttonStyle(PressScaleButtonStyle())
        // Identifier kept as `consent_decline` so the screenshot UI test and
        // any existing automation still find this button.
        .accessibilityIdentifier("consent_decline")
    }

    /// First launch: fall through to the guest shell. On the sign-in sheet:
    /// close it — the user stays exactly where they were, signed out.
    /// (`defer` is a Swift keyword, hence the trailing underscore.)
    private func defer_() {
        if isSheet {
            authVM.dismissSignIn()
        } else {
            authVM.deferConsent()
        }
    }
}
