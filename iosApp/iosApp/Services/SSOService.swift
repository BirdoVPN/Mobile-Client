import Foundation
import UIKit
import AuthenticationServices
import CommonCrypto
import Security

/// SSO provider allow-list. The broker knows exactly `google` and `github`;
/// anything else is refused client-side before a browser ever opens.
enum SSOProvider: String, CaseIterable, Sendable {
    case google
    case github
}

/// Native PKCE broker flow (Google + GitHub) via `ASWebAuthenticationSession`,
/// mirroring Android's system-browser flow 1:1.
///
/// Sequence (spec §4):
/// 1. Generate RFC 7636 S256 PKCE (`verifier` = base64url(32 rand bytes),
///    `challenge` = base64url(SHA-256(verifier))) + anti-CSRF `state`
///    (base64url(16 rand bytes)).
/// 2. PERSIST `(verifier, state)` BEFORE opening the browser — Android learned
///    the hard way that in-memory state dies across the browser round-trip;
///    on iOS the app can still be jetsammed mid-flow. Cleared after the
///    exchange (success OR failure); only useful for ~60 s.
/// 3. Open the broker on the WEB host `https://birdo.app` (NOT api.birdo.app):
///    `/native/oauth/start?provider=…&code_challenge=…&redirect_uri=birdo%3A%2F%2Fauth&state=…`.
/// 4. Broker completes the real NextAuth flow and 302s to
///    `birdo://auth?code=<handoff>&state=…` (or `?error=auth_failed&state=…`
///    with NO code — ignored silently, matching Android).
/// 5. Verify state, then POST the single-use handoff code + verifier to
///    `https://api.birdo.app/auth/native/exchange` EXACTLY once (server holds
///    a Redis jti lock; a replay gets 401). Response is the password-login
///    shape — SSO does NOT bypass a user's 2FA.
///
/// Requires the `birdo` URL scheme registered in Info.plist
/// (`CFBundleURLTypes`) — without it the session never returns.
@MainActor
final class SSOService: NSObject {
    static let shared = SSOService()

    /// Outcome of one sign-in attempt. `cancelled` covers both the user
    /// dismissing the sheet and the broker's `auth_failed` redirect — Android
    /// surfaces nothing for either, so callers should stay silent too.
    enum Outcome: Sendable {
        case completed(LoginResult)
        case cancelled
    }

    /// User-facing failures with the exact copy from the Android flow.
    enum SSOError: Error, LocalizedError, Sendable {
        case unsupportedProvider
        case browserUnavailable
        case sessionExpired
        case stateMismatch

        var errorDescription: String? {
            switch self {
            case .unsupportedProvider: return "Unsupported sign-in provider"
            case .browserUnavailable: return "Could not open the browser to sign in."
            case .sessionExpired: return "Sign-in session expired. Please try again."
            case .stateMismatch: return "Sign-in could not be verified. Please try again."
            }
        }
    }

    private static let verifierKey = "sso_code_verifier"
    private static let stateKey = "sso_state"

    private let api: APIClient
    /// Strong reference keeps the session (and its sheet) alive while the
    /// browser round-trip runs; doubles as the single-flight guard.
    private var activeSession: ASWebAuthenticationSession?

    private init(api: APIClient = .shared) {
        self.api = api
        super.init()
    }

