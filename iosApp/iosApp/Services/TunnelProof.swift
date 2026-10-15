import Foundation

/// MR-974 — what the app may say about the tunnel, and when it may say
/// "Protected".
///
/// `NEVPNStatus.connected` means the PacketTunnel extension STARTED: it calls
/// `completionHandler(nil)` as soon as WireGuardKit has accepted the
/// configuration (`PacketTunnelProvider.startTunnel`), before any handshake. A
/// tunnel pointed at a peer that never answers — a revoked key, a dead node, a
/// network that drops WireGuard — reaches `.connected` just the same, and Home
/// painted "Protected" off that alone, over a tunnel carrying nothing.
///
/// Android publishes Connected only once its transport probe has OBSERVED a
/// handshake (`BirdoVpnService.publishConnected`, after TransportProbe's
/// HANDSHAKE_OK); until then its UI says Connecting. This is the iOS half. The
/// proof is inbound bytes on THIS session (`rx > 0` in the extension's `stats`
/// reply, wireguard-go's per-peer rx_bytes): the same proof
/// `armKillSwitchAfterHandshake` arms the kill switch on, because WireGuard
/// cannot deliver a single received byte without a completed handshake.
///
/// Foundation-only on purpose: VpnViewModel feeds the latch from the places it
/// already samples the extension's counters, and BirdoVPNTests
/// (TunnelProofTests) pins the mapping, which VpnViewModel itself cannot be
/// compiled into.
enum TunnelPhase: Equatable, Sendable {
    /// No session.
    case disconnected
    /// Dialling, or the extension is up but no handshake has been seen on this
    /// session yet. Home says "Connecting…".
    case connecting
    /// A handshake has been proven on this session. The only phase Home, the
    /// globe, the server list and the Profile row may call "Protected".
    case protected

    /// `isConnected` / `isConnecting` are VpnViewModel's mirrors of
    /// `NEVPNStatus` (`.reasserting` raises `isConnecting` with `isConnected`
    /// still set); `handshakeProven` is `HandshakeLatch.isProven`.
    static func resolve(isConnected: Bool, isConnecting: Bool, handshakeProven: Bool) -> TunnelPhase {
        // PRE-FIX behaviour (red commit): `.connected` alone painted Protected.
        if isConnected { return .protected }
        return isConnecting ? .connecting : .disconnected
    }
}

/// The per-session handshake proof behind `TunnelPhase.protected`.
///
/// A session starts unproven (`reset()`) when the tunnel comes up from down,
/// when it goes down, and when a live rebuild puts a different peer under the
/// running session. Each reset starts a new `session` number, and a sample
/// counts only for the session it was requested in: a `stats` reply already in
/// flight when the peer changed carries the OLD peer's bytes and must not prove
/// the new one.
///
/// A reassert (network change) is NOT a new session: the peer and its proof
/// carry over, and the extension's liveness check owns a peer that stops
/// answering.
struct HandshakeLatch: Equatable, Sendable {
    private(set) var session = 0
    private(set) var isProven = false

    mutating func reset() {
        session &+= 1
        isProven = false
    }

    /// Fold in one `stats` sample that was requested while `session` was
    /// `sampledIn`. Returns true only for the sample that proved the session,
    /// so the caller publishes once.
    @discardableResult
    mutating func observe(rx: Int64, sampledIn: Int) -> Bool {
        // PRE-FIX behaviour (red commit): any sample of a started tunnel counted.
        guard !isProven else { return false }
        isProven = true
        return true
    }
}
