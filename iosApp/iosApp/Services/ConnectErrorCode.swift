import Foundation

// MR-1948. Foundation-only BY DESIGN, and in its own file for the same reason
// ClientUpdateAdvisory.swift and StoredPQKeyTriage.swift are: the decoding and
// the clamp below are the whole risk surface (a refusal that loses its code,
// a wait that hammers the API or parks the user for an hour, a junk body that
// crashes), and they can only be pinned by a test if they compile into the
// test bundle without APIClient.swift's networking stack. See
// ConnectErrorCodeTests, which also checks this enum against the vendored
// contract.

/// The machine-readable `error` codes the connect routes emit.
///
/// Mirrors `$defs.ErrorCode` in contract/vpn-protocol.schema.json (generated
/// by birdo-web from `CONNECT_ERROR_CODES` in
/// backend/src/vpn/contract/protocol-schema.ts and vendored byte for byte).
/// `ConnectErrorCodeTests` fails if the two lists drift, so a code added by a
/// re-vendor has to be mapped here on purpose.
///
/// Read from the error envelope's `error` field as a plain string, never as a
/// decoded enum, so a code this build does not know cannot fail a decode: it
/// is `.unknown`, and the failure is classified by `APIClient` exactly as it
/// was before the code existed. The same field carries Nest's reason phrase
/// ("Service Unavailable", "Bad Request") on every error that names no code;
/// that is `.unknown` too. Android twin: `ConnectErrorCode.kt` (MR-1639).
enum ConnectErrorCode: CaseIterable, Equatable, Sendable {
    /// 426: below the support floor, or a quantumProtection request without a
    /// usable PQ key. iOS already handles this by its STATUS (APIClient's 426
    /// branch); listed so the contract check is complete.
    case updateRequired
    /// 403: the attestation policy refused this build.
    case clientAttestationRequired
    /// 503: a FREE account's data allowance could not be read, so the cap
    /// failed closed. Paid plans never receive it.
    case quotaCheckUnavailable
    /// 503: the backend could not hand this connection's pre-shared key to the
    /// relay safely. Its one throw site (birdo-web wireguard.service.ts, the
    /// peer add) is reached only when `getAgent()` is null, i.e.
    /// `BIRDO_USE_NODE_AGENT !== 'true'`, which is a HUB-WIDE setting: every
    /// location answers the same until it clears, so the remedy is to wait,
    /// never to switch. The server rolled the new peer back rather than install
    /// it without its PSK, so nothing was downgraded server-side, and nothing
    /// may be here: a re-dial asks for exactly what this one did, because
    /// `APIClient.getConnectConfig` / `getMultiHopConfig` read Quantum
    /// Protection afresh on every dial and no failure path writes it.
    case pskDeliveryUnavailable
    /// No code, a reason phrase, a non-string, or a code newer than this build.
    case unknown

    /// The wire spelling. Exact match only: the server's codes are lower snake case.
    var wire: String? {
        switch self {
        case .updateRequired: return "update_required"
        case .clientAttestationRequired: return "client_attestation_required"
        case .quotaCheckUnavailable: return "quota_check_unavailable"
        case .pskDeliveryUnavailable: return "psk_delivery_unavailable"
        case .unknown: return nil
        }
    }

    /// The contract's word, not a guess from the status: the server refused a
    /// connect that nothing about the account or the request will keep
    /// refusing, and said when to come back (`details.retryAfterSeconds`,
    /// REQUIRED on exactly these two codes). Asking again later is the remedy.
    var isRetryable: Bool {
        switch self {
        case .quotaCheckUnavailable, .pskDeliveryUnavailable:
            return true
        case .updateRequired, .clientAttestationRequired, .unknown:
            return false
        }
    }

    init(wire: String?) {
        guard let wire else {
            self = .unknown
            return
        }
        self = Self.allCases.first(where: { $0.wire == wire }) ?? .unknown
    }
}

