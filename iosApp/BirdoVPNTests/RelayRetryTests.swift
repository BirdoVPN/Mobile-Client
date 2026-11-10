import XCTest

/// A-H15 / MR-2212: which relay the one automatic retry goes to.
final class RelayRetryTests: XCTestCase {

    private struct Node: RelayRetryCandidate {
        let id: String
        var name: String { id }
        var countryCode = "DE"
        var city = "Frankfurt"
        var load: Int32 = 10
        var isOnline = true
        var accessible = true
    }

    func testPicksTheLeastLoadedOnlineSiblingInTheSameCity() {
        let list = [Node(id: "a"), Node(id: "b", load: 40), Node(id: "c", load: 5)]
        XCTAssertEqual(RelayRetry.sibling(of: "a", in: list)?.id, "c")
    }

    func testNeverReturnsTheRelayThatFailed() {
        XCTAssertNil(RelayRetry.sibling(of: "a", in: [Node(id: "a")]))
    }

    func testOtherCitiesAndCountriesAreNotSiblings() {
        let list = [Node(id: "a"), Node(id: "b", city: "Berlin"), Node(id: "c", countryCode: "NL")]
        XCTAssertNil(RelayRetry.sibling(of: "a", in: list))
    }

    func testOfflineAndOutOfPlanSiblingsAreSkipped() {
        let list = [Node(id: "a"), Node(id: "b", isOnline: false), Node(id: "c", accessible: false),
                    Node(id: "d", load: 90)]
        XCTAssertEqual(RelayRetry.sibling(of: "a", in: list)?.id, "d")
    }

    func testCaseAndWhitespaceInTheLocationAreIgnored() {
        let list = [Node(id: "a"), Node(id: "b", countryCode: "de", city: " frankfurt ")]
        XCTAssertEqual(RelayRetry.sibling(of: "a", in: list)?.id, "b")
    }

    func testAnEmptyCityNeverMatches() {
        let list = [Node(id: "a", city: ""), Node(id: "b", city: "")]
        XCTAssertNil(RelayRetry.sibling(of: "a", in: list))
    }

    func testAnUnknownFailedIdYieldsNothing() {
        XCTAssertNil(RelayRetry.sibling(of: "zzz", in: [Node(id: "a"), Node(id: "b")]))
    }
}
