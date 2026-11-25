import Foundation

// MR-2524. The tunnel extension's own heartbeat (PacketTunnelProvider) used to
// tear the tunnel down on any `valid: false`. While the app is suspended that
// beat is the only one running, and while the app is open it often hears first,
// so the host's decision (HeartbeatPolicy, MR-2343) was skipped whenever it did:
// a reaped key was never rebuilt, a drained server never left, and every ending
// read as the circuit breaker's generic sentence.
//
// The extension now decides with the HOST's code: HeartbeatWire.swift is
// compiled into the PacketTunnel target as it is (with the Foundation-only files
// it needs: RelayRetry, QuickSelect, ConnectionPhase, TunnelProof). This file
// says what the extension can DO with each outcome, which is less than the host
// can: it cannot mint a key, so a rebuild or a move is the host's to carry out.
// What the extension decided reaches the host through the shared keychain
// (`HeartbeatHandOff`, stored by `TunnelBreakerStore`).
//
// Foundation-only, and compiled into the app, the extension and BirdoVPNTests
// (project.yml), so TunnelHeartbeatPolicyTests runs the real decision.
// Android has one process, so its `VpnManager.onHeartbeatVerdict` is both
// halves at once: REAPED and a single-hop SERVER_GONE are rebuilt or moved
// behind the block, REVOKED / EVICTED / QUOTA_EXCEEDED end the session.

/// What the PacketTunnel extension does with one outcome of `HeartbeatPolicy`.
enum TunnelHeartbeatAction: Equatable, Sendable {
    /// The session stays as it is.
    case keep
    /// A rebuild or a move: a new key from `/vpn/connect`, which only the host
    /// can mint. The extension leaves the tunnel exactly as it is (nothing
    /// cancelled, nothing re-dialled, so with the kill switch on nothing leaves
    /// outside it) and hands the verdict to the host, which carries it out the
    /// next time it runs, as its own beat would have.
    case leaveToHost(HeartbeatHandOff.Verdict)
    /// The server ended the session: stop and never dial it again on our own,
    /// through the circuit breaker's `revoked` budget as before. The host is
    /// told which ending it was, so the user reads that ending's sentence.
    case stop(HeartbeatHandOff.Verdict)
}

enum TunnelHeartbeatPolicy {
    /// The profile key that says whether the session is a Multi-Hop pair ("1")
    /// or not ("0"). `VPNManager` writes it on every connect and swap, and the
    /// extension reads it at start and on a reconfigure. A profile an older
    /// build saved has none and reads as single-hop; the host judges a move of
    /// a pair again in its own context (`HeartbeatHandOff.Verdict.hostOutcome`).
    static let multiHopKey = "hb-multi-hop"

    /// The value of `multiHopKey` as the extension reads it: only "1" is a pair.
    static func multiHop(_ value: Any?) -> Bool {
        (value as? String) == "1"
    }

    /// The one mapping from the host's outcome to what the extension does.
    static func action(for outcome: HeartbeatOutcome) -> TunnelHeartbeatAction {
        switch outcome {
        case .keep:
            return .keep
        case .rebuild:
            return .leaveToHost(.rebuild)
        case .moveServer:
            return .leaveToHost(.moveServer)
        case .end(let ending):
            return .stop(HeartbeatHandOff.Verdict(ending: ending))
        }
    }
}

/// The extension heartbeat's memory, the twin of the host's
/// `lastHeartbeatOkAt` and `heartbeatRebuildBudget` (VpnViewModel), for the key
/// it beats for. The provider holds one under its heartbeat lock.
struct TunnelHeartbeatState: Sendable {
    /// The key the memory is about. A different key (a reconfigure onto a new
    /// peer) starts its own reap clock.
    private(set) var keyId: String?
    /// The last `valid: true` heard for `keyId`: the clock of the reap
    /// inference (`HeartbeatPolicy.reapWindow`).
    private(set) var lastOkAt: Date?
    /// The server has settled `keyId`: it ended the key, or released it for the
    /// host to rebuild or move. A key the server removed never comes back, so
    /// later replies about it are not judged again (one hand-off, one charge
    /// to the breaker, one rebuild from the budget).
    private(set) var settled = false
    /// One quiet rebuild per window, as on the host. Kept across keys, so a
    /// session the host rebuilt that is reaped again inside the window ends.
    private var budget = HeartbeatRebuildBudget()
    /// The last good beat this state wrote to the shared record (review F2).
    private var lastSharedAt: Date?

    /// How often the last good beat is written to the shared record: the reap
    /// window is 5 min, so 2 min keeps the record well inside it for one
    /// keychain write per four beats.
    static let shareEvery: TimeInterval = 120

    init() {}

