import Foundation

// MR-2524 review F1. A host started COLD (iOS ended the app while the tunnel
// ran) restores only `last_key_id`; the session's route (the server, who
// picked it, the Multi-Hop pair) lived in memory. Once the server list
// loaded, a heartbeat rebuild or move then dialled QuickSelect's best server
// as an automatic pick: a USER pick could move abroad and a pair could become
// a single hop, against the owner's rules. The extension's hand-off made that
// the common case. So the route a session was dialled on is kept next to
// `last_key_id`, a cold host matches it against the server list before it
// carries out a rebuild or a move, and without it nothing is dialled at all.
//
// Foundation-only, compiled into the app and BirdoVPNTests (project.yml);
// VpnViewModel keeps and restores it, HeartbeatRouteTests runs it.

/// The route a session was dialled on, as `VpnViewModel` holds it at
/// `.connected`. Server ids and the route label only: no secret, no address.
struct HeartbeatRoute: Codable, Equatable, Sendable {
    /// The session the route belongs to: only used for that `activeKeyId`.
    let keyId: String
    /// Single hop: the server dialled. nil for a Multi-Hop pair.
    let serverId: String?
    /// The client chose that server (MR-2212's `.automatic`); false is a user pick.
    let automatic: Bool
    /// Multi-Hop: the confirmed pair and its route label. nil for a single hop.
    let entryId: String?
    let exitId: String?
    let pairRoute: String?

    var isPair: Bool { entryId != nil && exitId != nil }

    /// The route found in a server list.
    enum Match<S> {
        case single(S)
        case pair(entry: S, exit: S)
        /// The server, or a node of the pair, is no longer listed.
        case unlisted
    }

    func match<S: RelayRetryCandidate>(in list: [S]) -> Match<S> {
        if let entryId, let exitId {
            guard let entry = list.first(where: { $0.id == entryId }),
                  let exit = list.first(where: { $0.id == exitId }) else { return .unlisted }
            return .pair(entry: entry, exit: exit)
        }
        guard let serverId, let server = list.first(where: { $0.id == serverId }) else { return .unlisted }
        return .single(server)
    }
}

/// What the host knows of the route of the session a rebuild or a move is for.
enum HeartbeatRouteKnowledge: Equatable, Sendable {
    /// In memory: this process dialled the session, or its kept route matched.
    case known
    /// A route is kept, but the server list to match it against is not loaded.
    case pending
    /// The kept route's server (or a node of its pair) is no longer listed.
    case unlisted(pair: Bool)
    /// Nothing kept and not dialled here (a session an older build dialled).
    case unknown
}

enum HeartbeatRouting {
    /// `outcome` as the host can carry it out on the route it knows; nil means
    /// wait (the next beat, or the next pass over a hand-off, asks again).
    /// Only a rebuild or a move needs the route; a keep or an ending never
    /// waits. The host never dials a guessed route:
    ///
    /// | route | rebuild | move |
    /// |---|---|---|
    /// | known | rebuild | move (owner rule for the pick) |
    /// | pending | wait | wait |
    /// | unlisted, single hop | end: this server went offline | move, from the kept server and pick |
    /// | unlisted, pair | end: a Multi-Hop server went offline | the same |
    /// | unknown | end: revoked | end: this server went offline |
    static func carry(_ outcome: HeartbeatOutcome, knowing route: HeartbeatRouteKnowledge) -> HeartbeatOutcome? {
        switch outcome {
        case .keep, .end:
            return outcome
        case .rebuild, .moveServer:
            break
        }
        switch route {
        case .known:
            return outcome
        case .pending:
            return nil
        case .unlisted(let pair):
            if pair { return .end(.multiHopRouteOffline) }
            return outcome == .rebuild ? .end(.serverOffline) : .moveServer
        case .unknown:
            return outcome == .rebuild ? .end(.revoked) : .end(.serverOffline)
        }
    }
}
