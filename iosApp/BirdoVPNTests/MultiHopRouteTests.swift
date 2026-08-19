import XCTest

/// Ports Android's `VpnManagerTest` multi-hop route cases (commit 29f9e4d /
/// #268) to the iOS check in MultiHopRoute.swift, which is compiled directly
/// into this un-hosted bundle — no app host, no KMP framework, no WireGuardKit.
///
/// The check exists because `success: true` only means the request was
/// handled: without verifying the response's `multiHop` block, a working
/// single-hop tunnel is rendered as the user's chosen two-hop route — a
/// silent, indefinite jurisdiction leak the user cannot observe themselves.
final class MultiHopRouteTests: XCTestCase {

    /// A route the client will accept for a de-1 -> nl-1 request. Build mock
    /// routes through here so a future field addition breaks in one place
    /// (mirrors Android's `makeRoute`).
    private func makeRoute(entryId: String = "de-1",
                           exitId: String = "nl-1") -> MultiHopRouteInfo {
        MultiHopRouteInfo(
            entryNode: MultiHopNodeInfo(id: entryId, name: "Frankfurt", country: "DE"),
            exitNode: MultiHopNodeInfo(id: exitId, name: "Amsterdam", country: "NL"),
            route: "DE -> NL"
        )
    }

    func testAcceptsTheConfirmedPair() throws {
        let confirmed = try MultiHopRouteCheck.validate(
            makeRoute(),
            requestedEntryId: "de-1",
            requestedExitId: "nl-1"
        )
        XCTAssertEqual(confirmed.route, "DE -> NL")
    }

    func testRefusesAResponseWithNoRouteBlock() {
        // `success: true` only means the request was handled. Without the route
        // block we cannot tell a real two-hop install from a single hop, and
        // the user cannot observe their own egress country — so refuse.
        XCTAssertThrowsError(try MultiHopRouteCheck.validate(
            nil,
            requestedEntryId: "de-1",
            requestedExitId: "nl-1"
        )) { error in
            XCTAssertEqual(error as? MultiHopRouteValidationError, .unconfirmed)
        }
    }

    func testRefusesARouteNamingADifferentPair() {
        // The backend confirmed a DIFFERENT exit. Connecting anyway would
        // render the user's chosen route while egressing somewhere else
        // indefinitely.
        XCTAssertThrowsError(try MultiHopRouteCheck.validate(
            makeRoute(entryId: "de-1", exitId: "us-9"),
            requestedEntryId: "de-1",
            requestedExitId: "nl-1"
        )) { error in
            XCTAssertEqual(error as? MultiHopRouteValidationError,
                           .mismatched(confirmedRoute: "DE -> NL"))
            // The refusal must SAY which route came back, so a bug report
            // carries the mismatch rather than "it didn't work".
            XCTAssertTrue(error.localizedDescription.contains("DE -> NL"))
        }
    }

    func testRefusesADifferentEntryEvenWithTheRightExit() {
        XCTAssertThrowsError(try MultiHopRouteCheck.validate(
            makeRoute(entryId: "fr-2", exitId: "nl-1"),
            requestedEntryId: "de-1",
            requestedExitId: "nl-1"
        ))
    }

    func testDecodesTheWireShape() throws {
        // Field names are the wire contract shared with Android
        // (`MultiHopInfo`/`MultiHopNodeInfo` in shared/model/Models.kt) and the
        // backend's multi-hop connect response. If this decode breaks, the
        // check above would refuse EVERY multi-hop connect — fail here first.
        let json = Data("""
        {
          "entryNode": { "id": "de-1", "name": "Frankfurt", "country": "DE", "region": "eu" },
          "exitNode":  { "id": "nl-1", "name": "Amsterdam", "country": "NL", "region": "eu" },
          "route": "DE -> NL"
        }
        """.utf8)
        let route = try JSONDecoder().decode(MultiHopRouteInfo.self, from: json)
        XCTAssertEqual(route, makeRoute())
    }
}
