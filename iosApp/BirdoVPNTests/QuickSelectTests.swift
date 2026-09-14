import XCTest

/// OPEN-WORK K10 — pins `QuickSelect.bestServer(in:)`, the rule behind both
/// Apple pre-select paths in `VpnViewModel` (`loadServers()` and the
/// auto-connect-on-launch task). Same four cases as the Desktop
/// `pick_quick_connect_server` tests, so the three clients agree on what
/// "pick one for me" means.
///
/// `ServerInfo` is not compiled into this un-hosted bundle (it lives in a
/// SwiftUI view file), so the cases run on a four-field stand-in; the app
/// target's `extension ServerInfo: QuickSelectCandidate {}` is what ties the
/// real type to the same rule, and it is checked by the compiler.
final class QuickSelectTests: XCTestCase {

    private struct Node: QuickSelectCandidate, Equatable {
        let name: String
        let load: Int32
        let isOnline: Bool
        let accessible: Bool
    }

    private func node(_ name: String, load: Int32,
                      online: Bool = true, accessible: Bool = true) -> Node {
        Node(name: name, load: load, isOnline: online, accessible: accessible)
    }

    func testPicksTheLeastLoadedNodeNotTheFirst() {
        // The list arrives name-sorted, so `.first` used to mean "A" here
        // regardless of load. The whole point of K10 is that it does not.
        let list = [node("A", load: 60), node("B", load: 10)]
        XCTAssertEqual(QuickSelect.bestServer(in: list), list[1])
    }

    func testSkipsANodeThePlanCannotReachEvenWhenItIsEmptier() {
        // A free user must not be pre-selected onto a paid node: the backend
        // would refuse the connect and the user would see a failure for a
        // choice they never made.
        let list = [node("A", load: 5, accessible: false), node("B", load: 40)]
        XCTAssertEqual(QuickSelect.bestServer(in: list), list[1])
    }

    func testAllOfflineYieldsNothing() {
        // Nothing to pre-select: leaving `selectedServer` nil is the correct
        // outcome. Falling back to an offline node would fail on Connect.
        let list = [node("A", load: 0, online: false), node("B", load: 0, online: false)]
        XCTAssertNil(QuickSelect.bestServer(in: list))
        XCTAssertNil(QuickSelect.bestServer(in: [Node]()))
    }

    func testEqualLoadsFallBackToTheNameWhateverTheListOrder() {
        // A fresh fleet reports 0 everywhere. The pick must then be the
        // alphabetical one users have always seen, and it must not depend on
        // the order the backend happened to send — the same input in two
        // orders is the same answer.
        let sorted = [node("Amsterdam", load: 0), node("Berlin", load: 0)]
        let reversed = Array(sorted.reversed())
        XCTAssertEqual(QuickSelect.bestServer(in: sorted), sorted[0])
        XCTAssertEqual(QuickSelect.bestServer(in: reversed), sorted[0])
    }

    func testLoadBeatsNameOrder() {
        // Name is ONLY the tie-break: an alphabetically-later node with a
        // lower load still wins.
        let list = [node("Amsterdam", load: 1), node("Zurich", load: 0)]
        XCTAssertEqual(QuickSelect.bestServer(in: list), list[1])
    }
}
