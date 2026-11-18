import XCTest

/// MR-1620 / MR-1621 -- the connection state the user sees and hears.
/// MR-2371 -- "Reconnecting…" for a real drop the system re-dials, Disconnect
/// while reconnecting, and "Connection failed" only for a connection that
/// failed. A path loss is not shown through NEVPNStatus (review of #634).
final class ConnectionPhaseTests: XCTestCase {

    private func phase(_ tunnel: TunnelPhase,
                       switching: Bool = false,
                       reconnecting: Bool = false,
                       awaitingRedial: Bool = false,
                       error: Bool = false) -> ConnectionPhase {
        ConnectionPhase.resolve(tunnel: tunnel, isSwitching: switching,
                                isReconnecting: reconnecting, awaitingRedial: awaitingRedial,
                                hasError: error)
    }

    func testPlainStatesMapOneToOne() {
        XCTAssertEqual(phase(.disconnected), .disconnected)
        XCTAssertEqual(phase(.connecting), .connecting)
        XCTAssertEqual(phase(.protected), .protected)
    }

    func testDisconnectedWithAnErrorIsFailed() {
        XCTAssertEqual(phase(.disconnected, error: true), .failed)
        // An error left over from an earlier attempt must not outrank a live tunnel.
        XCTAssertEqual(phase(.protected, error: true), .protected)
    }

    func testReconnectingBeatsProtectedAndConnecting() {
        XCTAssertEqual(phase(.protected, reconnecting: true), .reconnecting)
        XCTAssertEqual(phase(.connecting, reconnecting: true), .reconnecting)
        XCTAssertEqual(ConnectionPhase.reconnectingLabel, "Reconnecting…")
    }

    func testReconnectingFlagIsIgnoredOnceTheTunnelIsGone() {
        XCTAssertEqual(phase(.disconnected, reconnecting: true), .disconnected)
    }

    /// MR-2371: a drop the system is dialling again is still "Reconnecting…",
    /// its banner error notwithstanding, never "Connection failed".
    func testADropAwaitingTheSystemsRedialIsReconnecting() {
        XCTAssertEqual(phase(.disconnected, reconnecting: true, awaitingRedial: true, error: true), .reconnecting)
        XCTAssertEqual(phase(.disconnected, reconnecting: false, awaitingRedial: true, error: true), .failed,
                       "awaiting without reconnecting is not a state the watch produces; the error stands")
        XCTAssertEqual(phase(.disconnected, switching: true, reconnecting: true, awaitingRedial: true), .connecting)
    }

    func testALiveSwitchIsNeverReconnecting() {
        XCTAssertEqual(phase(.protected, switching: true, reconnecting: true), .connecting)
    }

    func testAnnouncementsForEveryState() {
        let d = ConnectionPhase.disconnected
        XCTAssertEqual(ConnectionPhase.announcement(from: d, to: .connecting), "Connecting")
        XCTAssertEqual(ConnectionPhase.announcement(from: .connecting, to: .protected), "Protected")
        XCTAssertEqual(ConnectionPhase.announcement(from: .protected, to: .reconnecting), "Reconnecting")
        XCTAssertEqual(ConnectionPhase.announcement(from: .protected, to: .disconnected), "Disconnected")
        XCTAssertEqual(ConnectionPhase.announcement(from: .connecting, to: .failed), "Connection failed")
    }

    /// MR-2371: "Connection failed" only when a connection that was being
    /// made, or was up, failed. An error while idle ("Select a server first")
    /// speaks through its own banner, not as a failed connection.
    func testConnectionFailedIsSpokenOnlyForAConnectionThatFailed() {
        XCTAssertEqual(ConnectionPhase.announcement(from: .connecting, to: .failed), "Connection failed")
        XCTAssertEqual(ConnectionPhase.announcement(from: .reconnecting, to: .failed), "Connection failed")
        XCTAssertEqual(ConnectionPhase.announcement(from: .protected, to: .failed), "Connection failed")
        XCTAssertNil(ConnectionPhase.announcement(from: .disconnected, to: .failed))
        XCTAssertNil(ConnectionPhase.announcement(from: .failed, to: .failed))
    }

