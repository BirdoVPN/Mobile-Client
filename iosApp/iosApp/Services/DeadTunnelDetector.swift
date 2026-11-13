import Foundation

/// MR-1622 -- fast dead-tunnel detection, and MR-1623 -- the old-key probe.
///
/// Foundation-only by design: this file is compiled into BOTH the PacketTunnel
/// extension (which runs the checks, because it is the only process alive while
/// the host app is suspended) and the BirdoVPNTests bundle (so the thresholds and
/// the verdict table are pinned by tests, not by a device that has to lose its
/// peer on cue).
///
/// ## MR-1622: what Android does, and what iOS now mirrors
///
/// Android's `TunnelMonitor` samples wg-go every 10 s and declares a tunnel dead
/// by either of two rules:
///
///   * FAST: our sends are going out (tx grows by more than the keepalives we
///     would send on our own) while NOTHING comes back (rx does not advance) for
///     20 s. WireGuard starts a handshake 15 s after an unanswered send and
///     retries every 5 s, so at 20 s one full handshake attempt has failed.
///   * BACKSTOP: the last handshake is older than 180 s (an idle healthy tunnel
///     re-handshakes at 120 s, plus one keepalive, at most 175 s).
///
/// iOS used to run only the backstop, on the 30 s heartbeat tick: 180 to 210 s
/// of a dead "Protected" screen. This is the same two rules on a 10 s tick.
///
/// A deep sleep between two checks makes the next verdict untrustworthy (the
/// handshake is old only because the CPU was off), so a gap longer than
/// `suspendGap` skips the verdict and starts over from the new reading.
enum DeadTunnelDetector {
    /// Seconds between two checks.
    static let checkInterval: TimeInterval = 10
    /// How long sends may go unanswered before the FAST rule fires.
    static let fastDead: TimeInterval = 20
    /// The backstop: the last handshake older than this means the peer is gone.
    static let stallThreshold: TimeInterval = 180
    /// Sleep between two checks longer than this voids the verdict.
    static let suspendGap: TimeInterval = 10
    /// A WireGuard keepalive on the wire: 16-byte header + 16-byte tag.
    static let keepaliveWireBytes: Int64 = 32
    /// `PersistentKeepalive` the host writes into every wg-quick config.
    static let keepaliveSeconds: Int = 25

    struct Sample: Equatable, Sendable {
        var rx: Int64
        var tx: Int64
        /// Age of the newest handshake across peers, nil = never handshook.
        var handshakeAge: TimeInterval?
    }

    /// State carried from one check to the next. A fresh value (`init()`) means
    /// "no previous reading", which is what a new session or a swapped peer gets.
    struct Liveness: Equatable, Sendable {
        var rx: Int64 = -1
        var tx: Int64 = -1
        /// When our sends first went unanswered, nil while the peer answers.
        var unansweredSince: TimeInterval?
        /// Wall-clock and monotonic stamps of the previous check, for `suspended`.
        var lastWall: TimeInterval?
        var lastUptime: TimeInterval?
    }

    enum Verdict: Equatable, Sendable {
        case alive
        /// FAST rule: tx grew, rx did not, for `fastDead`.
        case unanswered
        /// BACKSTOP: last handshake older than `stallThreshold`.
        case stalled
        /// wg-go reports no handshake at all. The CALLER decides whether that is
        /// dead yet (it knows how long the tunnel has existed).
        case neverHandshook
        /// A deep sleep voided this reading; the baseline was reset.
        case skippedAfterSuspend

        var isDead: Bool { self == .unanswered || self == .stalled }
    }

    /// The most our own keepalives can add to tx between two checks. Anything
    /// above it is data or a handshake initiation, both of which a live peer
    /// answers.
    static var keepaliveAllowance: Int64 {
        keepaliveWireBytes * (1 + Int64(checkInterval) / Int64(keepaliveSeconds))
    }

    /// Time spent asleep between two checks: wall-clock elapsed minus awake
    /// (monotonic) elapsed. Zero when there is no previous check.
    static func suspended(prev: Liveness, wall: TimeInterval, uptime: TimeInterval) -> TimeInterval {
        guard let w = prev.lastWall, let u = prev.lastUptime else { return 0 }
        return max(0, (wall - w) - (uptime - u))
    }

