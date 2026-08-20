import Foundation

/// P1-ios-redial-loop-blackhole — the circuit breaker that bounds automatic
/// re-dials of a tunnel that cannot carry traffic.
///
/// ## The loop this exists to break
///
/// `armKillSwitchAfterHandshake()` arms `NEOnDemandRuleConnect` only after real
/// inbound bytes prove a handshake, which correctly stops a *never-established*
/// tunnel from ever arming. But arming is not the only way in. Once a session is
/// legitimately armed, the tunnel can still die later — the server-side peer is
/// revoked/evicted, or the node goes away — and the teardown then comes from the
/// EXTENSION (`checkDataPlaneLiveness()` and the `{valid:false}` heartbeat both
/// call `cancelTunnelWithError`). The extension cannot touch the on-demand rule:
/// `NETunnelProviderManager` preferences are host-app API. So the rule is still
/// armed when the tunnel drops, iOS re-dials the same dead peer immediately, it
/// comes up (WireGuardKit brings the interface up with or without a peer that
/// answers), the liveness check kills it again, forever. With
/// `includeAllNetworks` set, every packet is blackholed throughout — and the
/// host app is suspended, so nothing in-app is counting.
///
/// ## What this type is
///
/// Pure decision logic: given the failure history for a node, how many more
/// automatic re-dials are allowed, and has the budget run out. It imports
/// nothing beyond Foundation so the `BirdoVPNTests` bundle compiles it directly
/// (un-hosted — no app host, no KMP framework, no WireGuardKit), exactly like
/// `MultiHopRoute.swift`. Persistence lives in `TunnelBreakerStore`; the
/// extension- and host-side wiring lives in `PacketTunnelProvider` and
/// `VPNManager`/`VpnViewModel`.
///
/// ## Fail OPEN, never closed
///
/// A tripped breaker is a *bounded, self-clearing, user-overridable* state:
///   * `tripCooldown` (15 min) lapses it on its own, so even an app that is
///     never opened returns to normal behaviour.
///   * `TunnelBreakerStore.clear()` is called on every USER-INITIATED connect,
///     so a manual tap always wins — a bug in here can delay an automatic
///     re-dial, never prevent the user connecting.
///   * A decode failure or a missing record reads as "not tripped".
/// Nothing here disables the VPN permanently and nothing here blocks traffic.
/// The trip *action* on the host side is to stop blocking (disarm on-demand and
/// `includeAllNetworks`, then stop the tunnel) — see `VPNManager.failOpenAndStop()`.

// MARK: - Failure classification

/// WHY the tunnel stopped carrying traffic. The three cases need different
/// recoveries, which is why the breaker distinguishes them rather than counting
/// undifferentiated "failures".
enum TunnelFailureKind: String, Codable, Equatable, Sendable {
    /// The tunnel came up and NEVER completed a handshake (wg reports
    /// `last_handshake_time_sec=0` past the liveness grace window).
    ///
    /// Recovery: a re-dial to the same endpoint is nearly worthless — the cause
    /// is a blocked/filtered path, a wrong endpoint or a peer that was never
    /// installed, and none of those change between dials. The user needs a
    /// DIFFERENT server (or a different transport). Small budget.
    case neverEstablished

    /// A handshake DID complete, and later went stale past
    /// `PacketTunnelProvider.maxHandshakeAge`.
    ///
    /// Recovery: a re-dial is genuinely likely to work — this is what a NAT
    /// rebind, a node restart or a roaming path change looks like, and the peer
    /// still exists server-side. Largest budget.
    case diedAfterHandshake

    /// The backend answered the liveness heartbeat with `{valid:false}`: the
    /// peer has been revoked or evicted server-side.
    ///
    /// Recovery: a raw re-dial can NEVER work — there is nothing to hand shake
    /// with, and every retry re-dials a peer the server has already deleted.
    /// Only a fresh `/vpn/connect` (which mints a new peer) fixes it, and that
    /// is host-app work. Budget of 1: trip on the first occurrence.
    case revoked
}

// MARK: - Persistent record

/// The breaker's state for ONE node, as persisted in the shared keychain.
///
/// Keyed by node (the endpoint host we dial), so failures against Frankfurt
/// never spend the budget for Amsterdam and switching servers starts clean.
struct TunnelBreakerRecord: Codable, Equatable, Sendable {
    /// Endpoint host of the node these failures belong to (`serverAddress`).
    /// For a Multi-Hop session this is the ENTRY node — the only peer this
    /// device actually handshakes with.
    let nodeId: String
    /// The most recent failure kind. The budget in force is this kind's.
    let kind: TunnelFailureKind
    /// Consecutive failures for `nodeId` inside the rolling `failureWindow`.
    let consecutiveFailures: Int
    /// When this streak started (unix seconds).
    let firstFailureAt: TimeInterval
    /// When the most recent failure landed (unix seconds).
    let lastFailureAt: TimeInterval
    /// When the breaker tripped (unix seconds), or nil while still under
    /// budget. Drives the cooldown.
    let trippedAt: TimeInterval?

    /// EXPLICIT init. The memberwise one is only as visible as the least
    /// visible stored property, and this type crosses two targets — spell it
    /// out rather than depend on synthesis.
    init(
        nodeId: String,
        kind: TunnelFailureKind,
        consecutiveFailures: Int,
        firstFailureAt: TimeInterval,
        lastFailureAt: TimeInterval,
        trippedAt: TimeInterval?
    ) {
        self.nodeId = nodeId
        self.kind = kind
        self.consecutiveFailures = consecutiveFailures
        self.firstFailureAt = firstFailureAt
        self.lastFailureAt = lastFailureAt
        self.trippedAt = trippedAt
    }
}

