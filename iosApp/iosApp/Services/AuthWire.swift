import Foundation

// The sign-in, session and store-link wire models: the token pair, the login
// outcome, the identity from /auth/me, and every request and response body
// that carries a credential (a password, a token, the 2FA challenge and code,
// the SSO code and verifier, Apple's identity token and signed transaction).
//
// MR-1949: moved out of APIClient.swift VERBATIM (only `private` dropped from
// the declarations) so they compile into the un-hosted BirdoVPNTests bundle,
// which cannot host APIClient.swift (keychain, KMP framework, URLSession
// plumbing). That is what lets CredentialDescriptionTests check the REAL
// models' printed form. Their redacting descriptions live in
// CredentialRedaction.swift; their Codable shape is unchanged.

/// Complete token pair from a successful authentication.
struct TokenPairData: Sendable {
    let accessToken: String
    let refreshToken: String
}

/// Outcome of a credential/SSO login round: either a full token pair, or a
/// pending 2FA challenge. The challenge token belongs in ViewModel state
/// (never the keychain) and is consumed server-side only on a SUCCESSFUL
/// verify — a wrong code leaves it valid for retries.
enum LoginResult: Sendable {
    case success(TokenPairData)
    case twoFactorRequired(challengeToken: String)
}

/// Result of `POST /auth/register/anonymous`. The minted 24-digit ID is
/// returned ONCE and is the account's sole recovery credential — persist it
/// and surface it to the user before proceeding.
struct AnonymousRegistration: Sendable {
    let anonymousId: String?
    let tokens: TokenPairData
}

/// Identity model from `GET /auth/me`. Unknown wire keys are ignored and
/// every field is defaulted so a new backend field can never break decoding.
struct UserProfile: Sendable, Equatable {
    let id: String
    let email: String?
    let name: String?
    let emailVerified: Bool
    /// Drives the delete-account dialog (password confirmation). DEFAULTS
    /// TRUE when absent — fail-safe: ask for a password rather than let a
    /// password account delete without one.
    let hasPassword: Bool
    let isSSO: Bool
    /// Owner item 86: "anonymous" | "standard", when the backend sends it.
    let accountType: String?
    /// Owner item 86: the backend's own answer, when it sends one.
    let isAnonymousField: Bool?
    /// Owner item 86: the 24-digit account number, when the backend sends it.
    let accountNumber: String?

    /// Anonymous account? The explicit `/auth/me` fields win; an older
    /// backend is read from the synthetic email `anon_<24digits>@anonymous.local`
    /// (`AccountIdentity`). Never render that email — it "reads as a bug";
    /// show `Anonymous account` + the account-number card instead.
    var isAnonymous: Bool { identity.isAnonymous }

    /// The 24-digit account number; nil for non-anonymous accounts.
    var anonymousAccountNumber: String? { identity.accountNumber }

    private var identity: AccountIdentity.Resolved {
        AccountIdentity.resolve(accountType: accountType,
                                isAnonymous: isAnonymousField,
                                accountNumber: accountNumber,
                                email: email)
    }
}

extension UserProfile: Decodable {
    private enum CodingKeys: String, CodingKey {
        case id, email, name, emailVerified, hasPassword, isSSO
        case accountType, isAnonymous, accountNumber
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
        email = try c.decodeIfPresent(String.self, forKey: .email)
        name = try c.decodeIfPresent(String.self, forKey: .name)
        emailVerified = try c.decodeIfPresent(Bool.self, forKey: .emailVerified) ?? false
        hasPassword = try c.decodeIfPresent(Bool.self, forKey: .hasPassword) ?? true
        isSSO = try c.decodeIfPresent(Bool.self, forKey: .isSSO) ?? false
        // `try?`: new, optional fields. A shape this build does not expect
        // falls back to the email parsing instead of failing the whole
        // identity (which would leave `user == nil` on every launch).
        accountType = (try? c.decodeIfPresent(String.self, forKey: .accountType)) ?? nil
        isAnonymousField = (try? c.decodeIfPresent(Bool.self, forKey: .isAnonymous)) ?? nil
        accountNumber = (try? c.decodeIfPresent(String.self, forKey: .accountNumber)) ?? nil
    }
}

/// Device class and platform sent in every auth body below.
///
/// Audit 2026-09-29, D-18: this one target builds both the iOS app and the Mac
/// App Store app, and every Mac registered itself as an iPhone ("MOBILE" /
/// "IOS"), so the device list, support and any per-platform view of devices
/// were wrong for macOS. The backend already accepts DESKTOP and MACOS in the
/// same enums (auth.controller.ts DeviceInfoSchema; Prisma DeviceType /
/// Platform) and uses them for display only. The User-Agent and
/// `X-Desktop-Client` header followed on 2026-10-01 (owner item 96) — see
/// `ClientIdentity`.
#if os(macOS)
private let kDeviceTypeWire = "DESKTOP"
private let kPlatformWire = "MACOS"
#else
private let kDeviceTypeWire = "MOBILE"
private let kPlatformWire = "IOS"
#endif

