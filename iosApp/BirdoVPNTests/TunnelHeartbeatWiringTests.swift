import XCTest

/// MR-2524: where the tunnel extension's own heartbeat takes the HOST's
/// decision, and where the host carries out what the extension handed over.
///
/// Source pins: PacketTunnelProvider (NetworkExtension, WireGuardKit),
/// VpnViewModel and VPNManager cannot be compiled into this bundle. The
/// decision itself runs in TunnelHeartbeatPolicyTests.
///
/// Before MR-2524 the extension tore the tunnel down on any `valid: false`, so
/// whenever it heard a reply first (always while the app is suspended) the
/// host's policy (HeartbeatPolicy, MR-2343) was skipped: a reaped key was never
/// rebuilt, a drained server never left, and every ending read as the circuit
/// breaker's generic sentence.
final class TunnelHeartbeatWiringTests: XCTestCase {

    // MARK: - Helpers

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/TunnelHeartbeatWiringTests.swift -> .../iosApp
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

    // MARK: - The extension takes the host's decision, not a copy of it

    /// HeartbeatWire.swift is compiled into the extension as it is, with the
    /// Foundation-only files it reads; nothing in the extension's own folder
    /// declares a second policy or a second reply model.
    func testTheExtensionCompilesTheHostsPolicy() throws {
        let yml = try source("project.yml")
        let tunnel = slice(yml, from: "\n  PacketTunnel:", to: "\n    settings:")
        for file in ["HeartbeatWire.swift", "TunnelHeartbeatPolicy.swift", "RelayRetry.swift", "QuickSelect.swift",
                     "ConnectionPhase.swift", "TunnelProof.swift", "TunnelBreakerStore.swift"] {
            XCTAssertTrue(tunnel.contains("- path: iosApp/Services/\(file)\n"), "the extension does not compile \(file)")
        }
        let tests = slice(yml, from: "\n  BirdoVPNTests:", to: "\n  BirdoVPNUITests:")
        XCTAssertTrue(tests.contains("- path: iosApp/Services/TunnelHeartbeatPolicy.swift\n"),
                      "TunnelHeartbeatPolicyTests would not run the real decision")

        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let folder = root.appendingPathComponent("PacketTunnel")
        let swiftFiles = try FileManager.default.contentsOfDirectory(atPath: folder.path).filter { $0.hasSuffix(".swift") }
        XCTAssertTrue(swiftFiles.contains("PacketTunnelProvider.swift"), "the listing is broken")
        for name in swiftFiles {
            let text = try source("PacketTunnel/\(name)")
            for copy in ["enum HeartbeatPolicy", "struct HeartbeatResult", "enum HeartbeatReason", "enum HeartbeatOutcome"] {
                XCTAssertFalse(text.contains(copy), "PacketTunnel/\(name) declares its own \(copy)")
            }
        }
    }

    /// The beat decodes every reply with the host's model and decoder and
    /// hands it to the policy; it no longer reads `valid` alone or tears down
    /// by itself. A reply that lands after its beat was cancelled (the peer
    /// was swapped) is about a key the tunnel no longer rides and is dropped.
    func testTheExtensionBeatDecidesWithTheHostsPolicy() throws {
        let provider = try source("PacketTunnel/PacketTunnelProvider.swift")
        let beat = slice(provider, from: "private func sendHeartbeat(", to: "\n    }\n")
        XCTAssertTrue(beat.contains("JSONDecoder().decode(HeartbeatResult.self, from: data)"),
                      "the beat does not read the reply as the host does")
        XCTAssertTrue(beat.contains("actOnHeartbeat(reply, keyId: keyId)"))
        XCTAssertFalse(beat.contains("json[\"valid\"]"), "the beat acts on valid alone again")
        XCTAssertFalse(beat.contains("handleDataPlaneFailure("), "the beat tears down by itself again")

        let act = slice(provider, from: "private func actOnHeartbeat(", to: "\n    }\n")
        XCTAssertTrue(act.contains("guard !Task.isCancelled else { return }"),
                      "a reply about a swapped-out key is acted on")
        XCTAssertTrue(act.contains("heartbeatLock.lock()"))
        XCTAssertTrue(act.contains("_heartbeatState.decide(reply, keyId: keyId, multiHop: _heartbeatMultiHop,"),
                      "the extension decides by itself again")
    }

    // MARK: - No teardown where the policy redials