// MARK: - Decision logic

enum TunnelCircuitBreaker {

    /// Failures older than this do not count toward a streak. A node that dies
    /// once a week must never accumulate its way to a trip: without a window,
    /// `consecutiveFailures` is a lifetime counter and the breaker eventually
    /// trips on a perfectly healthy server.
    static let failureWindow: TimeInterval = 10 * 60

    /// How long a trip holds before it lapses on its own.
    ///
    /// This is the LAST-RESORT reset: it is what guarantees an unattended
    /// device returns to normal behaviour without anyone opening the app, so a
    /// mistake in the trip logic costs a 15-minute delay rather than a VPN that
    /// is off forever. Every other reset (a successful handshake, a manual
    /// connect, a different node, sign-out) fires sooner.
    static let tripCooldown: TimeInterval = 15 * 60

    /// How many automatic re-dials are allowed for a given failure kind before
    /// the breaker trips. See `TunnelFailureKind` for why they differ.
    static func redialBudget(for kind: TunnelFailureKind) -> Int {
        switch kind {
        case .revoked:           return 1
        case .neverEstablished:  return 2
        case .diedAfterHandshake: return 4
        }
    }

    /// Fold one failure into the record, returning the new record to persist.
    ///
    /// Streak rules:
    ///   * a failure for a DIFFERENT node starts a fresh streak at 1 (switching
    ///     servers resets the breaker);
    ///   * a failure more than `failureWindow` after the previous one starts a
    ///     fresh streak at 1;
    ///   * otherwise the streak increments and the record adopts the NEW kind,
    ///     so a mixed sequence is judged by the budget of the most recent
    ///     failure. A run that degrades into `.revoked` therefore trips at once
    ///     instead of riding the larger `.diedAfterHandshake` budget.
    ///
    /// `trippedAt` is stamped the moment the streak reaches the budget and is
    /// then carried forward unchanged, so the cooldown measures from the FIRST
    /// trip rather than sliding forward on every later failure.
    static func recordFailure(
        _ kind: TunnelFailureKind,
        nodeId: String,
        now: Date,
        into previous: TunnelBreakerRecord?
    ) -> TunnelBreakerRecord {
        let nowSeconds = now.timeIntervalSince1970
        let continues: Bool = {
            guard let previous else { return false }
            guard previous.nodeId == nodeId else { return false }
            return nowSeconds - previous.lastFailureAt <= failureWindow
        }()

        let streak = continues ? (previous!.consecutiveFailures + 1) : 1
        let firstAt = continues ? previous!.firstFailureAt : nowSeconds
        let carriedTrip = continues ? previous!.trippedAt : nil
        let trippedAt: TimeInterval? = carriedTrip
            ?? (streak >= redialBudget(for: kind) ? nowSeconds : nil)

        return TunnelBreakerRecord(
            nodeId: nodeId,
            kind: kind,
            consecutiveFailures: streak,
            firstFailureAt: firstAt,
            lastFailureAt: nowSeconds,
            trippedAt: trippedAt
        )
    }

    /// Is the breaker currently holding?
    ///
    /// FAIL OPEN: `nil` (no record, or a record that failed to decode) is NOT
    /// tripped, and a trip older than `tripCooldown` is NOT tripped. Both
    /// answers are "carry on as normal", which is the only safe default for a
    /// component whose job is to stop something.
    static func isTripped(_ record: TunnelBreakerRecord?, now: Date) -> Bool {
        guard let record, let trippedAt = record.trippedAt else { return false }
        return now.timeIntervalSince1970 - trippedAt < tripCooldown
    }

    /// Automatic re-dials still allowed before the breaker trips. Never
    /// negative. Purely informational for logging — `isTripped` is the gate.
    static func remainingRedials(_ record: TunnelBreakerRecord?, now: Date) -> Int {
        guard let record else { return redialBudget(for: .diedAfterHandshake) }
        if isTripped(record, now: now) { return 0 }
        // A lapsed trip means the streak is spent history; the next failure
        // starts fresh, so report the full budget for that kind.
        if record.trippedAt != nil { return redialBudget(for: record.kind) }
        return max(0, redialBudget(for: record.kind) - record.consecutiveFailures)
    }

    /// User-visible explanation for a tripped breaker.
    ///
    /// Deliberately says what STOPPED and what is no longer happening ("traffic
    /// is no longer being blocked"), because the symptom the user just lived
    /// through was a dead network under a "Protected" UI. Ends with the action
    /// that actually helps for that failure kind.
    static func userMessage(for record: TunnelBreakerRecord) -> String {
        let attempts = record.consecutiveFailures
        let plural = attempts == 1 ? "attempt" : "attempts"
        switch record.kind {
        case .revoked:
            return "Birdo stopped reconnecting: the server ended this connection "
                + "(it may have been revoked, or claimed by another device). "
                + "Traffic is no longer being blocked. Tap Connect to start a new session."
        case .neverEstablished:
            return "Birdo stopped reconnecting after \(attempts) \(plural): the tunnel "
                + "came up but never reached this server, so no traffic could pass. "
                + "Traffic is no longer being blocked. Try a different location, or a "
                + "different network."
        case .diedAfterHandshake:
            return "Birdo stopped reconnecting after \(attempts) \(plural): the connection "
                + "to this server keeps dropping. Traffic is no longer being blocked. "
                + "Tap Connect to retry, or pick another location."
        }
    }
}
