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

    /// The reason `wire` names; nil for none and for one newer than this build.
    init?(wire: String?) {
        guard let wire, let known = ConnectRefusalReason(rawValue: wire) else { return nil }
        self = known
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

    private enum CodingKeys: String, CodingKey {
        case success, message, rebuildRefused, refusalReason, quotaExceeded
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
    static func classify(message: String?, refusalReason: String?, quotaExceeded: Bool) -> ConnectRefusedError {
        let reason = ConnectRefusalReason(wire: refusalReason)
        let text: String
        if reason == .deviceLimit {
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
    static func isPresentable(_ text: String) -> Bool {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if t.isEmpty || t.utf16.count > 200 { return false }
        if t.hasPrefix("{") || t.hasPrefix("[") || t.contains("<") { return false }
        if t.contains("Exception") || t.contains("\tat ") || t.contains("stackTrace") { return false }
        var phrase = t.lowercased()
        while phrase.hasSuffix(".") { phrase.removeLast() }
        return !reasonPhrases.contains(phrase)
    }
}
