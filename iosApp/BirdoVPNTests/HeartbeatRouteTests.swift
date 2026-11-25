import XCTest

/// MR-2524 review F1 (vpn-94, 2026-11-25): a host started COLD carries out a
/// heartbeat rebuild or move only on the route the dialling process kept
/// (`HeartbeatRoute`, `HeartbeatRouting` in HeartbeatRoute.swift), never on
/// QuickSelect's guess. Before, a cold host's move of a USER pick went to the
/// best server anywhere, and a Multi-Hop pair could be rebuilt as a single
/// hop. TunnelHeartbeatWiringTests pins where VpnViewModel keeps, restores
/// and consults it.
final class HeartbeatRouteTests: XCTestCase {

    private struct Node: RelayRetryCandidate {
        let id: String
        var name: String { id }
        var countryCode = "DE"
        var city = "Frankfurt"
        var load: Int32 = 10
        var isOnline = true
        var accessible = true
    }

    /// nl-1 is the best server anywhere; de-1b is de-1's same-city twin.
    private let list = [Node(id: "de-1"),
                        Node(id: "de-1b", load: 60),
                        Node(id: "de-2", city: "Berlin", load: 50),
                        Node(id: "nl-1", countryCode: "NL", city: "Amsterdam", load: 1)]

    private func carry(_ outcome: HeartbeatOutcome, _ route: HeartbeatRouteKnowledge) -> HeartbeatOutcome? {
        HeartbeatRouting.carry(outcome, knowing: route)
    }

    /// The table: a rebuild or a move waits for the route, or ends without
    /// one; a keep or an ending never depends on it.
    func testARebuildOrAMoveWaitsForTheRouteAndNeverGuessesIt() {
        let routes: [HeartbeatRouteKnowledge] = [.known, .pending, .unlisted(pair: false), .unlisted(pair: true), .unknown]
        for route in routes {
            XCTAssertEqual(carry(.keep(nodeOffline: false, grace: nil), route), .keep(nodeOffline: false, grace: nil))
            XCTAssertEqual(carry(.end(.evicted), route), .end(.evicted))
            XCTAssertEqual(carry(.end(.quotaExceeded), route), .end(.quotaExceeded))
        }
        XCTAssertEqual(carry(.rebuild, .known), .rebuild)
        XCTAssertEqual(carry(.moveServer, .known), .moveServer)
        XCTAssertNil(carry(.rebuild, .pending), "a rebuild is dialled before the route is known")
        XCTAssertNil(carry(.moveServer, .pending), "a move is dialled before the route is known")
        XCTAssertEqual(carry(.rebuild, .unlisted(pair: false)), .end(.serverOffline))
        XCTAssertEqual(carry(.moveServer, .unlisted(pair: false)), .moveServer, "the move starts from the kept server")
        XCTAssertEqual(carry(.rebuild, .unlisted(pair: true)), .end(.multiHopRouteOffline))
        XCTAssertEqual(carry(.moveServer, .unlisted(pair: true)), .end(.multiHopRouteOffline))
        XCTAssertEqual(carry(.rebuild, .unknown), .end(.revoked), "nothing kept: never a guessed route")
        XCTAssertEqual(carry(.moveServer, .unknown), .end(.serverOffline), "nothing kept: never a guessed route")
    }

    /// The review's scenario: a USER picked de-1, iOS ended the app, the app
    /// started cold and the list loaded, then de-1 was drained. The kept route
    /// gives back the server AND the pick, so the move stays in Germany. The
    /// guess it replaces was the best server anywhere as an automatic pick.
    func testAColdHostMovesAUserPickOnlyWithinItsCountry() {
        let route = HeartbeatRoute(keyId: "key-a", serverId: "de-1", automatic: false,
                                   entryId: nil, exitId: nil, pairRoute: nil)
        XCTAssertFalse(route.isPair)
        guard case .single(let server) = route.match(in: list) else {
            return XCTFail("the dialled server is not found again")
        }
        XCTAssertEqual(server.id, "de-1")
        var drained = list
        drained[0].isOnline = false
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: server.id, in: drained, cached: drained,
                                                  origin: route.automatic ? .automatic : .user)?.server.id, "de-1b")
        XCTAssertEqual(QuickSelect.bestServer(in: drained)?.id, "nl-1", "the guess went abroad")
    }

    /// A DELETED server a cold host no longer finds in the list: the move
    /// starts from the kept id and pick. An automatic pick goes anywhere; a
    /// user pick, whose country nothing on this host knows any more, stops
    /// ("Choose another location"): never abroad.
    func testAColdHostNeverMovesAUserPickAbroadFromADeletedServer() {
        let route = HeartbeatRoute(keyId: "key-a", serverId: "de-1", automatic: false,
                                   entryId: nil, exitId: nil, pairRoute: nil)
        let deleted = Array(list.dropFirst())
        if case .unlisted = route.match(in: deleted) {} else { XCTFail("a deleted server is found") }
        XCTAssertNil(HeartbeatPolicy.moveTarget(from: "de-1", in: deleted, cached: deleted, origin: .user))
        XCTAssertEqual(HeartbeatPolicy.moveTarget(from: "de-1", in: deleted, cached: deleted,
                                                  origin: .automatic)?.server.id, "nl-1")
    }

    /// A pair comes back as the pair, so a rebuild keeps it and a drained node
    /// ends it. A node gone from the list ends it too: never a single hop.
    func testAColdHostKeepsAPairAPair() {
        let route = HeartbeatRoute(keyId: "key-a", serverId: nil, automatic: false,
                                   entryId: "de-1", exitId: "nl-1", pairRoute: "DE -> NL")
        XCTAssertTrue(route.isPair)
        guard case .pair(let entry, let exit) = route.match(in: list) else {
            return XCTFail("the pair is not found again")
        }
        XCTAssertEqual(entry.id, "de-1")
        XCTAssertEqual(exit.id, "nl-1")
        XCTAssertEqual(HeartbeatHandOff.Verdict.moveServer.hostOutcome(multiHop: true), .end(.multiHopRouteOffline))
        XCTAssertEqual(HeartbeatHandOff.Verdict.rebuild.hostOutcome(multiHop: true), .rebuild)
        if case .unlisted = route.match(in: [list[0], list[1]]) {} else { XCTFail("a pair without its exit is found") }
        XCTAssertEqual(carry(.rebuild, .unlisted(pair: true)), .end(.multiHopRouteOffline))
    }

    /// The kept route is server ids, the pick and the route label: nothing
    /// else, and it survives the round trip through UserDefaults' data.
    func testTheRouteIsKeptByIdsOnly() throws {
        let routes = [
            HeartbeatRoute(keyId: "key-a", serverId: "de-1", automatic: true, entryId: nil, exitId: nil, pairRoute: nil),
            HeartbeatRoute(keyId: "key-b", serverId: nil, automatic: false, entryId: "de-1", exitId: "nl-1",
                           pairRoute: "DE -> NL"),
        ]
        let allowed: Set<String> = ["keyId", "serverId", "automatic", "entryId", "exitId", "pairRoute"]
        for route in routes {
            let data = try JSONEncoder().encode(route)
            XCTAssertEqual(try JSONDecoder().decode(HeartbeatRoute.self, from: data), route)
            let keys = Set(((try JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]).keys)
            XCTAssertTrue(keys.isSubset(of: allowed), "\(keys.subtracting(allowed))")
        }
    }
}
