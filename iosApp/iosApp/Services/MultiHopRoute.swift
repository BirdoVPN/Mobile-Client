import Foundation

/// The `multiHop` block of `/vpn/multi-hop/connect` — the backend's account of
/// the route it ACTUALLY installed. Field names mirror the shared KMP model
/// (`MultiHopInfo`/`MultiHopNodeInfo` in shared/model/Models.kt) that Android
/// decodes, and the desktop's `multi_hop` structs.
///
/// This file deliberately imports nothing beyond Foundation: the BirdoVPNTests
/// unit bundle compiles it directly (un-hosted, no KMP framework, no
/// WireGuardKit), so the route check stays testable on CI.
struct MultiHopNodeInfo: Decodable, Equatable, Sendable {
    let id: String
    let name: String
    let country: String
}

struct MultiHopRouteInfo: Decodable, Equatable, Sendable {
    let entryNode: MultiHopNodeInfo
    let exitNode: MultiHopNodeInfo
    /// Human-readable route, e.g. "DE -> NL".
    let route: String
}

enum MultiHopRouteValidationError: LocalizedError, Equatable {
    /// `success: true` but no route block: we cannot tell a real two-hop
    /// install from a single hop.
    case unconfirmed
    /// The server confirmed a DIFFERENT pair than the one requested.
    case mismatched(confirmedRoute: String)

    var errorDescription: String? {
        switch self {
        case .unconfirmed:
            return "The server did not confirm the Multi-Hop route. Not connecting, "
                + "because this could leave you on a single-hop tunnel while the app showed two."
        case .mismatched(let confirmedRoute):
            return "The server established a different Multi-Hop route (\(confirmedRoute)) "
                + "than the one selected. Not connecting."
        }
    }
}

/// VERIFY THE ROUTE WE ASKED FOR IS THE ROUTE WE GOT.
///
/// Ports the desktop (`vpn_multi_hop.rs`) and Android (`VpnManager.kt` #268)
/// check iOS never had: `success: true` only means the request was handled.
/// The response carries a `multiHop` block describing what was actually
/// installed; without checking it, every failure mode that still yields a
/// working single-hop tunnel (a skipped forwarding install, a fallback path,
/// a response for a different pair) is shown to the user as their chosen
/// multi-hop route. The user cannot observe their own egress country — this
/// client is the only thing that can tell them. Refuse instead: no tunnel is
/// recoverable, whereas a single hop believed to be two is a silent,
/// indefinite jurisdiction leak.
enum MultiHopRouteCheck {
    /// Returns the confirmed route, or throws `MultiHopRouteValidationError`.
    @discardableResult
    static func validate(
        _ route: MultiHopRouteInfo?,
        requestedEntryId: String,
        requestedExitId: String
    ) throws -> MultiHopRouteInfo {
        guard let route else {
            throw MultiHopRouteValidationError.unconfirmed
        }
        guard route.entryNode.id == requestedEntryId,
              route.exitNode.id == requestedExitId else {
            throw MultiHopRouteValidationError.mismatched(confirmedRoute: route.route)
        }
        return route
    }
}
