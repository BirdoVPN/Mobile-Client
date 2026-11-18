import Foundation

/// MR-1620 / MR-1621 -- the one place that names the connection state the user
/// sees and hears.
///
/// Foundation-only by design: the BirdoVPNTests bundle compiles this file
/// directly (un-hosted), so the mapping from the view model's flags to a label
/// and to a VoiceOver announcement is pinned by tests instead of by a device
/// run with VoiceOver on. `VpnViewModel.swift` cannot be compiled there.
enum ConnectionPhase: Equatable, Sendable {
    case disconnected
    case connecting
    /// The tunnel was up and is being brought back: a `.reasserting` status
    /// (a live re-key, or no network path: the extension's adapter pauses and
    /// raises it, vendored WireGuardKit local patch 5), or a session that
    /// dropped and that the system is dialling again (`ReconnectWatch`).
    case reconnecting
    case protected
    /// The last attempt, or the live tunnel, ended in an error.
    case failed

    /// Resolve the phase from `TunnelPhase` (MR-974: "Protected" only after a
    /// proven handshake) plus the flags that refine it. Precedence matters: a
    /// live switch and a re-dial both keep the tunnel "up" for a moment, and
    /// neither may read as "Protected".
    ///
    /// - Parameter awaitingRedial: MR-2371. The session dropped all the way
    ///   down and the system's on-demand rule will dial it again
    ///   (`ReconnectWatch.awaitingRedial`): still "Reconnecting…", not
    ///   "Connection failed". Without it a reconnect flag is ignored once the
    ///   tunnel is gone.
    static func resolve(tunnel: TunnelPhase,
                        isSwitching: Bool,
                        isReconnecting: Bool,
                        awaitingRedial: Bool = false,
                        hasError: Bool) -> ConnectionPhase {
        // A live server switch also raises `.reasserting` (the extension swaps
        // the peer in place) -- that is "Switching", never "Reconnecting".
        if isSwitching { return .connecting }
        if isReconnecting && (tunnel != .disconnected || awaitingRedial) { return .reconnecting }
        switch tunnel {
        case .protected:    return .protected
        case .connecting:   return .connecting
        case .disconnected: return hasError ? .failed : .disconnected
        }
    }

    /// The status pill text while re-dialling. The other phases keep their
    /// existing labels in `HomeView` (Protected adds the Multi-Hop suffix there).
    static let reconnectingLabel = "Reconnecting…"

    /// What VoiceOver should say on arriving at `new` from `old`, or nil when
    /// nothing should be spoken (no change, or the app launching disconnected).
    ///
    /// MR-2371: "Connection failed" only when a connection that was being made,
    /// or was up, failed. An error while idle ("Select a server first", "Sign
    /// in to connect.") is not one, and its banner (`ErrorBanner`) already
    /// announces its own words.
    static func announcement(from old: ConnectionPhase, to new: ConnectionPhase) -> String? {
        guard old != new else { return nil }
        switch new {
        case .connecting:    return "Connecting"
        case .protected:     return "Protected"
        case .reconnecting:  return "Reconnecting"
        case .disconnected:  return "Disconnected"
        case .failed:
            switch old {
            case .connecting, .reconnecting, .protected: return "Connection failed"
            case .disconnected, .failed: return nil
            }
        }
    }
}

/// MR-2371 -- what the Home button does. Reconnecting offers Disconnect, the
/// way out of a recovery that is not coming back, as on Android
/// (`HomeConnectionModel.ctaModel`: "Reconnecting and a held block both offer
/// Disconnect"). It used to be a disabled spinner for the whole reassert.
enum ConnectAction: Equatable, Sendable {
    case connect
    case disconnect
    /// Dialling, or a live switch: nothing to tap.
    case busy

    static func resolve(phase: ConnectionPhase,
                        isSwitching: Bool,
                        isConnected: Bool,
                        isConnecting: Bool) -> ConnectAction {
        // A switch must not offer Disconnect over a half-swapped tunnel.
        if isSwitching { return .busy }
        if phase == .reconnecting || isConnected { return .disconnect }
        if isConnecting { return .busy }
        return .connect
    }
}

