import Foundation

/// Guest-shell policy: what the app does BEFORE (and without) an account.
///
/// Why this file exists — App Store Guideline 5.1.1(v), macOS 1.4.22 rejection
/// (10 Aug 2026): "The app requires users to register or log in to access
/// features that are not account based." Everything in Settings and VPN
/// Settings (theme, kill switch, post-quantum, LAN sharing, custom DNS,
/// WireGuard port, MTU), the privacy policy, the terms, the about card and the
/// location list are not account based, and all of them sat behind a login
/// wall at launch.
///
/// The rules encoded here:
///   1. The app opens into its normal shell with no account (`RootRoute`).
///   2. Account-gated actions ask for sign-in AT THE POINT OF USE
///      (`SignInReason`) instead of walling the launch.
///   3. Creating an anonymous account is the FIRST and easiest option, but it
///      is never the only way in — and when it fails it must not dead-end the
///      user (`AnonymousCreateFailure`).
///
/// Foundation-only on purpose: this file is compiled into the un-hosted
/// `BirdoVPNTests` bundle (see iosApp/project.yml), so the policy is unit
/// tested without an app host, a KMP framework or a network.

// MARK: - Root route

/// What the app root shows. There is deliberately no `.login` case: sign-in is
/// a sheet raised on demand, never a route that owns the whole window.
enum RootRoute: Equatable, Sendable {
    /// First-run privacy disclosure. Shown once, before anything else.
    case consent
    /// The normal tabbed app — signed in OR guest.
    case shell

    /// - Parameters:
    ///   - hasConsented: the user accepted the privacy disclosure.
    ///   - consentDeferred: the user chose "Not now" on the disclosure. They
    ///     still get the whole guest shell; consent is asked for again before
    ///     an account is created or signed into, which is the only point at
    ///     which personal data is processed.
    ///
    /// NOTE the deliberate absence of `isLoggedIn`: being signed out can no
    /// longer change the route. That is the whole 5.1.1(v) fix, and putting it
    /// in a pure function is what lets a test assert it can never regress.
    static func decide(hasConsented: Bool, consentDeferred: Bool) -> RootRoute {
        (hasConsented || consentDeferred) ? .shell : .consent
    }
}

// MARK: - Point-of-use sign-in prompts

/// Why the sign-in sheet was raised. Each case carries the honest reason the
/// action needs an account — the server mints a per-account WireGuard peer and
/// allocates a connection slot, so connecting genuinely cannot work without
/// one. Anything that does NOT need an account must never raise this sheet.
enum SignInReason: String, Equatable, Sendable, CaseIterable {
    /// Home's Connect CTA.
    case connect
    /// Picking a location to connect to (browsing them needs no account).
    case servers
    /// The Limit tab's data-usage figures.
    case usage
    /// The Profile tab's identity/subscription card.
    case profile
    /// The Multi-Hop entry chip.
    case multiHop
    /// Raised from somewhere with no more specific story.
    case generic

    /// Sheet headline.
    var title: String {
        switch self {
        case .connect:  return "Sign in to connect"
        case .servers:  return "Sign in to use a location"
        case .usage:    return "Sign in to see your usage"
        case .profile:  return "Sign in to see your account"
        case .multiHop: return "Sign in to use Multi-Hop"
        case .generic:  return "Sign in to Birdo VPN"
        }
    }

    /// One line under the headline saying WHY this particular thing needs an
    /// account, plus the standing reassurance that the rest of the app does
    /// not. Never claim an account is needed for something that isn't.
    var message: String {
        switch self {
        case .connect, .servers, .multiHop:
            return "Connecting needs an account: the server creates a private "
                + "WireGuard key for you and holds a connection slot. Settings, "
                + "locations and the policies stay open without one."
        case .usage:
            return "Your data usage belongs to an account. Everything else on "
                + "this screen works without one."
        case .profile:
            return "Your plan and account details need an account. The privacy "
                + "policy and terms stay readable without one."
        case .generic:
            return "An account is only needed to connect. Settings, locations "
                + "and the policies work without one."
        }
    }
}

// MARK: - Anonymous account creation failures

/// Copy for a failed `POST /auth/register/anonymous`.
///
/// 🔴 THE ONE THAT MATTERS: that endpoint is rate limited to **3 creations per
/// IP per hour**. App Review runs from shared addresses, so a reviewer tapping
/// "Create a new anonymous account" can very plausibly get a 429 through no
/// fault of their own. If that reads as "the app is broken", a 5.1.1 fix turns
/// into a fresh 2.1 App Completeness rejection. So the 429 copy has to say
/// three things: it is the network, not you; the app still works without an
/// account; and here is how to get in anyway.
///
/// Classification is on the HTTP STATUS only — never on the backend's message
/// text, which is copy that changes without notice.
enum AnonymousCreateFailure {
    /// The whole point of the guest shell: nothing here is a dead end.
    static let stillUsableSuffix =
        "You can keep using the app without an account — settings and locations "
        + "stay open — or sign in with an existing account."

    /// - Parameters:
    ///   - status: HTTP status the failure arrived on, or nil when the client
    ///     minted the error itself (transport failure, unusable 2xx body).
    ///   - serverText: the backend's own words, when it explained itself.
    ///   - isOffline: the transport says there is no connection at all.
    static func message(status: Int?, serverText: String?, isOffline: Bool = false) -> String {
        if isOffline {
            return "No internet connection, so the account could not be created. "
                + stillUsableSuffix
        }
        switch status {
        case 429:
            // 3 per IP per hour (auth.controller.ts `rl:anon-register:ip:*`).
            // Name the network, give the wait, and offer the two other doors.
            return "Too many anonymous accounts have been created from this "
                + "network in the past hour, so this one was refused. Try again "
                + "in about an hour. " + stillUsableSuffix
        case 401:
            // Missing/blocked X-Desktop-Client header — a build problem, not
            // a user problem. Say so rather than implying bad credentials.
            return "This build could not create an account (the server refused "
                + "the request). " + stillUsableSuffix
        case .some(let code) where code >= 500:
            return "The account service is temporarily unavailable (error \(code)). "
                + stillUsableSuffix
        case .some(let code):
            // Some other refusal the backend explained: show its words, then
            // the way out.
            if let text = serverText, !text.isEmpty {
                return "\(text) " + stillUsableSuffix
            }
            return "Could not create an anonymous account (error \(code)). "
                + stillUsableSuffix
        case .none:
            if let text = serverText, !text.isEmpty {
                return "\(text) " + stillUsableSuffix
            }
            return "Could not create an anonymous account. " + stillUsableSuffix
        }
    }

    /// True when the failure is worth offering a "Try again" button for. A 429
    /// is NOT: retrying inside the hour burns another attempt and fails again,
    /// which is exactly how a reviewer concludes the app is broken.
    static func isImmediatelyRetryable(status: Int?) -> Bool {
        switch status {
        case 429: return false
        case 401: return false
        default:  return true
        }
    }
}
