import AuthenticationServices
import CryptoKit
import Foundation
import Security

#if canImport(UIKit)
import UIKit
#endif

/// Sign in with Apple — the native flow.
///
/// ## Why this exists at all
///
/// App Review guideline 4.8: an app that offers a third-party or social login
/// service must ALSO offer an equivalent privacy-preserving option, and Sign in
/// with Apple is the one Apple accepts. `LoginView` ships "Continue with Google"
/// and "Continue with GitHub", so the requirement attaches. This is not a
/// nice-to-have; without it the app is rejectable on sight.
///
/// ## Why it does not look like `SSOService`
///
/// Google and GitHub round-trip through the web PKCE broker because they need a
/// confidential client secret that must never sit in the app. Apple does not:
/// `ASAuthorizationController` hands the app a **signed identity token**
/// directly, so there is no code to exchange and no secret here. The token *is*
/// the assertion, and the backend's only job is to prove it came from Apple and
/// was minted for this bundle id.
///
/// ## The nonce, which is the part that is easy to get wrong
///
/// A raw nonce is generated per attempt. Its **SHA-256** goes to Apple in the
/// request; the **raw** value goes to our backend alongside the token. Apple
/// embeds the hash in the token's `nonce` claim, and the server hashes what we
/// sent and compares. Sending the raw value to Apple, or the hash to the
/// backend, both "work" in the sense that sign-in completes — and both silently
/// destroy the replay protection, because the comparison then either always
/// fails or is checking a value an attacker already holds. That is why the two
/// directions are named explicitly below rather than left to a variable name.
@MainActor
final class AppleSignInService: NSObject {
    static let shared = AppleSignInService()

    /// Mirrors `SSOService.Outcome` exactly, so `AuthViewModel` handles both
    /// providers through the same three cases. `cancelled` covers the user
    /// dismissing the sheet — Android stays silent for that and so do we.
    enum Outcome: Sendable {
        case completed(LoginResult)
        case cancelled
    }

    enum AppleSignInError: Error, LocalizedError, Sendable {
        case noIdentityToken
        case unavailable

        var errorDescription: String? {
            switch self {
            case .noIdentityToken:
                return "Apple did not return a sign-in token. Please try again."
            case .unavailable:
                return "Sign in with Apple is unavailable on this device."
            }
        }
    }

    private var continuation: CheckedContinuation<ASAuthorization?, Error>?

    private let api: APIClient

    init(api: APIClient = .shared) {
        self.api = api
        super.init()
    }

    /// Runs the whole flow: present Apple's sheet, take the identity token, and
    /// exchange it at `POST /auth/apple/native`.
    func signIn() async throws -> Outcome {
        let rawNonce = Self.randomNonce()

        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName, .email]
        // HASHED to Apple.
        request.nonce = Self.sha256(rawNonce)

        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self

        let authorization: ASAuthorization? = try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            controller.performRequests()
        }

        guard let authorization else { return .cancelled }

        guard
            let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
            let tokenData = credential.identityToken,
            let identityToken = String(data: tokenData, encoding: .utf8)
        else {
            throw AppleSignInError.noIdentityToken
        }

        // RAW to our backend. See the note above.
        let result = try await api.loginWithApple(identityToken: identityToken, nonce: rawNonce)
        return .completed(result)
    }

    // MARK: - Nonce

    /// 32 bytes from the system CSPRNG, hex-encoded.
    ///
    /// Hex rather than base64url because the value travels through a JWT claim
    /// and a JSON body, and the backend bounds it at 8...256 characters — 64 hex
    /// characters sits inside that with no padding or URL-safety to get wrong.
    private static func randomNonce() -> String {
        var bytes = [UInt8](repeating: 0, count: 32)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        if status != errSecSuccess {
            // Never silently fall back to a weaker source: a predictable nonce
            // is worse than no nonce, because the server would still report the
            // check as passing.
            fatalError("SecRandomCopyBytes failed with status \(status)")
        }
        return bytes.map { String(format: "%02x", $0) }.joined()
    }

    private static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8))
            .map { String(format: "%02x", $0) }
            .joined()
    }
}

// MARK: - Delegate

extension AppleSignInService: ASAuthorizationControllerDelegate {
    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithAuthorization authorization: ASAuthorization
    ) {
        MainActor.assumeIsolated {
            let cont = self.continuation
            self.continuation = nil
            cont?.resume(returning: authorization)
        }
    }

    nonisolated func authorizationController(
        controller: ASAuthorizationController,
        didCompleteWithError error: Error
    ) {
        MainActor.assumeIsolated {
            let cont = self.continuation
            self.continuation = nil
            // A user-cancelled sheet is not an error worth surfacing; every
            // other ASAuthorizationError is.
            if let asError = error as? ASAuthorizationError, asError.code == .canceled {
                cont?.resume(returning: nil)
            } else {
                cont?.resume(throwing: error)
            }
        }
    }
}

// MARK: - Presentation Context

extension AppleSignInService: ASAuthorizationControllerPresentationContextProviding {
    /// Same shape as `SSOService`'s anchor, and the same reasoning: fall back to
    /// a bare anchor rather than force-unwrapping, so sign-in can never be the
    /// thing that crashes the app when no window is key yet.
    nonisolated func presentationAnchor(
        for controller: ASAuthorizationController
    ) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            #if os(iOS)
            return UIApplication.shared.connectedScenes
                .compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows)
                .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
            #else
            return ASPresentationAnchor()
            #endif
        }
    }
}