    func testNoAnnouncementWithoutAChange() {
        for p in [ConnectionPhase.disconnected, .connecting, .reconnecting, .protected, .failed] {
            XCTAssertNil(ConnectionPhase.announcement(from: p, to: p))
        }
    }

    // MARK: - The Home button (MR-2371)

    func testReconnectingOffersDisconnect() {
        func action(_ phase: ConnectionPhase, switching: Bool = false,
                    connected: Bool = false, connecting: Bool = false) -> ConnectAction {
            ConnectAction.resolve(phase: phase, isSwitching: switching,
                                  isConnected: connected, isConnecting: connecting)
        }
        // A reassert (still connected), the system's re-dial (connecting) and
        // the gap between (neither): Disconnect, usable, in every one.
        XCTAssertEqual(action(.reconnecting, connected: true, connecting: true), .disconnect)
        XCTAssertEqual(action(.reconnecting, connecting: true), .disconnect)
        XCTAssertEqual(action(.reconnecting), .disconnect)
        // Unchanged elsewhere.
        XCTAssertEqual(action(.protected, connected: true), .disconnect)
        XCTAssertEqual(action(.connecting, connected: true), .disconnect, "up but not yet proven")
        XCTAssertEqual(action(.connecting, connecting: true), .busy)
        XCTAssertEqual(action(.disconnected), .connect)
        XCTAssertEqual(action(.failed), .connect)
        // A live switch never offers Disconnect over a half-swapped tunnel.
        XCTAssertEqual(action(.connecting, switching: true, connected: true, connecting: true), .busy)
        XCTAssertEqual(action(.reconnecting, switching: true, connected: true), .busy)
    }

    // MARK: - ReconnectWatch (MR-2371)

    private struct Step {
        var watch = ReconnectWatch()
        mutating func see(_ status: ReconnectWatch.Status, connected: Bool, proven: Bool,
                          appDriven: Bool = false, redials: Bool = true) {
            watch.observe(status, wasConnected: connected, wasProven: proven,
                          appDriven: appDriven, redialsOnDrop: redials)
        }
        func phase(_ tunnel: TunnelPhase, error: Bool = false) -> ConnectionPhase {
            ConnectionPhase.resolve(tunnel: tunnel, isSwitching: false, isReconnecting: watch.isReconnecting,
                                    awaitingRedial: watch.awaitingRedial, hasError: error)
        }
    }

    /// A protected session drops (the extension's liveness check cancelled
    /// it), the on-demand rule dials it again, and it proves a handshake:
    /// "Reconnecting…" the whole way, spoken once, then "Protected".
    func testARealDropTheSystemRedialsReadsReconnectingUntilItIsBack() {
        var s = Step()
        var phases: [ConnectionPhase] = [.protected]

        s.see(.disconnecting, connected: true, proven: true)
        XCTAssertTrue(s.watch.isReconnecting)
        XCTAssertTrue(s.watch.awaitingRedial)
        phases.append(s.phase(.protected))

        s.see(.disconnected, connected: true, proven: true)
        phases.append(s.phase(.disconnected, error: true))   // "Tunnel failed: …" on the banner

        s.see(.connecting, connected: false, proven: false)
        XCTAssertTrue(s.watch.isReconnecting)
        XCTAssertFalse(s.watch.awaitingRedial)
        phases.append(s.phase(.connecting, error: true))

        s.see(.connected, connected: false, proven: false)
        XCTAssertTrue(s.watch.isReconnecting, "up, but no handshake yet on the new session")
        phases.append(s.phase(.connecting, error: true))

        s.watch.proven()
        XCTAssertFalse(s.watch.isReconnecting)
        phases.append(s.phase(.protected, error: true))

        XCTAssertEqual(phases, [.protected, .reconnecting, .reconnecting, .reconnecting, .reconnecting, .protected])
        let spoken = zip(phases, phases.dropFirst()).compactMap { ConnectionPhase.announcement(from: $0, to: $1) }
        XCTAssertEqual(spoken, ["Reconnecting", "Protected"])
    }

