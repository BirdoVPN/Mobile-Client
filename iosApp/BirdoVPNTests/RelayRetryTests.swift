import XCTest

/// A-H15 / MR-2212: which relay the one automatic retry goes to.
///
/// Owner rule, 2026-11-19: a server the client chose may move to another
/// relay in any country; a location the user picked never leaves its country;
/// never a node the server lists offline (a jurisdiction hold depools the
/// node) or one this plan cannot use; ONE retry; the log says counts only.
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

    // MARK: - #549's same-city rule (unchanged)

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

    // MARK: - The fleet as it is: one relay per city

    /// `birdo-infra:deploy/fleet-nodes.conf`: 10 relays, 10 cities, 9 countries.
    private var fleet: [Node] {
        [Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 30),
         Node(id: "fra", countryCode: "DE", city: "Frankfurt", load: 10),
         Node(id: "lon", countryCode: "GB", city: "London", load: 25),
         Node(id: "lax", countryCode: "US", city: "Los Angeles", load: 40),
         Node(id: "nyc", countryCode: "US", city: "New York", load: 15),
         Node(id: "sao", countryCode: "BR", city: "São Paulo", load: 35),
         Node(id: "sin", countryCode: "SG", city: "Singapore", load: 45),
         Node(id: "sto", countryCode: "SE", city: "Stockholm", load: 20),
         Node(id: "tyo", countryCode: "JP", city: "Tokyo", load: 50),
         Node(id: "yyz", countryCode: "CA", city: "Toronto", load: 55)]
    }

    /// Why #549 never fired: no relay in today's fleet has a same-city sibling.
    func testOnTodaysFleetTheSameCityRuleNeverFindsARelay() {
        for node in fleet {
            XCTAssertNil(RelayRetry.sibling(of: node.id, in: fleet), node.id)
        }
    }

    /// The widened rule on the same fleet: an automatic pick always has
    /// somewhere to go; a user's pick only where its country has a second
    /// relay (today the United States).
    func testOnTodaysFleetOnlyAnAutomaticPickLeavesTheCountry() {
        for node in fleet {
            let auto = RelayRetry.next(after: node.id, in: fleet, origin: .automatic)
            XCTAssertNotNil(auto, node.id)
            XCTAssertNotEqual(auto?.server.id, node.id)
            let user = RelayRetry.next(after: node.id, in: fleet, origin: .user)
            if node.countryCode == "US" {
                XCTAssertEqual(user?.server.countryCode, "US", node.id)
                XCTAssertEqual(user?.reach, .sameCountry, node.id)
            } else {
                XCTAssertNil(user, "\(node.id): a user's location never leaves its country")
            }
        }
        XCTAssertEqual(RelayRetry.next(after: "nyc", in: fleet, origin: .user)?.server.id, "lax")
        XCTAssertEqual(RelayRetry.next(after: "lax", in: fleet, origin: .user)?.server.id, "nyc")
    }

    // MARK: - Automatic pick: may move to another country, once

    func testAnAutomaticPickMovesToTheBestRelayInAnotherCountry() {
        let list = [Node(id: "fra"),
                    Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 30),
                    Node(id: "sto", countryCode: "SE", city: "Stockholm", load: 20)]
        let choice = RelayRetry.next(after: "fra", in: list, origin: .automatic)
        XCTAssertEqual(choice?.server.id, "sto", "the least loaded of the other relays")
        XCTAssertEqual(choice?.reach, .otherCountry)
    }

    func testAnAutomaticPickStillTakesTheSameCityFirst() {
        let list = [Node(id: "fra"), Node(id: "fra2", load: 60),
                    Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 0)]
        let choice = RelayRetry.next(after: "fra", in: list, origin: .automatic)
        XCTAssertEqual(choice?.server.id, "fra2")
        XCTAssertEqual(choice?.reach, .sameCity)
    }

    func testAnAutomaticPickInsideItsOwnCountryIsReportedAsSuch() {
        let list = [Node(id: "nyc", countryCode: "US", city: "New York"),
                    Node(id: "lax", countryCode: "US", city: "Los Angeles", load: 1),
                    Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 50)]
        XCTAssertEqual(RelayRetry.next(after: "nyc", in: list, origin: .automatic)?.reach, .sameCountry)
    }

    func testAnAutomaticPickMovesToAnotherCountryExactlyOnce() {
        let list = [Node(id: "fra"),
                    Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 30),
                    Node(id: "sto", countryCode: "SE", city: "Stockholm", load: 20)]
        var budget = RelayRetryBudget()
        // Frankfurt does not answer: one move, to another country.
        let first = RelayRetry.plan(after: "fra", in: list, origin: .automatic, budget: &budget)
        XCTAssertEqual(first?.server.id, "sto")
        XCTAssertEqual(first?.reach, .otherCountry)
        XCTAssertTrue(budget.spent)
        // Stockholm, the retry, does not answer either: no second move, even
        // though Amsterdam is still there to try.
        XCTAssertNil(RelayRetry.plan(after: "sto", in: list, origin: .automatic, budget: &budget))
        XCTAssertNil(RelayRetry.plan(after: "fra", in: list, origin: .automatic, budget: &budget))
        XCTAssertTrue(budget.spent)
    }

    // MARK: - User pick: never leaves its country

    func testAUserPickNeverLeavesItsCountry() {
        let list = [Node(id: "fra"),
                    Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 0),
                    Node(id: "lon", countryCode: "GB", city: "London", load: 0)]
        XCTAssertNil(RelayRetry.next(after: "fra", in: list, origin: .user))
        var budget = RelayRetryBudget()
        XCTAssertNil(RelayRetry.plan(after: "fra", in: list, origin: .user, budget: &budget))
        XCTAssertFalse(budget.spent, "no retry happened, so none was spent")
    }

    func testAUserPickMayMoveWithinItsCountry() {
        let list = [Node(id: "nyc", countryCode: "US", city: "New York"),
                    Node(id: "lax", countryCode: "US", city: "Los Angeles", load: 70),
                    Node(id: "yyz", countryCode: "CA", city: "Toronto", load: 0)]
        let choice = RelayRetry.next(after: "nyc", in: list, origin: .user)
        XCTAssertEqual(choice?.server.id, "lax", "never Toronto, however empty")
        XCTAssertEqual(choice?.reach, .sameCountry)
    }

    func testAUserPickPrefersTheSameCity() {
        let list = [Node(id: "nyc", countryCode: "US", city: "New York"),
                    Node(id: "nyc2", countryCode: "US", city: "New York", load: 80),
                    Node(id: "lax", countryCode: "US", city: "Los Angeles", load: 0)]
        let choice = RelayRetry.next(after: "nyc", in: list, origin: .user)
        XCTAssertEqual(choice?.server.id, "nyc2")
        XCTAssertEqual(choice?.reach, .sameCity)
    }

    func testAUserPickWithNoKnownCountryStaysPut() {
        let list = [Node(id: "a", countryCode: " ", city: ""),
                    Node(id: "b", countryCode: " ", city: "", load: 0),
                    Node(id: "c", countryCode: "NL", city: "Amsterdam", load: 0)]
        XCTAssertNil(RelayRetry.next(after: "a", in: list, origin: .user))
    }

    func testAUserPickInTheSameCountryIsMatchedWhateverTheCase() {
        let list = [Node(id: "nyc", countryCode: "US", city: "New York"),
                    Node(id: "lax", countryCode: " us ", city: "Los Angeles")]
        XCTAssertEqual(RelayRetry.next(after: "nyc", in: list, origin: .user)?.server.id, "lax")
    }

    // MARK: - Held or ineligible nodes are never picked

    /// A node held for a jurisdiction mismatch fails its health check and is
    /// listed `isOnline: false`; a node above the plan is `accessible: false`.
    func testAHeldOrIneligibleNodeIsNeverPicked() {
        for origin in [SelectionOrigin.automatic, .user] {
            let list = [Node(id: "nyc", countryCode: "US", city: "New York"),
                        Node(id: "nyc-held", countryCode: "US", city: "New York", load: 0, isOnline: false),
                        Node(id: "lax-paid", countryCode: "US", city: "Los Angeles", load: 0, accessible: false),
                        Node(id: "lax-held", countryCode: "US", city: "Los Angeles", load: 0, isOnline: false),
                        Node(id: "lax", countryCode: "US", city: "Los Angeles", load: 90)]
            XCTAssertEqual(RelayRetry.next(after: "nyc", in: list, origin: origin)?.server.id, "lax", "\(origin)")
        }
        let onlyIneligible = [Node(id: "fra"),
                              Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 0, isOnline: false),
                              Node(id: "lon", countryCode: "GB", city: "London", load: 0, accessible: false)]
        XCTAssertNil(RelayRetry.next(after: "fra", in: onlyIneligible, origin: .automatic))
        var budget = RelayRetryBudget()
        XCTAssertNil(RelayRetry.plan(after: "fra", in: onlyIneligible, origin: .automatic, budget: &budget))
        XCTAssertFalse(budget.spent)
    }

    func testTheFailedRelayIsNeverTheTarget() {
        for origin in [SelectionOrigin.automatic, .user] {
            XCTAssertNil(RelayRetry.next(after: "a", in: [Node(id: "a", load: 0)], origin: origin))
        }
    }

    func testAnUnknownFailedRelayMovesNothing() {
        let list = [Node(id: "a"), Node(id: "b", countryCode: "NL", city: "Amsterdam")]
        for origin in [SelectionOrigin.automatic, .user] {
            XCTAssertNil(RelayRetry.next(after: "zzz", in: list, origin: origin))
        }
    }

    // MARK: - Exactly one retry

    func testTheBudgetAllowsExactlyOneRetry() {
        var budget = RelayRetryBudget()
        XCTAssertFalse(budget.spent)
        XCTAssertTrue(budget.take())
        XCTAssertFalse(budget.take(), "a second retry")
        XCTAssertFalse(budget.take())
        XCTAssertTrue(budget.spent)
        // A connect the user asks for, or a proven handshake, is a new start.
        budget.refill()
        XCTAssertTrue(budget.take())
        XCTAssertFalse(budget.take())
    }

    func testASpentBudgetPlansNothingAndChangesNothing() {
        var budget = RelayRetryBudget()
        _ = budget.take()
        let before = budget
        XCTAssertNil(RelayRetry.plan(after: "a", in: [Node(id: "a"), Node(id: "b")], origin: .automatic,
                                     budget: &budget))
        XCTAssertEqual(budget, before)
    }

    // MARK: - Counts only in the log

    func testTheRetryLogLineNamesNoRelay() {
        // Distinct, greppable markers in every field a log line could leak.
        let failed = Node(id: "id-marker-failed", countryCode: "QX", city: "Failedcityx", load: 1)
        let near = Node(id: "id-marker-near", countryCode: "QX", city: "Nearcityx", load: 2)
        let far = Node(id: "id-marker-far", countryCode: "QZ", city: "Farcityx", load: 0)
        let list = [failed, near, far]
        var budget = RelayRetryBudget()
        let retried = RelayRetry.plan(after: failed.id, in: list, origin: .automatic, budget: &budget)
        let spent = RelayRetry.plan(after: far.id, in: list, origin: .automatic, budget: &budget)
        let lines = [
            RelayRetry.logLine(for: RelayRetry.next(after: failed.id, in: list, origin: .automatic), alreadySpent: false),
            RelayRetry.logLine(for: RelayRetry.next(after: failed.id, in: list, origin: .user), alreadySpent: false),
            RelayRetry.logLine(for: RelayRetry.next(after: failed.id, in: [failed], origin: .user), alreadySpent: false),
            RelayRetry.logLine(for: retried, alreadySpent: false),
            RelayRetry.logLine(for: spent, alreadySpent: true),
        ]
        XCTAssertEqual(lines, [
            "[RelayRetry] no handshake: automatic retry 1 of 1 (other-country)",
            "[RelayRetry] no handshake: automatic retry 1 of 1 (same-country)",
            "[RelayRetry] no handshake: no relay qualifies for a retry (0 of 1)",
            "[RelayRetry] no handshake: automatic retry 1 of 1 (other-country)",
            "[RelayRetry] no handshake: the automatic retry is spent (1 of 1)",
        ])
        for line in lines {
            for node in list {
                for field in [node.id, node.name, node.city, node.countryCode] {
                    XCTAssertFalse(line.lowercased().contains(field.lowercased()), "\(line) leaks \(field)")
                }
            }
        }
    }

    func testTheProbeLogLinesAreCountsOnly() {
        XCTAssertEqual(RelayProbeVerdict.networkBlocksVpn.logLine, "[RelayProbe] no handshake; relay TCP reachable=1")
        XCTAssertEqual(RelayProbeVerdict.serverUnreachable.logLine, "[RelayProbe] no handshake; relay TCP reachable=0")
    }

    // MARK: - The probe's verdict and the words the user reads

    func testTheProbeVerdictPicksTheMessage() {
        XCTAssertEqual(RelayProbeVerdict(tcpConnected: true), .networkBlocksVpn)
        XCTAssertEqual(RelayProbeVerdict(tcpConnected: false), .serverUnreachable)
        // Design §3.3, word for word: plain, no blame.
        XCTAssertEqual(RelayProbeVerdict.networkBlocksVpn.message,
                       "This network appears to block VPN connections. Try mobile data or another Wi-Fi.")
        XCTAssertEqual(RelayProbeVerdict.serverUnreachable.message, "This server isn't reachable right now.")
        XCTAssertEqual(RelayProbeVerdict.port, 8443)
        XCTAssertEqual(RelayProbeVerdict.timeout, .seconds(3))
    }

    func testHomeSaysWhatTheRecoveryIsDoing() {
        XCTAssertEqual(RelayRecovery.tryingAnotherServer.label, "Trying another server…")
        XCTAssertEqual(RelayRecovery.checkingNetwork.label, "Checking the connection…")
    }

    // MARK: - Where it is wired (source pins: these files cannot compile here)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/RelayRetryTests.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    /// Text from `start` up to the next `end`, or "" (failing) when absent.
    private func slice(_ text: String, from start: String, to end: String,
                       file: StaticString = #filePath, line: UInt = #line) -> String {
        guard let a = text.range(of: start),
              let b = text.range(of: end, range: a.upperBound..<text.endIndex) else {
            XCTFail("not found: \(start) … \(end)", file: file, line: line)
            return ""
        }
        return String(text[a.lowerBound..<b.upperBound])
    }

    /// The client's own picks are `.automatic`; everything else that sets the
    /// selection (a tap, a live switch, a revert) reads `.user`.
    func testOnlyTheClientsOwnPickIsAutomatic() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let declaration = slice(vm, from: "@Published var selectedServer: ServerInfo? {", to: "}")
        XCTAssertTrue(declaration.contains("didSet { selectionOrigin = .user"), "a new assignment must default to .user")
        // Exactly the two pre-select twins (loadServers + auto-connect-on-launch).
        let lines = vm.components(separatedBy: "\n")
        var marked = 0
        for (i, line) in lines.enumerated() where line.contains("selectionOrigin = .automatic") {
            marked += 1
            XCTAssertTrue(i > 0 && lines[i - 1].contains("selectedServer = QuickSelect.bestServer(in:"),
                          "line \(i + 1): .automatic must follow the client's own pick")
        }
        XCTAssertEqual(marked, 2)
        let tap = slice(vm, from: "func selectServer(_ server: ServerInfo) {", to: "\n    }\n")
        XCTAssertFalse(tap.contains(".automatic"))
    }

    /// The no-handshake verdict: the retry first, then the probe.
    func testTheNoHandshakeVerdictRetriesThenProbes() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let verdict = slice(vm, from: "case .tearDownFailOpen:", to: "\n    }\n")
        let retry = try XCTUnwrap(verdict.range(of: "if retryOnAnotherRelay() { return }"))
        let probe = try XCTUnwrap(verdict.range(of: "diagnoseUnansweredRelay(host: host)"))
        XCTAssertLessThan(retry.lowerBound, probe.lowerBound)
        XCTAssertTrue(verdict.contains("activeMultiHop == nil, let host = lastDialRelayHost"))
        let retryBody = slice(vm, from: "private func retryOnAnotherRelay() -> Bool {", to: "\n    }\n")
        XCTAssertTrue(retryBody.contains(
            "RelayRetry.plan(after: failedId, in: servers, origin: origin, budget: &relayRetryBudget)"))
        XCTAssertTrue(retryBody.contains("RelayRetry.next(after: failedId, in: self.servers, origin: origin)"))
        XCTAssertTrue(retryBody.contains("self.connect(userInitiated: false)"), "the retry must not refill its own budget")
        let finish = slice(vm, from: "private func finishUnansweredRelay(", to: "\n    }\n")
        XCTAssertTrue(finish.contains("await RelayProbe.verdict(host: host)"))
        XCTAssertTrue(finish.contains("error = verdict.message"))
        // A connect the user asks for is the only dial that refills the budget.
        let connect = slice(vm, from: "func connect(userInitiated: Bool = true) {", to: "Task {")
        let refill = slice(connect, from: "if userInitiated {", to: "}")
        XCTAssertTrue(refill.contains("relayRetryBudget.refill()"))
        XCTAssertEqual(vm.components(separatedBy: "relayRetryBudget.refill()").count - 1, 2,
                       "refilled by a user's connect and by a proven handshake, nowhere else")
    }

    /// No log line in the retry and probe path carries anything but a count:
    /// each is a constant from this bundle's pure file, with no arguments.
    func testTheRetryAndProbePathLogsNoIdentifier() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let path = slice(vm, from: "private func retryOnAnotherRelay() -> Bool {",
                         to: "private func legacyRebuild(")
        let logs = path.components(separatedBy: "\n").filter { $0.contains("debugLog(") }
        XCTAssertEqual(logs.count, 2, "one line for the retry decision, one for the probe")
        for line in logs {
            let call = line.trimmingCharacters(in: .whitespaces)
            XCTAssertTrue(call == "debugLog(RelayRetry.logLine(for: planned, alreadySpent: spentBefore))"
                            || call == "debugLog(verdict.logLine)",
                          "unexpected log call: \(call)")
        }
        // The address the probe dials is never interpolated anywhere on the path.
        XCTAssertFalse(path.contains("\\(host"))
        XCTAssertFalse(path.contains("%@"))
    }
}
