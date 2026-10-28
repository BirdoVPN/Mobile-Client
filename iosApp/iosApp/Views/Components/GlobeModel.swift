import Foundation

// MARK: - Animation model

/// The Home globe's clock and its three tweens. Held by `WorldGlobeView` as a
/// `@StateObject` (the `ObservableObject` conformance lives there) and
/// advanced from inside its `Canvas` draw closure — see the note there.
///
/// Foundation-only by design, so `GlobeModelTests` can pin it in the
/// un-hosted test bundle; WorldGlobeView (a SwiftUI Canvas) cannot be compiled
/// there.
///
/// MR-1519 (owner decision 2026-10-07, confirmed 2026-10-28): the globe stops
/// turning while connected, as on Windows and Android. That is the CLOCK
/// holding (`advance(clockRunning: false)`), not the spin being swapped for a
/// fixed longitude: the spin, the sun's terminator, the pulse and the arc
/// shimmer all freeze where they are, so nothing snaps on connect, and they
/// resume from the same place on disconnect. The focus, zoom and arc tweens
/// are not on that clock, so the connect transition still plays out in full.
@MainActor
final class GlobeModel {
    /// Accumulated VISIBLE, UNHELD seconds. Advances only while the timeline
    /// is running and the globe may turn, so a backgrounded app resumes the
    /// spin where it left off rather than jumping forward by however long it
    /// was away, and a connected globe stays where it stopped.
    private var clock: Double = 0
    private var lastFrameAt: TimeInterval = 0

    private var focusTween = Tween(0, duration: 1.4)
    private var zoomTween = Tween(1, duration: 1.2)
    private var arcTween = Tween(0, duration: 0.9)

    var focus: Double { focusTween.value }
    var zoom: Double { zoomTween.value }
    var arcProgress: Double { arcTween.value }

    /// True once every tween has landed. With the clock held as well, nothing
    /// on the globe moves after this, so its timeline can stop drawing frames.
    var isSettled: Bool { !focusTween.isRunning && !zoomTween.isRunning && !arcTween.isRunning }

    /// Cyclic phases, all pure functions of `clock` (Android runs five separate
    /// infinite transitions; one monotonic counter reproduces them exactly).
    var pulse: Double { Self.phase(clock, 1.8) }
    var arcShimmer: Double { Self.phase(clock, 2.4) }
    var idleSpinDeg: Double { Self.phase(clock, 90) * 360 }
    /// The sun's longitude sweeps a full revolution every 2 minutes, independent
    /// of the camera, so the terminator crawls across continents even while the
    /// globe is focused and still.
    var sunSpinDeg: Double { Self.phase(clock, 120) * 360 }

    nonisolated init() {}

    func retarget(isConnected: Bool, hasFocus: Bool) {
        let wasSettled = isSettled
        focusTween.retarget(hasFocus ? 1 : 0)
        zoomTween.retarget(isConnected ? 1.20 : (hasFocus ? 1.06 : 1.0))
        // The 400 ms lead-in lets the connected colour land before the arc starts
        // drawing, exactly like the Kotlin `delayMillis`.
        arcTween.retarget(isConnected && hasFocus ? 1 : 0,
                          delay: isConnected ? 0.4 : 0)
        // A settled, held globe may have drawn no frame for minutes. Without
        // this, the first frame of the new transition would be credited with
        // that whole gap (clamped to 0.25 s) and open with a visible jump.
        if wasSettled && !isSettled { lastFrameAt = 0 }
    }

    /// One frame. `clockRunning` false holds the spin and every cyclic phase
    /// where they are (connected, MR-1519); the tweens advance regardless.
    func advance(now: TimeInterval = Date.timeIntervalSinceReferenceDate,
                 clockRunning: Bool = true) {
        defer { lastFrameAt = now }
        guard lastFrameAt > 0 else { return }
        // Clamp so a stalled — or just-resumed — timeline can't teleport things.
        let dt = min(max(now - lastFrameAt, 0), 0.25)
        if clockRunning { clock += dt }
        focusTween.advance(dt)
        zoomTween.advance(dt)
        arcTween.advance(dt)
    }

    /// Reduce Motion: land on the end state without playing the transition.
    func snapToTargets() {
        focusTween.finish()
        zoomTween.finish()
        arcTween.finish()
    }

    private static func phase(_ clock: Double, _ period: Double) -> Double {
        clock.truncatingRemainder(dividingBy: period) / period
    }
}

/// A one-shot eased transition — the moral equivalent of Compose's
/// `animateFloatAsState(tween(…, FastOutSlowInEasing))`.
private struct Tween {
    private(set) var value: Double
    private var start: Double
    private var target: Double
    private var elapsed: Double = 0
    private var delay: Double = 0
    private let duration: Double
    private var running = false

    var isRunning: Bool { running }

    init(_ initial: Double, duration: Double) {
        value = initial
        start = initial
        target = initial
        self.duration = duration
    }

    mutating func retarget(_ newTarget: Double, delay: Double = 0) {
        guard newTarget != target else { return }
        start = value
        target = newTarget
        elapsed = 0
        self.delay = delay
        running = true
    }

    mutating func advance(_ dt: Double) {
        guard running else { return }
        elapsed += dt
        let t = elapsed - delay
        guard t > 0 else { return }
        if t >= duration {
            finish()
            return
        }
        value = start + (target - start) * Self.fastOutSlowIn(t / duration)
    }

    mutating func finish() {
        value = target
        running = false
    }

    /// Material's FastOutSlowInEasing — cubic-bézier(0.4, 0, 0.2, 1). Newton
    /// solves x(u) = t, then reads y(u); five iterations is well inside a pixel
    /// for this curve.
    private static func fastOutSlowIn(_ t: Double) -> Double {
        let p1x = 0.4, p2x = 0.2, p1y = 0.0, p2y = 1.0
        var u = t
        for _ in 0..<5 {
            let mu = 1 - u
            let x = 3 * mu * mu * u * p1x + 3 * mu * u * u * p2x + u * u * u
            let dx = 3 * mu * mu * p1x + 6 * mu * u * (p2x - p1x) + 3 * u * u * (1 - p2x)
            if abs(dx) < 1e-6 { break }
            u = min(max(u - (x - t) / dx, 0), 1)
        }
        let mu = 1 - u
        return 3 * mu * mu * u * p1y + 3 * mu * u * u * p2y + u * u * u
    }
}
