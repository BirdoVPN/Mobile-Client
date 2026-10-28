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
}

/// A connect the server refused inside its 200 (`success: false`), that is
/// not a refused rebuild (`RebuildRefusedError`). Keyed on `refusalReason`,
/// never on the server's wording: the banner's text, and whether Home offers
/// the plans, come from the reason. `LocalizedError`, so the connect paths'
/// `error.localizedDescription` shows `message` as for any refusal.
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

    /// Today's copy for a refusal that explains nothing.
    static let genericMessage = "Could not connect. Please try again."

    /// The refusal for an envelope's fields.
    ///
    /// - `device_limit`: `deviceLimitMessage`, whatever the wording.
    /// - Another reason: the server's own sentence; when it sent none, the
    ///   reason's own copy (`fallback(for:)`), never the generic one.
    /// - No reason, or one newer than this build: exactly as before MR-139,
    ///   the server's sentence verbatim, or `genericMessage` when it sent none
    ///   (older backends send no reason).
    static func classify(message: String?, refusalReason: String?, quotaExceeded: Bool) -> ConnectRefusedError {
        let reason = ConnectRefusalReason(wire: refusalReason)
        let text: String
        switch reason {
        case .deviceLimit?:
            text = deviceLimitMessage
        case let typed?:
            let trimmed = message?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            text = trimmed.isEmpty ? fallback(for: typed) : trimmed
        case nil:
            text = message ?? genericMessage
        }
        return ConnectRefusedError(reason: reason, quotaExceeded: quotaExceeded, message: text)
    }

    /// The copy for a typed refusal whose server sentence is missing or blank.
    static func fallback(for reason: ConnectRefusalReason) -> String {
        switch reason {
        case .deviceLimit:
            return deviceLimitMessage
        case .plan:
            return "This server is not included in your plan. Choose another server, or view the plans."
        case .quotaExceeded:
            return "Your free data for this period is used up. View the plans to keep connecting."
        case .serverCapacity:
            return "This server is full right now. Choose another server."
        case .serverUnavailable:
            return "This server is not available right now. Choose another server or try again later."
        }
    }
}