struct LoginBody: Encodable {
    let email: String
    let password: String
    /// Device attribution — also lets a trusted device skip the 2FA challenge.
    let deviceId: String
    let deviceName: String
    let deviceType = kDeviceTypeWire
    let platform = kPlatformWire
    let platformVersion: String
    let appVersion: String
}

/// Body for `POST /auth/login/anonymous` — the 24-digit account ID plus
/// optional password. A nil password is OMITTED from the JSON (synthesized
/// Encodable uses encodeIfPresent): the backend deliberately answers 401
/// "Invalid credentials" when a password is supplied to a password-less
/// account, so an empty string must never be sent.
struct AnonymousLoginBody: Encodable {
    let anonymousId: String
    let password: String?
    let deviceId: String
    let deviceName: String
    let deviceType = kDeviceTypeWire
    let platform = kPlatformWire
    let platformVersion: String
    let appVersion: String
}

/// Body for `POST /auth/register/anonymous` — device context only; the server
/// mints the 24-digit anonymous ID.
struct DeviceInfoBody: Encodable {
    let deviceId: String
    let deviceName: String
    let deviceType = kDeviceTypeWire
    let platform = kPlatformWire
    let platformVersion: String
    let appVersion: String
}

struct TwoFactorBody: Encodable {
    let challengeToken: String
    let token: String
}

/// Body for `POST /auth/native/exchange`. Wire casing is EXACT:
/// `code_verifier` is snake_case (RFC 7636 vocabulary), device fields camelCase.
/// Body for `POST /auth/apple/native`. Mirrors `SsoExchangeBody`'s device
/// fields so both native sign-in paths register a session identically.
struct AppleNativeBody: Encodable {
    let identityToken: String
    let nonce: String
    let deviceId: String
    let deviceName: String
    let deviceType = kDeviceTypeWire
    let platform = kPlatformWire
    let platformVersion: String
    let appVersion: String

    private enum CodingKeys: String, CodingKey {
        case identityToken, nonce, deviceId, deviceName, deviceType, platform
        case platformVersion, appVersion
    }
}

struct SsoExchangeBody: Encodable {
    let code: String
    let codeVerifier: String
    let deviceId: String
    let deviceName: String
    let deviceType = kDeviceTypeWire
    let platform = kPlatformWire
    let platformVersion: String
    let appVersion: String

    private enum CodingKeys: String, CodingKey {
        case code
        case codeVerifier = "code_verifier"
        case deviceId, deviceName, deviceType, platform, platformVersion, appVersion
    }
}

struct RefreshBody: Encodable {
    // swiftlint:disable:next identifier_name — snake_case is the wire contract.
    let refresh_token: String
}

/// Token pair as the backend emits it (`AuthService.login` → snake_case).
struct TokenPairDTO: Decodable {
    let accessToken: String
    let refreshToken: String

    private enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

/// Shared response shape of `/auth/login/desktop`, `/auth/login/anonymous`,
/// `/auth/register/anonymous`, `/auth/native/exchange` and `/auth/2fa/verify`:
/// tokens on success, or a 2FA challenge.
struct AuthTokensResponse: Decodable {
    let ok: Bool?
    let tokens: TokenPairDTO?
    let requiresTwoFactor: Bool?
    let challengeToken: String?
    /// Returned by `/auth/register/anonymous` (minted) and echoed by
    /// `/auth/login/anonymous`. For a fresh registration it is surfaced only
    /// once and is the sole credential for the account — `/auth/me` never
    /// replays it directly (only via the synthetic email) — so dropping it
    /// would make the account unrecoverable after a logout or reinstall.
    let anonymousId: String?
}

/// `/auth/refresh` returns the rotated pair at the top level, not nested.
struct RefreshTokensResponse: Decodable {
    let accessToken: String
    let refreshToken: String?

    private enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
    }
}

// MARK: - Apple in-app-purchase wire models

/// `POST /payments/store/apple/purchase-token` response.
struct ApplePurchaseTokenResponse: Decodable {
    let appAccountToken: String
}

/// `POST /payments/store/apple/link` request.
///
/// One field, on purpose. The server derives the plan, the price and the
/// expiry from Apple's own signature — anything the client asserted about what
/// it bought would be an input the buyer controls.
struct AppleLinkBody: Encodable {
    let signedTransaction: String
}