    /// One check, pure: fold `sample` into `prev` and judge it.
    ///
    /// - Parameters:
    ///   - sample: nil when wg-go's runtime config could not be read.
    ///   - now: monotonic seconds (`ProcessInfo.systemUptime`).
    ///   - wall: wall-clock seconds (`Date().timeIntervalSince1970`).
    static func assess(prev: Liveness,
                       sample: Sample?,
                       now: TimeInterval,
                       wall: TimeInterval) -> (Liveness, Verdict) {
        let gap = suspended(prev: prev, wall: wall, uptime: now)
        var stamped = prev
        stamped.lastWall = wall
        stamped.lastUptime = now

        if gap > suspendGap {
            var baseline = Liveness()
            if let sample { baseline.rx = sample.rx; baseline.tx = sample.tx }
            baseline.lastWall = wall
            baseline.lastUptime = now
            return (baseline, .skippedAfterSuspend)
        }
        guard let sample, let age = sample.handshakeAge else {
            return (stamped, .neverHandshook)
        }

        let first = prev.rx < 0
        let rxAdvanced = !first && sample.rx > prev.rx
        let txGrowth = first ? 0 : sample.tx - prev.tx
        let unansweredSince: TimeInterval?
        if first || rxAdvanced {
            unansweredSince = nil
        } else if txGrowth > keepaliveAllowance {
            unansweredSince = prev.unansweredSince ?? now
        } else {
            unansweredSince = prev.unansweredSince
        }

        var next = stamped
        next.rx = sample.rx
        next.tx = sample.tx
        next.unansweredSince = unansweredSince

        if let since = unansweredSince, now - since >= fastDead {
            return (next, .unanswered)
        }
        if age > stallThreshold { return (next, .stalled) }
        return (next, .alive)
    }

    /// Parse wg-go's UAPI dump: rx/tx summed over peers, and the age of the
    /// newest handshake (nil when no peer has ever handshaked).
    static func parseSample(uapi: String, nowEpoch: TimeInterval) -> Sample {
        var rx: Int64 = 0
        var tx: Int64 = 0
        var newest: Int64 = 0
        for line in uapi.split(separator: "\n") {
            if line.hasPrefix("rx_bytes=") {
                rx = rx &+ (Int64(line.dropFirst("rx_bytes=".count)) ?? 0)
            } else if line.hasPrefix("tx_bytes=") {
                tx = tx &+ (Int64(line.dropFirst("tx_bytes=".count)) ?? 0)
            } else if line.hasPrefix("last_handshake_time_sec=") {
                newest = max(newest, Int64(line.dropFirst("last_handshake_time_sec=".count)) ?? 0)
            }
        }
        let age: TimeInterval? = newest > 0 ? max(0, nowEpoch - TimeInterval(newest)) : nil
        return Sample(rx: rx, tx: tx, handshakeAge: age)
    }
}

/// MR-1623 -- ask the server why the tunnel died, BEFORE re-dialling it.
///
/// A re-dial of a peer the server has already deleted (revoked, evicted by the
/// device cap, replaced by another device) can never work and only burns the
/// breaker's budget while the user stares at a dead tunnel. Android and Desktop
/// ask `POST /vpn/heartbeat/{keyId}` about the key they were using -- the answer
/// is `{ valid, serverOnline, message? }` -- and this is the same call, made
/// about the OLD key the moment the detector declares the tunnel dead.
///
/// The probe is best-effort and FAIL-OPEN: the extension's session rides the
/// tunnel, so on a genuinely dead path it simply fails, and the outcome is
/// `.unknown`, which changes nothing (the existing bounded re-dial runs).
enum DeadTunnelProbe {
    /// The longest the extension waits for the answer before re-dialling anyway.
    static let timeout: TimeInterval = 5

    enum Outcome: Equatable, Sendable {
        /// `valid:false` -- the key is gone server-side.
        case keyRevoked
        /// `valid:true, serverOnline:false` -- the node is down.
        case serverOffline
        /// The key is fine and the node is up: the path or the peer process died.
        case keyValid
        /// No usable answer (transport failure, non-2xx, garbled body).
        case unknown
    }

    static func classify(statusCode: Int?, json: [String: Any]?) -> Outcome {
        guard let statusCode, (200...299).contains(statusCode), let json else { return .unknown }
        let valid = json["valid"] as? Bool
        let online = json["serverOnline"] as? Bool
        if valid == false { return .keyRevoked }
        if online == false { return .serverOffline }
        if valid == true { return .keyValid }
        return .unknown
    }

    /// Which breaker budget the failure is charged to. A revoked key can never
    /// recover by re-dialling; a down node will not come back by re-dialling the
    /// same node, so the user is steered to a different one (small budget).
    static func failureKind(base: TunnelFailureKind, outcome: Outcome) -> TunnelFailureKind {
        switch outcome {
        case .keyRevoked:    return .revoked
        case .serverOffline: return .neverEstablished
        case .keyValid, .unknown: return base
        }
    }

    /// What the host shows after the drop (`Tunnel failed: <reason>`), or nil to
    /// keep the generic "stopped responding" text. Short labels, not paragraphs.
    static func reason(for outcome: Outcome) -> String? {
        switch outcome {
        case .keyRevoked:    return "connection revoked"
        case .serverOffline: return "server offline"
        case .keyValid, .unknown: return nil
        }
    }
}