/// How long to wait after a retryable refusal. Android twin: `RetryAfter.kt`.
///
/// The server sends `details.retryAfterSeconds: 30` on both retryable codes
/// (`PskOverSshRefusedError.RETRY_AFTER_SECONDS`,
/// `QuotaCheckUnavailableError.RETRY_AFTER_SECONDS`) and NO `Retry-After`
/// header (GlobalExceptionFilter sets none), so the body is the only source.
enum ConnectRetryAfter {
    /// Floor: a wrong value must not let a client hammer the API.
    static let minimum: TimeInterval = 1
    /// Ceiling: a wrong value must not park a user for an hour.
    static let maximum: TimeInterval = 10 * 60
    /// The wait when a retryable refusal named none this client can use. The
    /// contract REQUIRES `retryAfterSeconds` on both codes, so this only covers
    /// a body that lost it on the way; it matches what the server sends.
    static let retryableDefault: TimeInterval = 30

    /// A server-named wait, clamped to [`minimum`, `maximum`]. Nil when it is
    /// not a usable wait: absent, not a number, zero, negative or not finite.
    /// Zero and negative are treated as absent (they become the default), as
    /// on Android: a retryable refusal exists to say "not yet", and reading a
    /// malformed 0 as "1 s" would turn it into a hammer.
    static func clamped(seconds: Double?) -> TimeInterval? {
        guard let seconds, seconds.isFinite, seconds > 0 else { return nil }
        return min(max(seconds, minimum), maximum)
    }

    /// The wait to honour after a retryable refusal: the server's, clamped,
    /// else `retryableDefault`.
    static func forRetryableRefusal(serverSeconds: Double?) -> TimeInterval {
        clamped(seconds: serverSeconds) ?? retryableDefault
    }
}

/// The parts of GlobalExceptionFilter's envelope a connect refusal needs:
/// `{statusCode, message, error, timestamp, path, requestId?, correlationId?,
/// details?}`. Every field is decoded on its own and optionally, so a field of
/// the wrong type loses that field only and never the rest; a body that is not
/// a JSON object at all (HTML from a proxy, empty, `null`, an array) is nil.
struct ConnectErrorEnvelope: Decodable, Equatable {
    /// The machine-readable code, when the server named one.
    let error: String?
    /// The server's own sentence (Nest sends a string, or an array of
    /// validation strings).
    let message: String?
    /// `details.retryAfterSeconds`, as any JSON number.
    let retryAfterSeconds: Double?

    private enum CodingKeys: String, CodingKey {
        case error, message, details
    }

    private enum DetailsKeys: String, CodingKey {
        case retryAfterSeconds
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        error = try? c.decode(String.self, forKey: .error)
        if let single = try? c.decode(String.self, forKey: .message) {
            message = single
        } else if let many = try? c.decode([String].self, forKey: .message) {
            message = many.joined(separator: " ")
        } else {
            message = nil
        }
        let details = try? c.nestedContainer(keyedBy: DetailsKeys.self, forKey: .details)
        retryAfterSeconds = try? details?.decode(Double.self, forKey: .retryAfterSeconds)
    }

    /// Never throws: anything that is not a JSON object is nil.
    static func decode(_ body: Data) -> ConnectErrorEnvelope? {
        try? JSONDecoder().decode(ConnectErrorEnvelope.self, from: body)
    }
}

/// A connect refused with a RETRYABLE code (`ConnectErrorCode.isRetryable`):
/// HTTP 503 `psk_delivery_unavailable` or `quota_check_unavailable`.
///
/// Its own type rather than an `APIError` case, for the reason
/// `RebuildRefusedError` is: it can only come from the two connect routes, and
/// every exhaustive `APIError` switch in AuthViewModel would otherwise have to
/// name a case that can never reach it. `APIClient` throws it through
/// `performRequest`'s `refusal:` hook, which sees the raw body BEFORE the
/// generic mapping turns the 503 into `.serverMessage` / `.httpError(503)` and
/// throws the code and the wait away. `LocalizedError`, so the callers that
/// render `localizedDescription` (VpnViewModel's connect paths) show the copy
/// below with no change.
///
/// It carries no config: nothing was minted, so there is nothing to bring up,
/// release or downgrade.
struct RetryableConnectRefusal: Error, LocalizedError, Equatable {
    let code: ConnectErrorCode
    /// What the server asked for, clamped (`ConnectRetryAfter`).
    let retryAfter: TimeInterval
    /// The HTTP status it arrived on (503 today).
    let status: Int
    /// The server's own sentence, when it sent one.
    let serverMessage: String?

