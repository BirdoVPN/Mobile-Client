import XCTest

/// MR-2493 (design §3.2 step 4): when a location the USER picked does not
/// answer and no relay in its city or country can be tried, the message
/// offers ONE tap, "Try <best other location>". Birdo never moves there on
/// its own; the tap is the user's own connect.
///
/// Owner rules, 2026-11-19: a server the client chose may move to another
/// country, with one retry, on its own; a location the user picked is never
/// silently moved to another country; never a held or plan-excluded relay
/// (the server list, online and accessible, fetched fresh, is the only
/// authority); logs are counts only.
final class RelayOfferTests: XCTestCase {

    private struct Node: RelayRetryCandidate {
        let id: String
        var name: String { id }
        var countryCode = "DE"
        var city = "Frankfurt"
        var load: Int32 = 10
        var isOnline = true
        var accessible = true
    }

    /// `birdo-infra:deploy/fleet-nodes.conf`, as RelayRetryTests has it: 10
    /// relays, 10 cities, 9 countries; only the United States has two.
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

    // MARK: - An automatic pick is never offered anything

    /// A server the client chose already moves on its own (one retry, any
    /// country), so there is nothing to offer it.
    func testAnAutomaticPickIsNeverOfferedAnything() {
        for node in fleet {
            XCTAssertNil(RelayRetry.offer(after: node.id, in: fleet, origin: .automatic), node.id)
        }
        // Not even when its own retry has nowhere to go.
        let nothingElse = [Node(id: "fra"),
                           Node(id: "ams", countryCode: "NL", city: "Amsterdam", isOnline: false),
                           Node(id: "lon", countryCode: "GB", city: "London", accessible: false)]
        XCTAssertNil(RelayRetry.next(after: "fra", in: nothingElse, origin: .automatic))
        XCTAssertNil(RelayRetry.offer(after: "fra", in: nothingElse, origin: .automatic))
    }

    // MARK: - A user's pick with somewhere to go in its country: the retry

    func testAUserPickWithASameCountryCandidateRetriesAndIsOfferedNothing() {
        var budget = RelayRetryBudget()
        let retry = RelayRetry.plan(after: "nyc", in: fleet, origin: .user, budget: &budget)
        XCTAssertEqual(retry?.server.id, "lax")
        XCTAssertEqual(retry?.reach, .sameCountry)
        XCTAssertNil(RelayRetry.offer(after: "nyc", in: fleet, origin: .user))
        // The retry did not answer either: New York is still listed, in the
        // same country, so there is still nothing abroad to offer.
        XCTAssertNil(RelayRetry.offer(after: "lax", in: fleet, origin: .user))
        // A relay in the same city counts the same way.
        let sameCity = [Node(id: "fra"), Node(id: "fra2", load: 90),
                        Node(id: "ams", countryCode: "NL", city: "Amsterdam", load: 0)]
        XCTAssertNotNil(RelayRetry.next(after: "fra", in: sameCity, origin: .user))
        XCTAssertNil(RelayRetry.offer(after: "fra", in: sameCity, origin: .user))
    }

    // MARK: - A user's pick with nowhere to go in its country: the offer

    func testAUserPickWithNoCandidateIsOfferedTheBestOtherLocation() {
        var budget = RelayRetryBudget()
        // Stockholm is the only relay in Sweden: no retry, so none is spent...
        XCTAssertNil(RelayRetry.plan(after: "sto", in: fleet, origin: .user, budget: &budget))
        XCTAssertFalse(budget.spent)
        // ...and the message offers the least loaded relay elsewhere.
        XCTAssertEqual(RelayRetry.offer(after: "sto", in: fleet, origin: .user)?.id, "fra")
        XCTAssertEqual(RelayRetry.offer(after: "fra", in: fleet, origin: .user)?.id, "nyc")
        // Every relay outside the United States is in that position today.
        for node in fleet where node.countryCode != "US" {
            let offered = RelayRetry.offer(after: node.id, in: fleet, origin: .user)
            XCTAssertNotNil(offered, node.id)
            XCTAssertNotEqual(offered?.id, node.id, "never the relay that did not answer")
        }
    }

    func testTheOfferStandsBesideEveryMessageButTheBlockedNetworksOne() {
        // The probe could not run (the generic message), or the relay is not
        // reachable: another location is the way out.
        XCTAssertTrue(RelayRetry.offerFits(nil))
        XCTAssertTrue(RelayRetry.offerFits(.serverUnreachable))
        // The relay answers TCP, so this network drops the VPN's traffic, and
        // another location goes through the same network (design §3.3).
        XCTAssertFalse(RelayRetry.offerFits(.networkBlocksVpn))
    }

