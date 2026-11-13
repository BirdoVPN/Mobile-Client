import Foundation

/// Account deletion (GDPR Art. 17) — the pure decisions behind
/// `AuthViewModel.deleteAccount`, kept Foundation-only so the unit-test bundle
/// can pin them (AuthViewModel and APIClient cannot be compiled into it).
///
/// Owner item 43 (2026-10-01): the delete request goes FIRST. Only a server
/// that has CONFIRMED the erasure gets the tunnel torn down, the credentials
/// cleared and the user signed out; a refusal leaves the user connected and
/// signed in, with the reason on screen. (It used to disconnect the VPN before
/// sending, so a wrong password or a 5xx cost the user their tunnel for
/// nothing.)
enum AccountDeletion {
    /// Did the request possibly reach the server, with the answer lost?
    ///
    /// This is NOT an edge case while connected. The request rides the tunnel,
    /// and the erasure revokes every peer of the account on the nodes BEFORE
    /// the server answers (birdo-web `GdprService.deleteUserData`, B5), so the
    /// answer to a successful deletion is routed to a peer that no longer
    /// exists and never arrives: the request times out on a deleted account.
    /// Reading that timeout as "deletion failed" would leave the user signed
    /// in to nothing, behind a dead tunnel.
    ///
    /// So these errors mean UNKNOWN, and the caller checks: tunnel down, then
    /// `GET /auth/me` — a 401 there is a confirmed deletion.
    ///
    /// Transport errors that prove the request never left (no route, DNS, TLS
    /// or pin failure) are definitive failures instead: nothing was sent.
    static func outcomeIsUnknown(_ error: Error) -> Bool {
        guard let urlError = error as? URLError else { return false }
        switch urlError.code {
        case .timedOut, .networkConnectionLost, .badServerResponse,
             .cannotParseResponse, .zeroByteResource:
            return true
        default:
            return false
        }
    }

    /// Owner item 97: should deletion re-authenticate with Sign in with Apple
    /// first?
    ///
    /// `/auth/me` does not say whether an account is linked to Apple, so the
    /// app offers it when it KNOWS: this device's session was signed in with
    /// Apple, or the account's email is the backend's Apple alias
    /// (`apple_<hash>@appleid.local`, used when Apple shares no real address)
    /// or an Apple private-relay address. An Apple-linked account signed in
    /// some other way on this device is not detected; it deletes as before.
    static func offersAppleReauth(signedInWithAppleOnThisDevice: Bool, accountEmail: String?) -> Bool {
        if signedInWithAppleOnThisDevice { return true }
        guard let email = accountEmail?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased(),
              !email.isEmpty else { return false }
        return email.hasSuffix("@appleid.local") || email.hasSuffix("@privaterelay.appleid.com")
    }

    /// MR-179 (owner 2026-11-13): the digits of what the user typed, so a
    /// grouped or pasted account number ("1234 5678 ...") or e-mailed code
    /// reaches the server as plain digits.
    static func digitsOnly(_ text: String) -> String {
        String(text.filter { $0.isASCII && $0.isNumber })
    }

    /// An anonymous account number is 24 digits.
    static func isCompleteAccountNumber(_ text: String) -> Bool {
        digitsOnly(text).count == 24
    }

    /// The code `POST /api/v1/gdpr/delete/code` e-mails is 6 digits.
    static func isCompleteEmailCode(_ text: String) -> Bool {
        digitsOnly(text).count == 6
    }

    /// Client-side completeness gate for the deletion dialog's code field —
    /// the same rule as the login 2FA step (the server is authoritative): a
    /// 6-digit TOTP, or a hex backup code in 4-character groups, dashes
    /// optional.
    static func isCompleteTwoFactorCode(_ code: String) -> Bool {
        let c = code.trimmingCharacters(in: .whitespaces)
        if c.range(of: #"^\d{6}$"#, options: .regularExpression) != nil { return true }
        return c.range(of: #"^[0-9A-Fa-f]{4}(-?[0-9A-Fa-f]{4})+$"#,
                       options: .regularExpression) != nil
    }
}

/// Body of `DELETE /api/v1/gdpr/delete`.
///
/// Blank values are dropped and nil fields are OMITTED (synthesized
/// `Encodable` skips them), so a password-less account sends `{}` exactly as
/// before, and the new fields only appear when there is something to say.
/// The backend's schema is a non-strict zod object: an older deploy strips
/// keys it does not know rather than rejecting them.
struct DeleteAccountBody: Encodable, Equatable {
    let password: String?
    /// Owner item 85: a 6-digit TOTP or a backup code, sent after the server
    /// answered `two_factor_required`.
    let twoFactorCode: String?
    /// Owner item 97: a fresh Sign in with Apple `authorizationCode`, so the
    /// backend can revoke the app's Apple tokens with the account.
    let appleAuthorizationCode: String?
    /// MR-179: the 24-digit number an anonymous account re-enters (digits only).
    let accountNumber: String?
    /// MR-179: the 6-digit code e-mailed to an SSO account with no 2FA.
    let emailCode: String?
    /// MR-179: a fresh Sign in with Apple identity token (and the raw nonce it
    /// was requested with) for an Apple account that has no e-mail address.
    let appleIdentityToken: String?
    let appleNonce: String?