    /// A drop nothing will dial again (no kill switch) is a failed connection,
    /// as before.
    func testADropNothingRedialsIsAFailedConnection() {
        var s = Step()
        s.see(.disconnected, connected: true, proven: true, redials: false)
        XCTAssertFalse(s.watch.isReconnecting)
        XCTAssertFalse(s.watch.awaitingRedial)
        let now = s.phase(.disconnected, error: true)
        XCTAssertEqual(now, .failed)
        XCTAssertEqual(ConnectionPhase.announcement(from: .protected, to: now), "Connection failed")
    }

    /// What the app drives itself, or a session that never proved a
    /// handshake, is not brought back by the system.
    func testTheAppsOwnTeardownAndAnUnprovenDropAreNotReconnecting() {
        var own = Step()
        own.see(.disconnected, connected: true, proven: true, appDriven: true)
        XCTAssertFalse(own.watch.isReconnecting)

        var unproven = Step()
        unproven.see(.disconnected, connected: true, proven: false)
        XCTAssertFalse(unproven.watch.isReconnecting)
    }

    /// A re-dialled session that drops again before its handshake is still
    /// the same recovery.
    func testASecondDropDuringTheRecoveryStaysReconnecting() {
        var s = Step()
        s.see(.disconnected, connected: true, proven: true)
        s.see(.connecting, connected: false, proven: false)
        s.see(.connected, connected: false, proven: false)
        s.see(.disconnected, connected: true, proven: false)
        XCTAssertTrue(s.watch.isReconnecting)
        XCTAssertTrue(s.watch.awaitingRedial)
        XCTAssertEqual(s.phase(.disconnected, error: true), .reconnecting)
    }

    /// A reassert (a live re-key) reads "Reconnecting…" and ends at its
    /// `.connected`, as before (MR-1621). A path loss does not reach here at
    /// all: NEVPNStatus stays `.connected` while the extension waits for a
    /// network (review of #634; "paused, no network" is a follow-up through
    /// the stats reply or an app-group flag, never NEVPNStatus).
    func testAReassertEndsAtItsConnected() {
        var s = Step()
        s.see(.reasserting, connected: true, proven: true)
        XCTAssertEqual(s.phase(.protected), .reconnecting)
        s.see(.connected, connected: true, proven: true)
        XCTAssertFalse(s.watch.isReconnecting)
        XCTAssertEqual(s.phase(.connecting), .connecting)
    }

    /// A live switch or a settings rebuild raises `.reasserting` too; once it
    /// lands it reads as before, not as a recovery.
    func testAnAppDrivenReassertEndsAtConnected() {
        var s = Step()
        s.see(.reasserting, connected: true, proven: true, appDriven: true)
        s.see(.connected, connected: true, proven: true, appDriven: true)
        XCTAssertFalse(s.watch.isReconnecting)
    }

    /// The system's configuration read fresh: a drop it will not dial again
    /// (VPN or On Demand turned off in iOS Settings) stops reading
    /// "Reconnecting…"; a reassert is the extension's own and stays.
    func testADisarmedSystemEndsTheWaitButNotAReassert() {
        var drop = Step()
        drop.see(.disconnected, connected: true, proven: true)
        drop.watch.systemChanged(redialsOnDrop: true)
        XCTAssertTrue(drop.watch.isReconnecting)
        drop.watch.systemChanged(redialsOnDrop: false)
        XCTAssertFalse(drop.watch.isReconnecting)
        XCTAssertFalse(drop.watch.awaitingRedial)

        var reassert = Step()
        reassert.see(.reasserting, connected: true, proven: true)
        reassert.watch.systemChanged(redialsOnDrop: false)
        XCTAssertTrue(reassert.watch.isReconnecting)
    }

    /// The app's own dial or disconnect (and the breaker's fail-open, and
    /// sign-out) take over.
    func testStopClearsEverything() {
        var s = Step()
        s.see(.disconnected, connected: true, proven: true)
        s.watch.stop()
        XCTAssertEqual(s.watch, ReconnectWatch())
        s.see(.connecting, connected: false, proven: false)
        XCTAssertFalse(s.watch.isReconnecting, "the app's own connect is a first connect")
    }

