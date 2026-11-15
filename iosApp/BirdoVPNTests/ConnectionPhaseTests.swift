import XCTest

/// MR-1620 / MR-1621 -- the connection state the user sees and hears.
final class ConnectionPhaseTests: XCTestCase {

    private func phase(_ tunnel: TunnelPhase,
                       switching: Bool = false,
                       reconnecting: Bool = false,
                       error: Bool = false) -> ConnectionPhase {
        ConnectionPhase.resolve(tunnel: tunnel, isSwitching: switching,
                                isReconnecting: reconnecting, hasError: error)
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

    func testNoAnnouncementWithoutAChange() {
        for p in [ConnectionPhase.disconnected, .connecting, .reconnecting, .protected, .failed] {
            XCTAssertNil(ConnectionPhase.announcement(from: p, to: p))
        }
    }
}