    func testTheOfferReadsPlainlyAndWholeToVoiceOver() {
        let offer = RelayOffer(serverId: "fra", name: "Frankfurt", city: "Frankfurt", country: "Germany",
                               message: RelayProbeVerdict.serverUnreachable.message)
        XCTAssertEqual(offer.serverId, "fra")
        XCTAssertEqual(offer.title, "Try Frankfurt")
        XCTAssertEqual(offer.accessibilityLabel, "Try Frankfurt, Germany instead")
        // A name missing from the list: the city, then the country.
        XCTAssertEqual(RelayOffer(serverId: "x", name: " ", city: "Stockholm", country: "Sweden",
                                  message: "m").title, "Try Stockholm")
        XCTAssertEqual(RelayOffer(serverId: "x", name: "", city: "", country: "Sweden",
                                  message: "m").accessibilityLabel, "Try Sweden instead")
    }

    func testTheOfferIsShownOnlyBesideItsOwnMessage() {
        let message = RelayProbeVerdict.serverUnreachable.message
        let offer = RelayOffer(serverId: "fra", name: "Frankfurt", city: "Frankfurt", country: "Germany",
                               message: message)
        XCTAssertTrue(offer.isShown(beside: message))
        XCTAssertFalse(offer.isShown(beside: nil))
        XCTAssertFalse(offer.isShown(beside: "Sign in to connect."))
    }

    // MARK: - Never a held or ineligible relay

    /// A node held for a jurisdiction mismatch is listed `isOnline: false`; a
    /// node above the plan is `accessible: false`. Neither is ever offered,
    /// and the list decides again at the tap.
    func testAnOfferNeverNamesAHeldOrIneligibleRelay() {
        let list = [Node(id: "sto", countryCode: "SE", city: "Stockholm"),
                    Node(id: "ams-held", countryCode: "NL", city: "Amsterdam", load: 0, isOnline: false),
                    Node(id: "lon-paid", countryCode: "GB", city: "London", load: 0, accessible: false),
                    Node(id: "fra", countryCode: "DE", city: "Frankfurt", load: 60)]
        let offered = RelayRetry.offer(after: "sto", in: list, origin: .user)
        XCTAssertEqual(offered?.id, "fra", "the least loaded relay that is online AND open to this plan")
        let offer = offered.map {
            RelayOffer(serverId: $0.id, name: $0.name, city: $0.city, country: $0.countryCode, message: "m")
        }
        for word in ["ams-held", "Amsterdam", "lon-paid", "London"] {
            XCTAssertFalse(offer?.title.contains(word) ?? true, word)
            XCTAssertFalse(offer?.accessibilityLabel.contains(word) ?? true, word)
        }
        // Nothing eligible: no offer at all.
        XCTAssertNil(RelayRetry.offer(after: "sto", in: Array(list.dropLast()), origin: .user))
        // At the tap: only a relay still listed online and open to this plan.
        XCTAssertEqual(RelayRetry.offered("fra", in: list)?.id, "fra")
        for id in ["ams-held", "lon-paid", "zzz"] {
            XCTAssertNil(RelayRetry.offered(id, in: list), id)
        }
        let heldSince = [Node(id: "sto", countryCode: "SE", city: "Stockholm"),
                         Node(id: "fra", countryCode: "DE", city: "Frankfurt", load: 60, isOnline: false)]
        XCTAssertNil(RelayRetry.offered("fra", in: heldSince))
    }

    // MARK: - Review L1 of #645: no probe into a tunnel still going down

    /// `awaitTeardown` gives up after about 5 s whether or not the tunnel went
    /// down. A tunnel still up then would carry the probe into itself, and a
    /// relay that may be fine would read "isn't reachable".
    func testNoProbeRunsWhileTheTunnelIsStillUp() {
        let host = "198.51.100.7" // TEST-NET-2, a documentation address
        XCTAssertNil(RelayProbeVerdict.probeHost(host, tunnelStillUp: true))
        XCTAssertEqual(RelayProbeVerdict.probeHost(host, tunnelStillUp: false), host)
        XCTAssertNil(RelayProbeVerdict.probeHost(nil, tunnelStillUp: false))
        XCTAssertNil(RelayProbeVerdict.probeHost("", tunnelStillUp: false))
        // Without a probe the message claims no verdict.
        XCTAssertEqual(RelayProbeVerdict.unprobedMessage,
                       "Couldn't establish a secure tunnel to this server. Try another location.")
    }

    // MARK: - Where it is wired (source pins: these files cannot compile here)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/RelayOfferTests.swift -> .../iosApp
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

