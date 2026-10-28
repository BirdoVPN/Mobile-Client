import XCTest

/// MR-1519 — the Home globe holds still while connected (owner decision: stop
/// while connected, as on Windows and Android), and the change of state stays
/// smooth.
///
/// Before this the globe kept turning for the whole session on iOS. Holding it
/// is the model's clock stopping (`advance(clockRunning: false)`): the spin
/// freezes where it is rather than snapping to a fixed longitude, the connect
/// transition (focus, zoom, arc) still plays out in full, and the spin resumes
/// from the same place on disconnect. WorldGlobeView (a SwiftUI Canvas) cannot
/// be compiled into this un-hosted bundle; `GlobeModel` is everything it
/// animates with. Frames are driven with explicit timestamps at 30 fps.
final class GlobeModelTests: XCTestCase {

    private let frame = 1.0 / 30.0

    /// Runs `seconds` of frames from `start`; returns the time of the last one.
    @MainActor
    @discardableResult
    private func run(_ model: GlobeModel, from start: TimeInterval, seconds: Double,
                     clockRunning: Bool) -> TimeInterval {
        var t = start
        let end = start + seconds
        while t < end {
            t += frame
            model.advance(now: t, clockRunning: clockRunning)
        }
        return t
    }

    @MainActor
    func testTheSpinHoldsWhileConnectedButTheConnectTransitionLands() {
        let model = GlobeModel()
        var t = run(model, from: 1_000, seconds: 10, clockRunning: true)
        let spinAtConnect = model.idleSpinDeg
        XCTAssertGreaterThan(spinAtConnect, 0, "the globe turns while disconnected")

        model.retarget(isConnected: true, hasFocus: true)
        XCTAssertFalse(model.isSettled)
        t = run(model, from: t, seconds: 3, clockRunning: false)

        XCTAssertEqual(model.idleSpinDeg, spinAtConnect, "the globe still turns while connected")
        XCTAssertEqual(model.focus, 1, accuracy: 1e-9)
        XCTAssertEqual(model.zoom, 1.20, accuracy: 1e-9)
        XCTAssertEqual(model.arcProgress, 1, accuracy: 1e-9)
        XCTAssertTrue(model.isSettled, "nothing is left to animate, so the timeline may stop")

        // Everything on the clock is held too: the sun, the pulse, the shimmer.
        let sun = model.sunSpinDeg, pulse = model.pulse, shimmer = model.arcShimmer
        run(model, from: t, seconds: 60, clockRunning: false)
        XCTAssertEqual(model.sunSpinDeg, sun)
        XCTAssertEqual(model.pulse, pulse)
        XCTAssertEqual(model.arcShimmer, shimmer)
    }

    @MainActor
    func testTheTransitionIntoConnectedIsGradualNotASnap() {
        let model = GlobeModel()
        var t = run(model, from: 1_000, seconds: 1, clockRunning: true)
        model.retarget(isConnected: true, hasFocus: true)
        var previous = model.focus
        var largestStep = 0.0
        for _ in 0..<60 {
            t += frame
            model.advance(now: t, clockRunning: false)
            largestStep = max(largestStep, model.focus - previous)
            previous = model.focus
        }
        XCTAssertEqual(model.focus, 1, accuracy: 1e-9)
        XCTAssertLessThan(largestStep, 0.1, "the camera jumped \(largestStep) of the way in one frame")
    }

    @MainActor
    func testTheSpinResumesWhereItStoppedOnDisconnect() {
        let model = GlobeModel()
        var t = run(model, from: 1_000, seconds: 5, clockRunning: true)
        model.retarget(isConnected: true, hasFocus: true)
        t = run(model, from: t, seconds: 30, clockRunning: false)
        let held = model.idleSpinDeg

        model.retarget(isConnected: false, hasFocus: true)
        t += frame
        model.advance(now: t, clockRunning: true)
        // One frame of a 90-second revolution, give or take: no jump.
        XCTAssertLessThan(abs(model.idleSpinDeg - held), 0.5)
        run(model, from: t, seconds: 2, clockRunning: true)
        XCTAssertGreaterThan(model.idleSpinDeg, held, "the globe does not turn again after disconnecting")
    }

    /// A settled, held globe pauses its timeline, so the next frame can come
    /// minutes later. That gap must not open the next transition with a jump.
    @MainActor
    func testALongPauseWhileHeldDoesNotJumpTheNextTransition() {
        let model = GlobeModel()
        var t = run(model, from: 1_000, seconds: 1, clockRunning: true)
        model.retarget(isConnected: true, hasFocus: true)
        t = run(model, from: t, seconds: 3, clockRunning: false)
        XCTAssertTrue(model.isSettled)

        t += 600 // ten minutes with no frames
        model.retarget(isConnected: false, hasFocus: false)
        model.advance(now: t, clockRunning: true)
        XCTAssertEqual(model.focus, 1, accuracy: 1e-9, "the first frame after the pause already moved the camera")
        XCTAssertEqual(model.zoom, 1.20, accuracy: 1e-9)

        t += frame
        model.advance(now: t, clockRunning: true)
        XCTAssertGreaterThan(model.focus, 0.9, "one frame moved the camera more than a frame's worth")
        XCTAssertLessThan(model.focus, 1)
    }

    @MainActor
    func testNotSettledWhileAnyTweenRuns() {
        let model = GlobeModel()
        XCTAssertTrue(model.isSettled, "a fresh model has nothing to animate")
        var t = run(model, from: 1_000, seconds: 1, clockRunning: true)
        model.retarget(isConnected: true, hasFocus: true)
        // Focus (1.4 s) outlasts zoom (1.2 s) and the arc (0.4 s delay + 0.9 s).
        t = run(model, from: t, seconds: 1.3, clockRunning: false)
        XCTAssertFalse(model.isSettled)
        run(model, from: t, seconds: 0.3, clockRunning: false)
        XCTAssertTrue(model.isSettled)
    }
}
