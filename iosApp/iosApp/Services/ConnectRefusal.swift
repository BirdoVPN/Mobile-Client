import Foundation

/// MR-139: why the server refused a connect inside its 200 — `refusalReason`
/// beside `success: false` on both connect routes. birdo-web's
/// CONNECT_REFUSALS (vpn.service.ts), vendored as `$defs.ConnectRefusal` in
/// contract/vpn-protocol.schema.json and pinned to it by ConnectRefusalTests.
///
/// The Android twin is `ConnectRefusalReason` (Mobile #511). Read off the wire
/// as a plain string, so a value this build does not know cannot fail the
/// decode: `init(wire:)` returns nil for it and for none, and the refusal is
/// handled as one without a reason, exactly as before (the contract: "Treat
/// an unknown value as absent").
enum ConnectRefusalReason: String, CaseIterable, Sendable {
    /// The plan's device cap is reached; asking again changes nothing.
    case deviceLimit = "device_limit"
    /// The chosen server, every server, or Multi-Hop is outside the plan.
    case plan
    /// The Free data allowance is used up (always with `quotaExceeded: true`).
    case quotaExceeded = "quota_exceeded"
    /// The chosen server (or a Multi-Hop entry or exit) is full.
    case serverCapacity = "server_capacity"
    /// The chosen server is missing or not ONLINE, or none can be used now.
    case serverUnavailable = "server_unavailable"
    /// MR-2221: new free-plan connections on the account are paused until
    /// `accountRestriction.until`. Final until then; the server's own long
    /// sentence is shown, with the end time and the appeal link.
    case accountRestricted = "account_restricted"

    /// The reason `wire` names; nil for none and for one newer than this build.
    init?(wire: String?) {
        guard let wire, let known = ConnectRefusalReason(rawValue: wire) else { return nil }
        self = known
    }
}

/// MR-2221: `$defs.ConnectResponse.accountRestriction`. Every member optional,
/// so a body missing a part still decodes; read leniently by the envelope.
struct AccountRestriction: Decodable, Equatable, Sendable {
    let until: String?
    let appealPath: String?

    private enum CodingKeys: String, CodingKey { case until, appealPath }

    init(until: String?, appealPath: String?) {
        self.until = until
        self.appealPath = appealPath
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        until = (try? c.decodeIfPresent(String.self, forKey: .until)) ?? nil
        appealPath = (try? c.decodeIfPresent(String.self, forKey: .appealPath)) ?? nil
    }

    /// When the restriction ends; nil when `until` is missing or not ISO-8601
    /// (then nothing is held back and the server decides, as before).
    var endDate: Date? {
        guard let until else { return nil }
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let plain = ISO8601DateFormatter()
        return fractional.date(from: until) ?? plain.date(from: until)
    }

    /// The appeal page on the site, or nil unless `appealPath` is a plain path.
    var appealURL: URL? { SitePath.url(appealPath) }
}

/// MR-2220: `$defs.ConnectResponse.deviceNotice`, on a SUCCESSFUL connect.
struct DeviceNotice: Decodable, Equatable, Sendable {
    let kind: String?
    let message: String?
    let managePath: String?

    private enum CodingKeys: String, CodingKey { case kind = "code", message, managePath }

    init(kind: String?, message: String?, managePath: String?) {
        self.kind = kind
        self.message = message
        self.managePath = managePath
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = (try? c.decodeIfPresent(String.self, forKey: .kind)) ?? nil
        message = (try? c.decodeIfPresent(String.self, forKey: .message)) ?? nil
        managePath = (try? c.decodeIfPresent(String.self, forKey: .managePath)) ?? nil
    }

    /// The sentence when it can be shown as the server wrote it, else nil.
    var text: String? {
        guard let message, ConnectRefusedError.isPresentable(message, maxLength: 1000) else { return nil }
        return message.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    var manageURL: URL? { SitePath.url(managePath) }
}

/// A link the app may open: a plain `/path` of birdo.app, nothing else.
enum SitePath {
    static func url(_ path: String?) -> URL? {
        guard let p = path?.trimmingCharacters(in: .whitespacesAndNewlines),
              p.hasPrefix("/"), !p.hasPrefix("//"), p.count <= 200,
              // Printable ASCII only, no space and no control character (Desktop's rule).
              p.unicodeScalars.allSatisfy({ $0.value >= 0x21 && $0.value <= 0x7E }),
              !p.contains("\\"), !p.contains(" ") else { return nil }
        return URL(string: "https://birdo.app" + p)
    }
}

/// MR-2220: a sign-up refused because new accounts are not accepted from the
/// caller's country: HTTP 403 `{ statusCode, message, details: { code:
/// "registration_country_not_allowed" } }` (birdo-web `refusal()` in
/// registration-gate.service.ts). Told by `details.code` on a 403, never by
/// wording, and shown as the server wrote it. No other 403 is classified here.
/// Thrown from the `refusal:` hook of the account-creating requests, so the
/// generic mapping (which filters and re-words server text) never sees it.
struct RegistrationRefusedError: Error, LocalizedError, Equatable, Sendable {
    static let code = "registration_country_not_allowed"
    static let maxLength = 500