    /// A rebuild (reaped) or a move (a drained or deleted server) is the
    /// host's: the extension hands the verdict over and leaves the tunnel
    /// exactly as it is. With the kill switch on, that is what keeps a move
    /// from opening a gap on the extension's side: nothing is cancelled,
    /// nothing re-dialled, the on-demand rule untouched (the extension cannot
    /// touch it anyway), and the host moves through its own path (#662: in
    /// place first).
    func testNoTeardownWhereThePolicyRedials() throws {
        let provider = try source("PacketTunnel/PacketTunnelProvider.swift")
        let act = slice(provider, from: "private func actOnHeartbeat(", to: "\n    }\n")
        let leave = slice(act, from: "case .leaveToHost(let verdict):", to: "case .stop(let verdict):")
        XCTAssertTrue(leave.contains("TunnelBreakerStore.shared.setHeartbeatHandOff("), "the host is never told")
        for teardown in ["cancelTunnelWithError", "handleDataPlaneFailure", "stopHeartbeat", "adapter.stop",
                         "stopTunnel", "recordFailure"] {
            XCTAssertFalse(leave.contains(teardown), "a rebuild or a move tears the tunnel down again: \(teardown)")
        }
        let keep = slice(act, from: "case .keep:", to: "case .leaveToHost(let verdict):")
        XCTAssertTrue(keep.contains("return"))
        XCTAssertFalse(keep.contains("handleDataPlaneFailure") || keep.contains("cancelTunnelWithError"))
    }

    /// An ending still stops as before, through the breaker's `revoked` budget
    /// (so it can never become a re-dial loop), after the hand-off that tells
    /// the host which ending it was. That is the only place a heartbeat reply
    /// charges the breaker.
    func testAnEndingStopsThroughTheBreakerAfterSayingWhich() throws {
        let provider = try source("PacketTunnel/PacketTunnelProvider.swift")
        let act = slice(provider, from: "private func actOnHeartbeat(", to: "\n    }\n")
        let stop = slice(act, from: "case .stop(let verdict):", to: "\n    }\n")
        guard let said = stop.range(of: "TunnelBreakerStore.shared.setHeartbeatHandOff("),
              let stopped = stop.range(of: "handleDataPlaneFailure(.revoked, error: TunnelError.connectionRevoked)") else {
            return XCTFail("an ending no longer hands over and stops through the breaker")
        }
        XCTAssertLessThan(said.lowerBound, stopped.lowerBound,
                          "the host must find the ending by the time it acts on the trip")
        XCTAssertEqual(provider.components(separatedBy: "handleDataPlaneFailure(.revoked").count - 1, 1,
                       "a heartbeat reply charges the breaker somewhere else too")
    }

    // MARK: - The profile says Multi-Hop

    /// A drained server ends a Multi-Hop session where it moves a single hop,
    /// so the extension must know which one it carries: the host writes it on
    /// every connect and swap (the reconfigure message copies the swap's
    /// dictionary), and the extension reads it at start and on a reconfigure.
    func testTheProfileSaysWhetherTheSessionIsMultiHop() throws {
        let manager = try source("iosApp/Services/VPNManager.swift")
        let flag = "[TunnelHeartbeatPolicy.multiHopKey] = config.multiHop != nil ? \"1\" : \"0\""
        let connect = slice(manager, from: "func connect(config: VPNConnectionConfig) async throws {", to: "\n    }\n")
        XCTAssertTrue(connect.contains("providerConfig" + flag), "connect() does not say whether it is a pair")
        let swap = slice(manager, from: "func swapConfig(config: VPNConnectionConfig) async throws {", to: "\n    }\n")
        XCTAssertTrue(swap.contains("providerConfiguration" + flag), "swapConfig() does not say whether it is a pair")

        let provider = try source("PacketTunnel/PacketTunnelProvider.swift")
        let start = slice(provider, from: "override func startTunnel(", to: "\n    }\n")
        XCTAssertTrue(start.contains("heartbeatMultiHop = TunnelHeartbeatPolicy.multiHop("))
        XCTAssertTrue(start.contains("proto.providerConfiguration?[TunnelHeartbeatPolicy.multiHopKey]"))
        let reconfigure = slice(provider, from: "private func reconfigure(", to: "\n    }\n")
        XCTAssertTrue(reconfigure.contains("TunnelHeartbeatPolicy.multiHop(message[TunnelHeartbeatPolicy.multiHopKey])"))
        XCTAssertTrue(reconfigure.contains("self.heartbeatMultiHop = multiHop"))
    }