    /// Run the full broker flow for `provider` ("google" | "github").
    /// Returns `.cancelled` for silent outcomes; throws `SSOError` (exact
    /// user copy) or `APIError` from the exchange for everything else.
    func signIn(provider: String) async throws -> Outcome {
        // Single-flight: a second tap while the sheet is up is ignored.
        guard activeSession == nil else { return .cancelled }
        guard SSOProvider(rawValue: provider) != nil else {
            throw SSOError.unsupportedProvider
        }

        // RFC 7636 S256: challenge is the hash of the verifier's ASCII bytes.
        let verifier = Self.base64url(Self.randomBytes(32))
        let challenge = Self.base64url(Self.sha256(Data(verifier.utf8)))
        let state = Self.base64url(Self.randomBytes(16))

        // Persist BEFORE the browser opens (see class doc).
        let defaults = UserDefaults.standard
        defaults.set(verifier, forKey: Self.verifierKey)
        defaults.set(state, forKey: Self.stateKey)

        // Build the broker URL byte-exact: challenge/state are already
        // URL-safe base64url; redirect_uri is the encoded `birdo://auth`.
        var components = URLComponents(string: "https://birdo.app/native/oauth/start")!
        components.percentEncodedQuery =
            "provider=\(provider)&code_challenge=\(challenge)&redirect_uri=birdo%3A%2F%2Fauth&state=\(state)"
        guard let brokerURL = components.url else {
            clearPersistedState()
            throw SSOError.browserUnavailable
        }

        let callbackURL: URL?
        do {
            callbackURL = try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<URL?, Error>) in
                let session = ASWebAuthenticationSession(
                    url: brokerURL,
                    callbackURLScheme: "birdo"
                ) { url, error in
                    if let error {
                        if let authError = error as? ASWebAuthenticationSessionError,
                           authError.code == .canceledLogin {
                            // User dismissed the sheet — return to Login silently.
                            continuation.resume(returning: nil)
                        } else {
                            continuation.resume(throwing: SSOError.browserUnavailable)
                        }
                        return
                    }
                    continuation.resume(returning: url)
                }
                session.presentationContextProvider = self
                // Deliberately NOT ephemeral: the broker relies on the user's
                // existing browser cookies across birdo.app ↔ auth.birdo.app
                // and expires the birdo web session itself; the default
                // matches Android's system-browser behavior and lets Google
                // show its account chooser.
                self.activeSession = session
                if !session.start() {
                    self.activeSession = nil
                    continuation.resume(throwing: SSOError.browserUnavailable)
                }
            }
        } catch {
            activeSession = nil
            clearPersistedState()
            throw error
        }
        activeSession = nil

        guard let callbackURL else {
            // Cancelled sheet.
            clearPersistedState()
            return .cancelled
        }

        let query = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)?.queryItems
        let code = query?.first(where: { $0.name == "code" })?.value
        let returnedState = query?.first(where: { $0.name == "state" })?.value

        guard let code, !code.isEmpty else {
            // `birdo://auth?error=auth_failed&state=…` — no code. Android
            // ignores this silently; do the same.
            clearPersistedState()
            return .cancelled
        }
        guard let savedVerifier = defaults.string(forKey: Self.verifierKey),
              let savedState = defaults.string(forKey: Self.stateKey) else {
            clearPersistedState()
            throw SSOError.sessionExpired
        }
        guard returnedState == savedState else {
            clearPersistedState()
            throw SSOError.stateMismatch
        }

        // Exchange EXACTLY once — the handoff code is single-use (~60 s).
        // PKCE state is cleared whether the exchange succeeds or throws.
        defer { clearPersistedState() }
        let result = try await api.exchangeSsoCode(code: code, codeVerifier: savedVerifier)
        return .completed(result)
    }

    private func clearPersistedState() {
        let defaults = UserDefaults.standard
        defaults.removeObject(forKey: Self.verifierKey)
        defaults.removeObject(forKey: Self.stateKey)
    }

    // MARK: - Crypto helpers

    private static func randomBytes(_ count: Int) -> Data {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        if status != errSecSuccess {
            // Practically unreachable; keep the flow alive with the system RNG
            // rather than aborting sign-in.
            for index in bytes.indices {
                bytes[index] = UInt8.random(in: .min ... .max)
            }
        }
        return Data(bytes)
    }

    /// Base64url without padding (RFC 4648 §5) — the PKCE/state alphabet.
    private static func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func sha256(_ data: Data) -> Data {
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return Data(hash)
    }
}

// MARK: - Presentation Context

extension SSOService: ASWebAuthenticationPresentationContextProviding {
    /// The session calls this on the main thread; the protocol requirement is
    /// nonisolated, so hop back onto the main actor explicitly.
    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
        }
    }
}