    let message: String

    var errorDescription: String? { message }

    /// The refusal for a response, or nil when it is anything else.
    static func classify(status: Int, body: Data) -> RegistrationRefusedError? {
        guard status == 403,
              let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let details = root["details"] as? [String: Any],
              (details["code"] as? String) == code,
              let message = root["message"] as? String,
              ConnectRefusedError.isPresentable(message, maxLength: maxLength) else { return nil }
        return RegistrationRefusedError(message: message.trimmingCharacters(in: .whitespacesAndNewlines))
    }
}

/// MR-2210: a sign-in refused because the e-mail address was never verified:
/// HTTP 403 `{ statusCode, message, details: { code: "email_not_verified" } }`.
/// Told by `details.code` on a 403, never by wording. The server's sentence is
/// shown when it is presentable; otherwise the app's own. No other 403 is
/// classified here (a bare 403 stays whatever it was).
struct EmailNotVerifiedError: Error, LocalizedError, Equatable, Sendable {
    static let code = "email_not_verified"
    static let fallbackMessage = "Please verify your email address first. Open birdo.app/verify-email to get a new link."

    let message: String

    var errorDescription: String? { message }

    static func classify(status: Int, body: Data) -> EmailNotVerifiedError? {
        guard status == 403,
              let root = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any],
              let details = root["details"] as? [String: Any],
              (details["code"] as? String) == code else { return nil }
        if let m = root["message"] as? String, ConnectRefusedError.isPresentable(m, maxLength: 200) {
            return EmailNotVerifiedError(message: m.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return EmailNotVerifiedError(message: fallbackMessage)
    }
}

/// The `{ success, message }` envelope both connect routes use for
/// user-actionable refusals returned with a 2xx status, plus, for a refused
/// `rebuild: true` connect, the reason (`LiveRebuildWire.rebuildRefusedField`),
/// and since MR-139 the typed `refusalReason` and `quotaExceeded`. Moved out
/// of APIClient.swift so ConnectRefusalTests decode the REAL struct.
struct ConnectEnvelope: Decodable, Sendable {
    let success: Bool?
    let message: String?
    /// One of birdo-web's `RebuildRefusal` codes, or absent. Kept as the raw
    /// wire string so a code this build cannot read still reaches the policy.
    let rebuildRefused: String?
    /// `$defs.ConnectRefusal`, or absent (an older backend, a success, a
    /// rebuild refusal). Raw, for the same reason as `rebuildRefused`.
    let refusalReason: String?
    let quotaExceeded: Bool?
    /// MR-2221: with `account_restricted` only; a malformed one reads as absent.
    let accountRestriction: AccountRestriction?

    private enum CodingKeys: String, CodingKey {
        case success, message, rebuildRefused, refusalReason, quotaExceeded, accountRestriction
    }

    /// The MR-139 fields are read leniently: a wrong-typed `refusalReason` or
    /// `quotaExceeded` reads as absent. Decoded strictly, it failed the whole
    /// envelope, and the refusal lost the server's sentence with it.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = try c.decodeIfPresent(Bool.self, forKey: .success)
        message = try c.decodeIfPresent(String.self, forKey: .message)
        rebuildRefused = try c.decodeIfPresent(String.self, forKey: .rebuildRefused)
        refusalReason = (try? c.decodeIfPresent(String.self, forKey: .refusalReason)) ?? nil
        quotaExceeded = (try? c.decodeIfPresent(Bool.self, forKey: .quotaExceeded)) ?? nil
        accountRestriction = (try? c.decodeIfPresent(AccountRestriction.self, forKey: .accountRestriction)) ?? nil
    }
}

/// A connect the server refused inside its 200 (`success: false`), that is
/// not a refused rebuild (`RebuildRefusedError`). Keyed on `refusalReason`,
/// never on the server's wording: whether the banner shows the device cap's
/// own sentence, and whether Home offers the plans, come from the reason.
/// `LocalizedError`, so the connect paths' `error.localizedDescription` shows
/// `message` as for any refusal.
struct ConnectRefusedError: Error, LocalizedError, Equatable, Sendable {
    /// Nil for none and for a reason newer than this build.
    let reason: ConnectRefusalReason?
    let quotaExceeded: Bool
    /// What the connect paths render.
    let message: String
    /// MR-2221: when an `account_restricted` refusal ends, and where to appeal.
    /// Nil for every other refusal.
    let restrictedUntil: Date?
    let appealURL: URL?

