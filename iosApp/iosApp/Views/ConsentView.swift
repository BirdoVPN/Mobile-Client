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

                    Text(subtitle)
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

                    // BOTH documents are linked, because accepting accepts both
                    // (audit 2026-09-29, B-13: accepting here used to cover the
                    // privacy policy only, and the Terms were never presented).
                    // Grouped so the enclosing builder stays well under its
                    // child limit.
                    VStack(spacing: 0) {
                        policyLink("Read the Terms of Service", "https://birdo.app/terms")
                        policyLink("Read the full Privacy Policy", "https://birdo.app/privacy")

                        // The Terms' minimum age, stated where the account is created.
                        Text("You must be 18 or over to use BirdoVPN.")
                            .font(.system(size: 13, weight: .semibold))
                            .foregroundStyle(BirdoTheme.white60)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 8)
                    }
                    .padding(.top, 16)

                    // acceptConsent() persists the flag, the consent VERSION
                    // (ConsentRecord, owner item 38) and the timestamp —
                    // nothing else to do here.
                    PrimaryButton("I Agree & Continue",
                                  variant: .brand,
                                  fontSize: 16,
                                  action: { authVM.acceptConsent() })
                        .padding(.top, 20)
                        .accessibilityIdentifier("consent_accept")

                    // What "I Agree & Continue" means, next to the button.
                    Text("By tapping \"I Agree & Continue\" you accept the Terms of Service and the Privacy Policy.")
                        .font(.system(size: 12))
                        .foregroundStyle(BirdoTheme.white60)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 8)

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

    /// Owner item 38: a user who accepted an older disclosure is told it
    /// changed, so seeing it again does not read as the app having reset.
    private var subtitle: String {
        if isSheet {
            return "Before you create or sign in to an account, please review how your data is handled."
        }
        if authVM.isReconsent {
            // Honest about WHAT changed (audit 2026-09-29, B-1 / B-13): the
            // data description was corrected, and accepting now covers the
            // Terms as well as the Privacy Policy.
            return "We've updated this notice: it describes your data more accurately and now "
                + "covers the Terms of Service as well as the Privacy Policy. Please review it "
                + "before you carry on."
        }
        return "Before using BirdoVPN, please review how your data is handled."
    }

    // MARK: - Data summary card

    private var dataSummaryCard: some View {
        // The pre-use data declaration Apple 5.4 asks for. Wording is
        // REMEDIATION-DECISIONS §1.5 (audit 2026-09-29, P0-1 / B-1 / D-1 /
        // B-3), shared with Android and desktop.
        //
        // The old copy said the nodes ran a "strict zero-logs policy on
        // RAM-only volatile infrastructure" and called the sign-in IP hash
        // "non-reversible". Neither is true: the nodes are ordinary
        // disk-backed cloud servers, and a salted, truncated hash of an IPv4
        // address can be matched back. Never reintroduce "RAM-only",
        // "volatile", "diskless", "zero logs" or "non-reversible".
        //
        // There is NO crash-report item: the iOS/macOS app contains no
        // crash-reporting SDK, and this screen must not describe collection
        // that does not happen (it used to promise "anonymous crash reports").
        VStack(alignment: .leading, spacing: 16) {
            consentItem(
                title: "No Activity Logs",
                description: "Our VPN servers don't record the sites you visit, your DNS queries or your traffic. While you're connected, our account system keeps a live record of your session (server, device, connect time). It is deleted when you disconnect and is left out of our nightly backups. We also count your data use per billing period.")
            consentItem(
                title: "What Your Account Holds",
                description: "Your email (or anonymous account number), plan, the devices you add, and your usage totals. Full list: birdo.app/privacy.")
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

    private func policyLink(_ title: String, _ address: String) -> some View {
        Button {
            if let url = URL(string: address) { openURL(url) }
        } label: {
            Text(title)
                .font(.system(size: 14))
                .underline()
                .foregroundStyle(BirdoTheme.accent)
                .frame(minHeight: 44) // touch target
        }
        .buttonStyle(PressScaleButtonStyle())
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