    /// What to do with `reply`, about `keyId`, decided exactly as the host's
    /// `sendHeartbeat` decides it. nil: the key is already settled, so there is
    /// nothing to do.
    ///
    /// - Parameters:
    ///   - startedAt: when this key's beats began (the tunnel came up, or the
    ///     peer was swapped in): the reap clock's start until a good beat is
    ///     known, as the host starts it with its heartbeat.
    ///   - sharedLastOk: the last good beat of `keyId` in the shared record
    ///     (review F2). Every on-demand re-dial is a NEW provider instance, so
    ///     without it the clock restarted with each one, and the overnight
    ///     reap (`not_found` once the server forgot why) read as an ending
    ///     30 s after the re-dial.
    ///   - pending: the verdict already handed over for `keyId`, if any. An
    ///     ending only INFERRED from a missing record never replaces a rebuild
    ///     or a move handed over before it: the key is the same, only the clock
    ///     that inferred it is newer. The server's own words (revoked,
    ///     evicted, the allowance, a second reap) still do.
    mutating func decide(_ reply: HeartbeatResult, keyId: String, multiHop: Bool,
                         startedAt: Date, now: Date, sharedLastOk: Date? = nil,
                         pending: HeartbeatHandOff.Verdict? = nil) -> TunnelHeartbeatAction? {
        if keyId != self.keyId {
            self.keyId = keyId
            lastOkAt = nil
            lastSharedAt = nil
            settled = false
        }
        guard !settled else { return nil }
        let lastOk = [lastOkAt, sharedLastOk].compactMap { $0 }.max()
        let sinceLastOk = now.timeIntervalSince(lastOk ?? startedAt)
        let decided = HeartbeatPolicy.outcome(of: reply, sinceLastOk: sinceLastOk, multiHop: multiHop, now: now)
        let action: TunnelHeartbeatAction
        if case .end = decided, Self.isInferred(reply), let pending, pending.isLeftToHost {
            action = .leaveToHost(pending)
        } else {
            action = TunnelHeartbeatPolicy.action(for: budget.admit(decided, now: now))
        }
        if action == .keep {
            if reply.valid { lastOkAt = now }
        } else {
            settled = true
        }
        return action
    }

    /// The good beat to write to the shared record now, if one is due
    /// (`shareEvery`). Call `markShared` once it is written.
    var okToShare: Date? {
        guard let lastOkAt else { return nil }
        if let lastSharedAt, lastOkAt.timeIntervalSince(lastSharedAt) < Self.shareEvery { return nil }
        return lastOkAt
    }

    mutating func markShared(_ at: Date) {
        lastSharedAt = at
    }

    /// A reply whose ending is only inferred: the key is gone and the server
    /// no longer says why (`not_found`, or no reason this build knows).
    static func isInferred(_ reply: HeartbeatResult) -> Bool {
        !reply.valid && !reply.quotaExceeded && (reply.reason == nil || reply.reason == .notFound)
    }
}

/// The last good beat of a key, shared across extension instances and with
/// the host (review F2): a re-dial and a host started cold keep the reap clock
/// instead of restarting it.
struct HeartbeatLastOk: Codable, Equatable, Sendable {
    let keyId: String
    /// Unix seconds.
    let at: TimeInterval
}

/// What the extension's heartbeat decided about `keyId` and left to the host,
/// or could only half carry out itself. Kept in the shared keychain, one at a
/// time, the latest winning (`TunnelBreakerStore.heartbeatHandOff`). The host
/// acts on it only for the session it still holds (`keyId` is its
/// `activeKeyId`) and drops one about any other session.
struct HeartbeatHandOff: Codable, Equatable, Sendable {
    /// The outcome by a stable name: it crosses the process boundary.
    enum Verdict: String, Codable, CaseIterable, Sendable {
        case rebuild
        case moveServer = "move_server"
        case revoked
        case evicted
        case quotaExceeded = "quota_exceeded"
        case multiHopRouteOffline = "multi_hop_route_offline"
        case serverOffline = "server_offline"

        /// An ending by its sentence. An ending this build does not name is
        /// said as a revoke, the generic "the server ended it".
        init(ending: HeartbeatEnding) {
            if ending == .evicted {
                self = .evicted
            } else if ending == .quotaExceeded {
                self = .quotaExceeded
            } else if ending == .multiHopRouteOffline {
                self = .multiHopRouteOffline
            } else if ending == .serverOffline {
                self = .serverOffline
            } else {
                self = .revoked
            }
        }

        /// A rebuild or a move: the host's to carry out, the tunnel untouched.
        var isLeftToHost: Bool {
            self == .rebuild || self == .moveServer
        }

        /// What the host does with it: the outcome its own beat would have
        /// reached on the same reply. Only a move needs the host's context, the
        /// one fact a profile an older build saved cannot give the extension: a
        /// Multi-Hop pair is never moved, its session ends (HeartbeatPolicy).
        func hostOutcome(multiHop: Bool) -> HeartbeatOutcome {
            switch self {
            case .rebuild:
                return .rebuild
            case .moveServer:
                return multiHop ? .end(.multiHopRouteOffline) : .moveServer
            case .revoked:
                return .end(.revoked)
            case .evicted:
                return .end(.evicted)
            case .quotaExceeded:
                return .end(.quotaExceeded)
            case .multiHopRouteOffline:
                return .end(.multiHopRouteOffline)
            case .serverOffline:
                return .end(.serverOffline)
            }
        }
    }

    /// The key the verdict is about (the session's handle, not a secret).
    let keyId: String
    let verdict: Verdict
    /// When the extension decided (unix seconds). The host logs its age only.
    let decidedAt: TimeInterval
}