    /// Both ends of the path ask, after the wait and before the session state
    /// is reset (the reset forces `isConnected` false and would hide the answer).
    func testBothProbePathsCheckTheTunnelAfterTheWait() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let diagnose = slice(vm, from: "private func diagnoseUnansweredRelay(host: String) {", to: "\n    }\n")
        let wait = try XCTUnwrap(diagnose.range(of: "await self?.awaitTeardown()"))
        let ask = try XCTUnwrap(diagnose.range(of: "RelayProbeVerdict.probeHost(host, tunnelStillUp: self.isConnected)"))
        let reset = try XCTUnwrap(diagnose.range(of: "self.resetSessionState()"))
        XCTAssertLessThan(wait.lowerBound, ask.lowerBound)
        XCTAssertLessThan(ask.lowerBound, reset.lowerBound)

        let retry = slice(vm, from: "private func retryOnAnotherRelay() -> Bool {", to: "\n    }\n")
        let retryWait = try XCTUnwrap(retry.range(of: "await self?.awaitTeardown()"))
        let retryAsk = try XCTUnwrap(retry.range(of: "let tunnelStillUp = self.isConnected"))
        let retryReset = try XCTUnwrap(retry.range(of: "self.resetSessionState()"))
        XCTAssertLessThan(retryWait.lowerBound, retryAsk.lowerBound)
        XCTAssertLessThan(retryAsk.lowerBound, retryReset.lowerBound)
        XCTAssertTrue(retry.contains("RelayProbeVerdict.probeHost(host, tunnelStillUp: tunnelStillUp)"))

        // No address to probe: the generic message, never a verdict.
        let finish = slice(vm, from: "private func finishUnansweredRelay(", to: "\n    }\n")
        let noProbe = slice(finish, from: "guard let host else {", to: "\n        }\n")
        XCTAssertTrue(noProbe.contains("error = RelayProbeVerdict.unprobedMessage"))
        XCTAssertFalse(noProbe.contains("RelayProbe.verdict"))
    }

    /// The tap is a connect of the user's own: the offered relay, looked up
    /// again in the list, becomes the selection (which reads `.user`), and
    /// `connect(userInitiated: true)` refills the one retry.
    func testATapDialsTheOfferedRelayAsTheUsersOwnConnect() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let tap = slice(vm, from: "func acceptRelayOffer() {", to: "\n    }\n")
        let lookUp = try XCTUnwrap(tap.range(of:
            "guard let server = RelayRetry.offered(offer.serverId, in: servers) else { return }"))
        let select = try XCTUnwrap(tap.range(of: "selectServer(server)"))
        let dial = try XCTUnwrap(tap.range(of: "connect(userInitiated: true)"))
        XCTAssertLessThan(lookUp.lowerBound, select.lowerBound)
        XCTAssertLessThan(select.lowerBound, dial.lowerBound)
        for never in [".automatic", "selectionOrigin", "userInitiated: false", "relayRetryBudget"] {
            XCTAssertFalse(tap.contains(never), "the tap must be a plain connect of the user's: \(never)")
        }
        // What makes it the user's: the selection's didSet, and the refill
        // in connect() for a connect the user asked for.
        let declaration = slice(vm, from: "@Published var selectedServer: ServerInfo? {", to: "}")
        XCTAssertTrue(declaration.contains("didSet { selectionOrigin = .user"))
        let connect = slice(vm, from: "func connect(userInitiated: Bool = true) {", to: "Task {")
        XCTAssertTrue(slice(connect, from: "if userInitiated {", to: "}").contains("relayRetryBudget.refill()"))
        // Home's button is the only caller, and only beside its own message.
        XCTAssertEqual(vm.components(separatedBy: "acceptRelayOffer()").count - 1, 1,
                       "declared once, never called by the view model itself")
        let home = try source("iosApp/Views/HomeView.swift")
        XCTAssertEqual(home.components(separatedBy: "vpnVM.acceptRelayOffer()").count - 1, 1)
        XCTAssertTrue(home.contains("if let offer = vpnVM.relayOffer, offer.isShown(beside: message) {"))
    }

    /// The offer is chosen by `RelayRetry.offer` from a FRESH list only, at
    /// both ends of a dial that never answered, and set nowhere else.
    func testTheOfferComesOnlyFromTheRuleAndAFreshList() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let lines = vm.components(separatedBy: "\n")
        let choosing = lines.filter { $0.contains("RelayRetry.offer(") }
        XCTAssertEqual(choosing.count, 2, "the retry's end and the probe's")
        for line in choosing {
            XCTAssertTrue(line.contains("RelayRetry.offer(after: failedId, in: fresh, origin: origin)"), line)
        }
        let finish = slice(vm, from: "private func finishUnansweredRelay(", to: "\n    }\n")
        let setting = lines.filter { $0.contains("relayOffer = ") && !$0.contains("relayOffer = nil") }
        XCTAssertFalse(setting.isEmpty)
        for line in setting {
            XCTAssertTrue(finish.contains(line), "relayOffer set outside finishUnansweredRelay: \(line)")
        }
        // Never beside the blocked-network message.
        XCTAssertTrue(finish.contains("RelayRetry.offerFits(verdict)"))
    }
}
