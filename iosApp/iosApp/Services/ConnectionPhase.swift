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
    /// The tunnel was up and is being re-dialled (a `.reasserting` status, or a
    /// connect that begins while a session was live).
    case reconnecting
    case protected
    /// The last attempt, or the live tunnel, ended in an error.
    case failed

    /// Resolve the phase from `TunnelPhase` (MR-974: "Protected" only after a
    /// proven handshake) plus the flags that refine it. Precedence matters: a
    /// live switch and a re-dial both keep the tunnel "up" for a moment, and
    /// neither may read as "Protected".
    static func resolve(tunnel: TunnelPhase,
                        isSwitching: Bool,
                        isReconnecting: Bool,
                        hasError: Bool) -> ConnectionPhase {
        // A live server switch also raises `.reasserting` (the extension swaps
        // the peer in place) -- that is "Switching", never "Reconnecting".
        if isSwitching { return .connecting }
        if isReconnecting && tunnel != .disconnected { return .reconnecting }
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
    static func announcement(from old: ConnectionPhase, to new: ConnectionPhase) -> String? {
        guard old != new else { return nil }
        switch new {
        case .connecting:    return "Connecting"
        case .protected:     return "Protected"
        case .reconnecting:  return "Reconnecting"
        case .disconnected:  return "Disconnected"
        case .failed:        return "Connection failed"
        }
    }
}