    // MARK: - The host carries out what was handed over

    /// Where the host's own beat could not hear the reply (the app suspended,
    /// or a node that dropped the peer carries nothing for it), the host still
    /// carries out the decision when it next runs: first thing in each of the
    /// four places `checkCircuitBreaker` runs, under `sendHeartbeat`'s gate,
    /// through its rebuild budget, into its paths, and nothing of its own.
    func testTheHostCarriesOutTheHandOffAsItsOwnBeatWould() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let check = slice(vm, from: "func checkCircuitBreaker() {", to: "\n    }\n")
        XCTAssertTrue(check.hasPrefix("func checkCircuitBreaker() {\n        actOnTunnelHeartbeat()\n"),
                      "the hand-off is not acted on first")

        let act = slice(vm, from: "private func actOnTunnelHeartbeat() {", to: "\n    }\n")
        XCTAssertTrue(act.contains("guard let keyId = activeKeyId, handOff.keyId == keyId else {"),
                      "a verdict about another session could act")
        XCTAssertTrue(act.contains("store.clearHeartbeatHandOff(handOff)"))
        XCTAssertTrue(act.contains("guard isConnected, !isRebuildingLive, !isTearingDownForRedial else { return }"),
                      "not sendHeartbeat's gate")
        XCTAssertTrue(act.contains("heartbeatRebuildBudget.admit("), "a reap is rebuilt without the budget")
        XCTAssertTrue(act.contains("handOff.verdict.hostOutcome(multiHop: activeMultiHop != nil)"))
        let beat = slice(vm, from: "private func sendHeartbeat() async {", to: "\n    }\n")
        for path in ["rebuildAfterReap()", "moveOffDrainedServer()", "endSessionForServer(ending)"] {
            XCTAssertTrue(act.contains(path), "the hand-off does not reach \(path)")
            XCTAssertTrue(beat.contains(path), "the host's own beat no longer reaches \(path)")
        }
        for own in ["vpnManager.disconnect()", "failOpenAndStop()", "connect(userInitiated:", "releaseServerSlot()",
                    "connectMultiHop("] {
            XCTAssertFalse(act.contains(own), "the hand-off has a teardown or a dial of its own: \(own)")
        }
        // The log: the verdict, what was done and how long it waited (counts
        // only, for the device check), never a key or a server.
        let log = slice(act, from: "debugLog(", to: ")\n")
        XCTAssertTrue(log.contains("handOff.verdict.rawValue"))
        XCTAssertFalse(log.contains("keyId") || log.contains("server") || log.contains("Server"),
                       "the log names a key or a server")
    }

    /// A trip that is the extension stopping an ENDED session ends it as the
    /// host's own beat would (`endSessionForServer`): the key is already gone,
    /// so it is dropped rather than DELETEd; the trip has done its job; and the
    /// user reads that ending's sentence (and gets the plans for the allowance)
    /// instead of the trip's generic one. Only after the fail-open stopped it.
    func testATripForAnEndingSaysTheEnding() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let check = slice(vm, from: "func checkCircuitBreaker() {", to: "\n    }\n")
        let afterStop = slice(check, from: "guard vpnManager.failOpenAndStop() else { return }", to: "releaseServerSlot()")
        let ending = slice(afterStop, from: "if let ending = takeTunnelHeartbeatEnding() {", to: "return\n        }")
        for step in ["setActiveKeyId(nil)", "resetSessionState()", "clearCircuitBreaker()",
                     "heartbeatEnding = ending", "error = ending.message"] {
            XCTAssertTrue(ending.contains(step), "the ending is not said as the host says it: \(step)")
        }
        XCTAssertFalse(ending.contains("releaseServerSlot()"), "the key is already gone: nothing to DELETE")

        let take = slice(vm, from: "private func takeTunnelHeartbeatEnding() -> HeartbeatEnding? {", to: "\n    }\n")
        XCTAssertTrue(take.contains("handOff.keyId == keyId"), "another session's ending could be said")
        XCTAssertTrue(take.contains("case .end(let ending) = handOff.verdict.hostOutcome(multiHop: activeMultiHop != nil)"))
        XCTAssertTrue(take.contains("store.clearHeartbeatHandOff(handOff)"))
    }
}