    init(reason: ConnectRefusalReason?, quotaExceeded: Bool, message: String,
         restrictedUntil: Date? = nil, appealURL: URL? = nil) {
        self.reason = reason
        self.quotaExceeded = quotaExceeded
        self.message = message
        self.restrictedUntil = restrictedUntil
        self.appealURL = appealURL
    }

    /// The longest `account_restricted` sentence shown whole (the server's is about 454).
    static let restrictionMaxLength = 1000

    /// For a restricted account with no showable sentence.
    static let restrictedMessage =
        "New connections on this account are paused for now. You can ask for a review from the Devices page on birdo.app."

    var errorDescription: String? { message }

    /// The plan stands in the way, so Home offers the plans: a server outside
    /// the plan, or the Free allowance used up (Android: PLAN_REQUIRED and
    /// QUOTA_EXCEEDED). `quotaExceeded` counts whatever the reason says.
    var offersPlans: Bool {
        quotaExceeded || reason == .plan || reason == .quotaExceeded
    }

    /// The device cap's own sentence, whatever the server wrote (Android's
    /// `error_device_limit`, word for word): it says what to do about it.
    static let deviceLimitMessage =
        "This account is already connected on the maximum number of devices for its plan. "
        + "Disconnect another device or upgrade your plan."

    /// The copy for a refusal that explains nothing, or nothing showable
    /// (Android's `error_connect_failed`, word for word).
    static let genericMessage = "Couldn't connect to this server. Try again, or choose another location."

    /// The refusal for an envelope's fields: Android's
    /// `ApiErrorMapper.connectRefusal`, with one difference noted below.
    ///
    /// - `device_limit`: `deviceLimitMessage`, whatever the wording.
    /// - Any other reason, none, or one newer than this build: the server's
    ///   own sentence when `isPresentable`, otherwise `genericMessage`.
    ///
    /// Unlike Android, a refusal with NO reason is never matched on its
    /// wording ("Device limit reached …"): iOS never did, and every backend
    /// this build talks to sends the reason (birdo-web #739).
    static func classify(message: String?, refusalReason: String?, quotaExceeded: Bool,
                         accountRestriction: AccountRestriction? = nil) -> ConnectRefusedError {
        let reason = ConnectRefusalReason(wire: refusalReason)
        let text: String
        if reason == .accountRestricted {
            // MR-2221: keyed on the reason. The sentence is longer than the usual
            // limit, so only for this reason the ceiling is higher; every other
            // rule of `isPresentable` still applies. Then the end in local time.
            var shown = message.flatMap { isPresentable($0, maxLength: restrictionMaxLength) ? $0 : nil }?
                .trimmingCharacters(in: .whitespacesAndNewlines) ?? restrictedMessage
            let end = accountRestriction?.endDate
            if let end {
                let formatter = DateFormatter()
                formatter.dateStyle = .medium
                formatter.timeStyle = .short
                shown += "\n\nNew connections resume \(formatter.string(from: end))."
            }
            return ConnectRefusedError(reason: reason, quotaExceeded: quotaExceeded, message: shown,
                                       restrictedUntil: end, appealURL: accountRestriction?.appealURL)
        } else if reason == .deviceLimit {
            text = deviceLimitMessage
        } else if let message, isPresentable(message) {
            text = message.trimmingCharacters(in: .whitespacesAndNewlines)
        } else {
            text = genericMessage
        }
        return ConnectRefusedError(reason: reason, quotaExceeded: quotaExceeded, message: text)
    }

    /// Bare HTTP reason phrases, which say nothing a user can act on.
    private static let reasonPhrases: Set<String> = [
        "bad request", "unauthorized", "forbidden", "not found", "conflict", "gone",
        "unprocessable entity", "too many requests", "internal server error",
        "service unavailable", "bad gateway", "gateway timeout", "http exception",
        "validation failed",
    ]

    /// Whether a server sentence can be shown as-is: Android's
    /// `ApiErrorMapper.isPresentable`, rule for rule. Not blank, at most 200
    /// UTF-16 units (Kotlin's `length`), not JSON, no markup, no stack trace,
    /// and not a bare HTTP reason phrase.
    static func isPresentable(_ text: String, maxLength: Int = 200) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.utf16.count > maxLength { return false }
        if t.hasPrefix("{") || t.hasPrefix("[") || t.contains("<") { return false }
        if t.contains("Exception") || t.contains("\tat ") || t.contains("stackTrace") { return false }
        var phrase = t.lowercased()
        while phrase.hasSuffix(".") { phrase.removeLast() }
        return !reasonPhrases.contains(phrase)
    }
}
