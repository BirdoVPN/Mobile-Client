import XCTest

/// MR-974 — pins `TunnelPhase.resolve` and `HandshakeLatch`: iOS says
/// "Protected" only once a handshake is proven on THIS session, as Android
/// does (BirdoVpnService publishes Connected only after its transport probe
/// observes one).
///
/// Before: Home mapped `NEVPNStatus.connected` straight to "Protected", and the
/// extension reports `.connected` right after WireGuardKit accepts the config,
/// before any handshake (PacketTunnelProvider.startTunnel). A peer that never
/// answered read "Protected" for the whole 15 s handshake arm.
final class TunnelProofTests: XCTestCase {

    private func phase(connected: Bool, connecting: Bool, proven: Bool) -> TunnelPhase {
        TunnelPhase.resolve(isConnected: connected, isConnecting: connecting, handshakeProven: proven)
    }

    func testAStartedTunnelWithNoHandshakeIsConnectingNotProtected() {
        XCTAssertEqual(phase(connected: true, connecting: false, proven: false), .connecting)
    }

    func testAProvenHandshakeIsProtected() {
        XCTAssertEqual(phase(connected: true, connecting: false, proven: true), .protected)
    }

    func testDiallingIsConnectingAndNoSessionIsDisconnected() {
        XCTAssertEqual(phase(connected: false, connecting: true, proven: false), .connecting)
        XCTAssertEqual(phase(connected: false, connecting: false, proven: false), .disconnected)
        // A proof that outlived its session claims nothing.
        XCTAssertEqual(phase(connected: false, connecting: false, proven: true), .disconnected)
        XCTAssertEqual(phase(connected: false, connecting: true, proven: true), .connecting)
    }

    /// `.reasserting` raises `isConnecting` with `isConnected` still set: the
    /// same session, so its proof (or its lack of one) carries over.
    func testAReassertKeepsTheSessionsProof() {
        XCTAssertEqual(phase(connected: true, connecting: true, proven: true), .protected)
        XCTAssertEqual(phase(connected: true, connecting: true, proven: false), .connecting)
    }

    /// The acceptance case: a dead peer never shows "Protected" within the
    /// handshake arm's 15 s, sampled at the arm's own cadence.
    func testADeadPeerIsNeverProtectedForTheWholeHandshakeWindow() {
        var latch = HandshakeLatch()
        latch.reset()                                   // `.connected` from down
        for _ in 0..<HandshakeArm.polls {
            let session = latch.session
            XCTAssertFalse(latch.observe(rx: 0, sampledIn: session))
            XCTAssertNotEqual(phase(connected: true, connecting: false, proven: latch.isProven), .protected)
        }
        XCTAssertEqual(HandshakeArm.windowMs, 15_000)
    }

    func testTheFirstInboundBytesProveTheSessionOnce() {
        var latch = HandshakeLatch()
        latch.reset()
        let session = latch.session
        XCTAssertFalse(latch.observe(rx: 0, sampledIn: session))
        XCTAssertTrue(latch.observe(rx: 92, sampledIn: session))     // a handshake response
        XCTAssertTrue(latch.isProven)
        XCTAssertEqual(phase(connected: true, connecting: false, proven: latch.isProven), .protected)
        // Reported once, so the view model publishes once.
        XCTAssertFalse(latch.observe(rx: 4_096, sampledIn: session))
        XCTAssertTrue(latch.isProven)
    }

    /// A live rebuild replaces the peer while a `stats` reply for the OLD one
    /// is in flight: those bytes must not prove the new peer.
    func testASampleFromThePreviousSessionCannotProveTheNextOne() {
        var latch = HandshakeLatch()
        latch.reset()
        let before = latch.session
        latch.reset()
        XCTAssertFalse(latch.observe(rx: 1_000_000, sampledIn: before))
        XCTAssertFalse(latch.isProven)
        let now = latch.session
        XCTAssertTrue(latch.observe(rx: 92, sampledIn: now))
    }

    func testEndingTheSessionDropsTheProof() {
        var latch = HandshakeLatch()
        latch.reset()
        let session = latch.session
        latch.observe(rx: 92, sampledIn: session)
        XCTAssertTrue(latch.isProven)
        latch.reset()                                   // `.disconnected`, or a new peer
        XCTAssertFalse(latch.isProven)
        XCTAssertEqual(phase(connected: true, connecting: false, proven: latch.isProven), .connecting)
    }
}