    /// psk_delivery_unavailable. Mirrors Android #481's
    /// `error_secure_setup_unavailable` and the server's own sentence. It says
    /// to wait and deliberately does NOT suggest another location: the
    /// condition is hub-wide, so every location fails the same way until it
    /// clears. Nor does it suggest turning Quantum Protection off: the refusal
    /// is the server declining to connect with less protection, and the right
    /// answer is the same request again shortly.
    static let secureSetupUnavailableCopy =
        "Couldn't set up a secure connection to this server just now. Please try again in a moment."

    /// quota_check_unavailable with no usable server sentence. Mirrors
    /// Android's `error_server_unavailable`.
    static let temporarilyUnavailableCopy =
        "BirdoVPN is temporarily unavailable. Please try again in a moment."

    var errorDescription: String? {
        switch code {
        case .pskDeliveryUnavailable:
            return Self.secureSetupUnavailableCopy
        case .quotaCheckUnavailable, .updateRequired, .clientAttestationRequired, .unknown:
            // quota_check_unavailable keeps the copy it had before this type
            // existed: the server's own sentence, which was rendered verbatim
            // via `.serverMessage`.
            if let serverMessage, !serverMessage.isEmpty { return serverMessage }
            return Self.temporarilyUnavailableCopy
        }
    }

    /// The refusal this response is, or nil to let `APIClient` map it as
    /// before. Only a code the contract marks retryable is claimed, on any
    /// status (as on Android: a retryable code means "come back shortly"
    /// whatever status it rode in on). A non-retryable code, an unknown one,
    /// no code and a body that is not JSON all return nil.
    static func classify(status: Int, body: Data) -> RetryableConnectRefusal? {
        let envelope = ConnectErrorEnvelope.decode(body)
        let code = ConnectErrorCode(wire: envelope?.error)
        guard code.isRetryable else { return nil }
        return RetryableConnectRefusal(
            code: code,
            retryAfter: ConnectRetryAfter.forRetryableRefusal(serverSeconds: envelope?.retryAfterSeconds),
            status: status,
            serverMessage: envelope?.message
        )
    }
}

/// When an AUTOMATIC dial may next ask `/connect`, after a retryable refusal.
///
/// iOS makes exactly one connect-route dial without a tap: the launch
/// auto-connect (`VpnViewModel.autoConnectIfEnabled`). On-demand never calls
/// the API at all (iOS restarts the extension from the saved profile, and the
/// extension only ever calls the heartbeat), and every other dial is a user
/// action, which is never held: the same rule as the circuit breaker, a manual
/// tap always dials. So this is the whole of "the automatic re-dial waits at
/// least the server's wait" on iOS.
enum AutoDialHold {
    /// The instant the hold set by `refusal` lapses.
    static func notBefore(after refusal: RetryableConnectRefusal, now: Date) -> Date {
        now.addingTimeInterval(refusal.retryAfter)
    }

    /// Whether an automatic dial must still wait at `now`.
    static func isHeld(now: Date, notBefore: Date?) -> Bool {
        guard let notBefore else { return false }
        return now < notBefore
    }

    /// How long an automatic dial scheduled at `now` sleeps before it dials:
    /// its own `floor` (the launch auto-connect's 1.5 s settle) or the rest of
    /// the hold, whichever is longer. The rest of the hold is capped at
    /// `ConnectRetryAfter.maximum`: `notBefore` is a wall-clock instant, and a
    /// clock set back after it was recorded would otherwise stretch the wait
    /// past the 10 minutes the server's value was clamped to.
    static func delay(now: Date, notBefore: Date?, floor: TimeInterval) -> TimeInterval {
        guard let notBefore else { return floor }
        return max(floor, min(notBefore.timeIntervalSince(now), ConnectRetryAfter.maximum))
    }
}