    init(password: String?,
         twoFactorCode: String? = nil,
         appleAuthorizationCode: String? = nil,
         accountNumber: String? = nil,
         emailCode: String? = nil,
         appleIdentityToken: String? = nil,
         appleNonce: String? = nil) {
        self.password = Self.nonBlank(password)
        self.twoFactorCode = Self.nonBlank(twoFactorCode)
        self.appleAuthorizationCode = Self.nonBlank(appleAuthorizationCode)
        self.accountNumber = Self.nonBlank(accountNumber.map(AccountDeletion.digitsOnly))
        self.emailCode = Self.nonBlank(emailCode.map(AccountDeletion.digitsOnly))
        self.appleIdentityToken = Self.nonBlank(appleIdentityToken)
        self.appleNonce = Self.nonBlank(appleNonce)
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let trimmed = value?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else { return nil }
        return trimmed
    }
}

/// The deletion refusals the app acts on by CODE (owner item 85).
///
/// For an account with two-factor authentication the backend answers
///   * 403 `{"error":"two_factor_required"}` — no code was sent;
///   * 403 `{"error":"two_factor_invalid"}`  — the code was wrong;
/// (and 429 for too many attempts, which the generic mapping already covers).
/// GlobalExceptionFilter puts a thrown exception's `error` on the wire
/// verbatim; `details.code`, `code` and a bare `message` are read too, so the
/// classification survives the backend choosing any of them. Matching is on
/// the exact code, never on prose, so nothing else can be mistaken for it.
enum DeletionRefusal: Error, Equatable, Sendable {
    case twoFactorRequired
    case twoFactorInvalid
    /// MR-179: the deletion step-up (birdo-web deletion-step-up.ts). Nothing
    /// was deleted; the dialog asks for, or says it was wrong, the proof.
    case accountNumberRequired
    case accountNumberInvalid
    case emailCodeRequired
    case emailCodeInvalid
    case appleReauthRequired
    case appleReauthInvalid

    static func classify(status: Int, body: Data) -> DeletionRefusal? {
        // Any 4xx: the contract says 403, but a 401 carrying the same code is
        // the same refusal (the retry after the token refresh reaches here).
        guard (400..<500).contains(status),
              let object = try? JSONSerialization.jsonObject(with: body),
              let json = object as? [String: Any] else { return nil }
        var candidates: [String] = []
        for key in ["error", "code", "message"] {
            if let value = json[key] as? String { candidates.append(value) }
        }
        if let details = json["details"] as? [String: Any],
           let code = details["code"] as? String {
            candidates.append(code)
        }
        for raw in candidates {
            switch raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "two_factor_required": return .twoFactorRequired
            case "two_factor_invalid": return .twoFactorInvalid
            case "account_number_required": return .accountNumberRequired
            case "account_number_invalid": return .accountNumberInvalid
            case "email_code_required": return .emailCodeRequired
            case "email_code_invalid": return .emailCodeInvalid
            case "apple_reauth_required": return .appleReauthRequired
            case "apple_reauth_invalid": return .appleReauthInvalid
            default: continue
            }
        }
        return nil
    }
}

/// `stepUp` of `GET /api/v1/gdpr/delete/preflight` (MR-179): the server's
/// factor tokens for this account (`password`, `account_number`, `email_code`,
/// `apple_reauth`, `two_factor`) and whether it refuses a deletion without
/// them. Every field optional: an older backend sends none.
struct DeletionStepUp: Decodable, Sendable, Equatable {
    let factors: [String]?
    let enforced: Bool?

    var needsAccountNumber: Bool { factors?.contains("account_number") ?? false }
    var needsEmailCode: Bool { factors?.contains("email_code") ?? false }
    var needsAppleReauth: Bool { factors?.contains("apple_reauth") ?? false }
}

/// `POST /api/v1/gdpr/delete/code` reply: the masked address the code went to.
struct DeletionCodeSent: Decodable, Sendable, Equatable {
    let sentTo: String?
}

/// What a Sign in with Apple re-authentication for an account deletion yields
/// (MR-179): the `authorizationCode` for token revocation, and the identity
/// token plus the RAW nonce that prove a fresh Apple sign-in to the backend
/// (`apple_reauth`). Lives here, Foundation-only, so the test bundle compiles
/// it with its redaction (AppleSignInService.swift is not in that bundle).
struct DeletionAppleProof: Equatable, Sendable {
    let authorizationCode: String
    let identityToken: String?
    let nonce: String
}