/// MR-2371 -- whether the session the user had is being brought back, so
/// Home says (and VoiceOver speaks) "Reconnecting…" for the whole of it, not
/// only inside a live re-key:
///   * a reassert (`.reasserting`): a re-key, or no network path (the
///     extension's adapter pauses the backend and raises `reasserting`,
///     vendored WireGuardKit local patch 5), so a path loss no longer reads
///     "Protected";
///   * a real drop that the SYSTEM dials again (the on-demand Connect rule is
///     armed: the kill switch): `.disconnecting` / `.disconnected` (no longer
///     "Connection failed"), `.connecting`, then the new session's
///     `.connected` until it proves a handshake (`proven()`).
/// Not for what the app drives itself (a live switch, a settings rebuild, a
/// teardown before its own re-dial: `appDriven`), nor for a drop nothing will
/// dial again, nor once the app takes over (`stop()`: a tap on Connect or
/// Disconnect, the circuit breaker's fail-open, sign-out, or the system's
/// on-demand rule found disarmed).
///
/// Foundation-only, pinned by ConnectionPhaseTests; VpnViewModel feeds it.
struct ReconnectWatch: Equatable, Sendable {
    /// `NEVPNStatus`, as far as this needs it (`.invalid` is `.disconnected`).
    enum Status: Equatable, Sendable {
        case connected, connecting, reasserting, disconnecting, disconnected
    }

    /// What VpnViewModel publishes as `isReconnecting`.
    private(set) var isReconnecting = false
    /// The session being brought back is all the way down and the system has
    /// not started its re-dial yet.
    private(set) var awaitingRedial = false
    /// The last status was `.reasserting`: a proof sampled meanwhile is the
    /// old path's, and does not end it.
    private var inReassert = false

    init() {}

    /// One status transition.
    /// - Parameters:
    ///   - wasConnected: `isConnected` before this status (the session had
    ///     reached `.connected`).
    ///   - wasProven: "Protected" before this status (a handshake was proven
    ///     on that session).
    ///   - appDriven: the app itself is switching, rebuilding, re-applying
    ///     settings or tearing down before its own re-dial.
    ///   - redialsOnDrop: the system holds an armed on-demand Connect rule for
    ///     this configuration (`KillSwitchSystemState.redialsOnDrop`).
    mutating func observe(_ status: Status,
                          wasConnected: Bool,
                          wasProven: Bool,
                          appDriven: Bool,
                          redialsOnDrop: Bool) {
        inReassert = status == .reasserting
        switch status {
        case .reasserting:
            isReconnecting = true
            awaitingRedial = false
        case .connecting:
            // A connect that begins while a session was live, or after a drop
            // the system is re-dialling, is a re-dial, not a first connect.
            isReconnecting = isReconnecting || wasConnected
            awaitingRedial = false
        case .connected:
            // A recovery stays "Reconnecting…" until the new session proves a
            // handshake; one the app drove reads as before ("Connecting…"
            // until then).
            if appDriven { isReconnecting = false }
            awaitingRedial = false
        case .disconnecting, .disconnected:
            let live = (wasConnected && wasProven) || isReconnecting
            if live && !appDriven && redialsOnDrop {
                isReconnecting = true
                awaitingRedial = true
            } else {
                stop()
            }
        }
    }

    /// The current session proved a handshake: it is back. Not while the
    /// system still says `.reasserting`: that ends with `.connected`.
    mutating func proven() {
        guard !inReassert else { return }
        isReconnecting = false
        awaitingRedial = false
    }

    /// The system's configuration, read fresh: a drop it will not dial again
    /// is not "Reconnecting…" (the VPN or its On Demand turned off in iOS
    /// Settings). A reassert is the extension's own and is left alone.
    mutating func systemChanged(redialsOnDrop: Bool) {
        if awaitingRedial && !redialsOnDrop { stop() }
    }

    /// The app took over: a dial or a disconnect of its own, the circuit
    /// breaker's fail-open, sign-out.
    mutating func stop() {
        isReconnecting = false
        awaitingRedial = false
        inReassert = false
    }
}