    func testOnlyAnArmedOnDemandConnectRuleRedialsADrop() {
        let armed = KillSwitchSystemState(configurationEnabled: true, onDemandEnabled: true,
                                          hasConnectRule: true, includeAllNetworks: false)
        XCTAssertTrue(armed.redialsOnDrop, "includeAllNetworks holds traffic; the rule is what re-dials")
        var off = armed
        off.onDemandEnabled = false
        XCTAssertFalse(off.redialsOnDrop)
        var noRule = armed
        noRule.hasConnectRule = false
        XCTAssertFalse(noRule.redialsOnDrop)
        var disabled = armed
        disabled.configurationEnabled = false
        XCTAssertFalse(disabled.redialsOnDrop, "the VPN turned off in iOS Settings")
    }

    // MARK: - Where it is wired (source pins: these files cannot compile here)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/ConnectionPhaseTests.swift -> .../iosApp
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

    /// Review of #634: the vendored adapter is upstream's again. Its pause
    /// for no network path does not change NEVPNStatus; a `.reasserting ->
    /// .connected` on every path return reached the fresh-dial fail-open.
    func testThePathLossPauseLeavesTheTunnelStatusAlone() throws {
        let adapter = try source("Vendor/wireguard-apple/Sources/WireGuardKit/WireGuardAdapter.swift")
        let pause = slice(adapter, from: "Connectivity offline, pausing backend.", to: "Failed to restart backend")
        XCTAssertFalse(pause.contains("reasserting"), "a path loss must not drive NEVPNStatus (see the arm guard)")
    }

    /// Review of #634: whatever drives a `.connected`, a session whose
    /// on-demand rule is already armed never reaches the fresh-dial
    /// fail-open (`HandshakeArm.ruleAlreadyArmed`, LiveRebuildTests).
    func testTheConnectedArmKnowsWhetherTheRuleIsAlreadyArmed() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let connected = slice(vm, from: "case .connected:", to: "case .reasserting:")
        XCTAssertTrue(connected.contains("HandshakeArm.ruleAlreadyArmed(peerWasRestoredInPlace: peerWasRestoredInPlace,"))
        XCTAssertTrue(connected.contains("onDemandRuleArmed: redialsOnDrop)"))
        XCTAssertTrue(connected.contains("armKillSwitchAfterHandshake(ruleAlreadyArmed: ruleAlreadyArmed,"))
        let arm = slice(vm, from: "private func armKillSwitchAfterHandshake(", to: "case .inconclusive:")
        XCTAssertTrue(arm.contains("ruleAlreadyArmed: ruleAlreadyArmed,"), "the verdict ignores the armed rule")
    }

    func testTheViewModelAndHomeAreWiredToTheWatch() throws {
        let vm = try source("iosApp/ViewModels/VpnViewModel.swift")
        let handler = slice(vm, from: "private func handleStatusChange(_ status: NEVPNStatus) {", to: "checkCircuitBreaker()\n    }")
        for fed in [".connected", ".reasserting", ".connecting", ".disconnected", ".disconnecting"] {
            XCTAssertTrue(handler.contains("watch(\(fed))"), "handleStatusChange does not feed \(fed)")
        }
        XCTAssertTrue(handler.contains("redialsOnDrop = killSwitchSystem?.redialsOnDrop ?? false"))
        XCTAssertFalse(handler.contains("isReconnecting = "), "only the watch sets isReconnecting")
        let reset = slice(vm, from: "private func resetSessionState() {", to: "\n    }\n")
        XCTAssertTrue(reset.contains("updateReconnect { $0.stop() }"))
        let proof = slice(vm, from: "private func noteHandshake(", to: "\n    }\n")
        XCTAssertTrue(proof.contains("updateReconnect { $0.proven() }"))
        XCTAssertTrue(vm.contains("$0.systemChanged(redialsOnDrop: state?.redialsOnDrop ?? false)"))
        XCTAssertTrue(vm.contains("awaitingRedial: isAwaitingRedial,"))

        let home = try source("iosApp/Views/HomeView.swift")
        XCTAssertTrue(home.contains("ConnectAction.resolve(phase: vpnVM.connectionPhase,"))
        let tap = slice(home, from: "private func handleConnectTap() {", to: "\n    }\n")
        XCTAssertTrue(tap.contains("switch ctaAction {"), "the tap no longer follows ConnectAction")
        XCTAssertTrue(tap.contains("vpnVM.disconnect()"))
        XCTAssertTrue(home.contains("case .connected, .reconnecting: return \"Disconnect\""))
    }
}
