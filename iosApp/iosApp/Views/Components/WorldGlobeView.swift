import Foundation
import SwiftUI

/// Mullvad-style 3D rotating orthographic globe — the Connect-screen hero.
///
/// PORT of Android `ui/components/WorldGlobe.kt`. The projection, lighting,
/// terminator, pin and great-circle-arc maths are line-for-line the same, so a
/// server sits on the same pixel of the same continent on both platforms. Two
/// DELIBERATE divergences, both about iOS's own background identity:
///
///  1. **No opaque space fill and no star field.** Android paints `#030714` over
///     the whole canvas and scatters 90 stars on it. iOS already has ONE
///     app-root `PixelCanvasView` that every tab root is contract-bound to let
///     show through (spec-pixelcanvas-design.md §2.5) — painting an opaque
///     rectangle here would blank the signature grid on the Home tab only, which
///     reads as a bug the moment you switch tabs. The globe therefore draws on a
///     clear background and the twinkling pixel grid IS the star field. The
///     ocean disc is opaque, so the planet still reads solid.
///  2. **Animation is driven by a `TimelineView` clock**, not Compose's
///     `InfiniteTransition` + `animateFloatAsState`. Same 30 fps cadence, same
///     easing (Material FastOutSlowIn = cubic-bézier(0.4, 0, 0.2, 1)), and the
///     same accumulate-across-pauses behaviour so backgrounding the app doesn't
///     snap the planet half a revolution when you come back.
///
/// Power: the globe is only worth animating while it is on screen. It stops dead
/// when the scene leaves `.active`, when Reduce Motion is on, and when the view
/// disappears (tab switch, or a push onto `ServerListView` / `MultiHopView`) —
/// the equivalent of Android's lifecycle gate plus its `if (!showServerSheet)`
/// skip. A frozen globe is drawn, never a blank one.
struct WorldGlobeView: View {
    let servers: [ServerInfo]
    let selectedServerId: String?
    let isConnected: Bool
    let autoRotate: Bool
    /// Android parity: we do not geolocate the user (that would be a privacy
    /// regression in a VPN app), so the "you" pin is a fixed London anchor and
    /// the arc is decorative — it is NOT a claim about where the user is.
    /// Defaults live on `init` below, not here.
    let userLat: Double
    let userLon: Double

    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var model = GlobeModel()
    @State private var onScreen = false

    /// Explicit, like every other component here: the synthesised memberwise
    /// initialiser would be fileprivate because the wrappers above are private,
    /// so `HomeView` could not construct this.
    init(servers: [ServerInfo],
         selectedServerId: String?,
         isConnected: Bool,
         autoRotate: Bool = true,
         userLat: Double = 51.51,
         userLon: Double = -0.13) {
        self.servers = servers
        self.selectedServerId = selectedServerId
        self.isConnected = isConnected
        self.autoRotate = autoRotate
        self.userLat = userLat
        self.userLon = userLon
    }

    private var isPaused: Bool {
        reduceMotion || scenePhase != .active || !onScreen
    }

    /// Halve the clock in Low Power Mode.
    ///
    /// The globe is decoration — it must never be the reason a device on 10%
    /// battery gets warm. iOS also throttles the GPU in this state, so insisting
    /// on 30 fps buys dropped frames rather than smoothness. Read per frame
    /// rather than cached: the user can toggle it while the view is on screen.
    private var frameInterval: Double {
        ProcessInfo.processInfo.isLowPowerModeEnabled ? 1.0 / 15.0 : 1.0 / 30.0
    }

    var body: some View {
        TimelineView(.animation(minimumInterval: frameInterval, paused: isPaused)) { _ in
            Canvas { ctx, size in
                // Mutating the model inside the draw closure is the same
                // deliberate pattern `PixelCanvasView` uses: redraws come from
                // the timeline clock, never from state invalidation, so there is
                // no publish-during-view-update hazard.
                model.retarget(isConnected: isConnected, hasFocus: selectedServerId != nil)
                if reduceMotion {
                    // Freezing the tweens at 0 would leave a Connected user with
                    // no arc and no zoom — snap to the end state instead.
                    model.snapToTargets()
                } else {
                    model.advance()
                }
                render(ctx, size: size)
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { onScreen = true }
        .onDisappear { onScreen = false }
    }

    // MARK: - Render

    private func render(_ ctx: GraphicsContext, size: CGSize) {
        let w = Double(size.width)
        let h = Double(size.height)
        guard w > 0, h > 0 else { return }

        let cx = w * 0.5
        let cy = h * 0.5
        let radius = min(w, h) * 0.46 * model.zoom
        let atmR = radius * 1.22

        // ── Server coordinates ────────────────────────────────────────────────
        var serverPoints: [(id: String, lat: Double, lon: Double)] = []
        serverPoints.reserveCapacity(servers.count)
        for s in servers {
            if let ll = CountryCoords.forCountry(s.countryCode) {
                serverPoints.append((id: s.id, lat: ll.lat, lon: ll.lon))
            }
        }
        let selectedCoord: (lat: Double, lon: Double)? = serverPoints
            .first { $0.id == selectedServerId }
            .map { (lat: $0.lat, lon: $0.lon) }

        // Focal target: midpoint of (user, server) so the connection arc sweeps
        // across the visible front face rather than round the far side.
        var focusLatTarget = 0.0
        var focusLonTarget = 0.0
        if let sel = selectedCoord {
            let rawDLon = sel.lon - userLon
            let shiftedSLon: Double
            if rawDLon > 180 {
                shiftedSLon = sel.lon - 360
            } else if rawDLon < -180 {
                shiftedSLon = sel.lon + 360
            } else {
                shiftedSLon = sel.lon
            }
            focusLatTarget = (userLat + sel.lat) / 2
            focusLonTarget = (userLon + shiftedSLon) / 2
        }

        let focus = model.focus
        let idleLon = autoRotate ? (model.idleSpinDeg + userLon - 25) : userLon
        let effectiveLat = focusLatTarget * focus
        // Shortest-arc lerp against the LIVE idle value — that is what gives a
        // smooth bidirectional handoff instead of a second animation chasing the
        // spin (see the Kotlin note on `lerpAngleDeg`).
        let effectiveLon = Self.lerpAngleDeg(idleLon, focusLonTarget, focus)

        // ── Camera trig ───────────────────────────────────────────────────────
        let latRad = effectiveLat * .pi / 180
        let lonRad = effectiveLon * .pi / 180
        let cosLat = cos(latRad)
        let sinLat = sin(latRad)
        let cosLon = cos(lonRad)
        let sinLon = sin(lonRad)

        /// Project a (lat, lon) onto the disc; `nil` when it is on the far side.
        func project(_ latDeg: Double, _ lonDeg: Double) -> CGPoint? {
            let phi = latDeg * .pi / 180
            let lam = lonDeg * .pi / 180 - lonRad
            let cP = cos(phi)
            let sx = cP * sin(lam)
            let sy = sin(phi)
            let sz = cP * cos(lam)
            let ty = sy * cosLat - sz * sinLat
            let tz = sy * sinLat + sz * cosLat
            guard tz >= 0 else { return nil }
            return CGPoint(x: cx + sx * radius, y: cy - ty * radius)
        }

        // ── Outer atmosphere — Fresnel-ish ring peaking just outside the limb ──
        ctx.fill(
            circlePath(cx, cy, atmR),
            with: .radialGradient(
                Gradient(stops: [
                    .init(color: Palette.atmosphere.opacity(0), location: 0.78),
                    .init(color: Palette.atmosphere.opacity(0.36), location: 0.92),
                    .init(color: Palette.atmosphere.opacity(0), location: 1.0),
                ]),
                center: CGPoint(x: cx, y: cy),
                startRadius: 0,
                endRadius: CGFloat(atmR)
            )
        )

        let disc = circlePath(cx, cy, radius)

        // ── Ocean disc, lit from the upper-left ───────────────────────────────
        ctx.fill(
            disc,
            with: .radialGradient(
                Gradient(colors: [Palette.oceanCore, Palette.oceanRim]),
                center: CGPoint(x: cx - radius * 0.22, y: cy - radius * 0.28),
                startRadius: 0,
                endRadius: CGFloat(radius * 1.05)
            )
        )

        // ── Continents ────────────────────────────────────────────────────────
        // Walk the packed [sinPhi, cosPhi, lonRad] buffer once, cull the back
        // hemisphere, and bucket each surviving cell into one of three brightness
        // paths (fake directional shading). Three fills instead of ~10k — that is
        // the whole reason the buffer is packed and pre-built.
        var pathDim = Path()
        var pathMid = Path()
        var pathLit = Path()
        let geometry = GlobeGeometry.current()
        let cellPx = radius * geometry.cellSizeRad
        // Directional light in camera space (upper-left, towards the camera).
        let lx = -0.42, ly = -0.55, lz = 0.72
        // Frame constants for the angle-difference identities below — computed
        // ONCE per frame instead of sin/cos per cell.
        let sinLonRad = sin(lonRad)
        let cosLonRad = cos(lonRad)
        geometry.samples.withUnsafeBufferPointer { data in
            var i = 0
            let limit = data.count
            while i < limit {
                let sinPhi = data[i]
                let cosPhi = data[i + 1]
                let sinLon = data[i + 2]
                let cosLon = data[i + 3]
                i += 4
                // cos(lon - lonRad), needed for the depth test. Computed first so
                // the whole back hemisphere — roughly half the buffer — is
                // rejected before any of the projection work below.
                let cosLam = cosLon * cosLonRad + sinLon * sinLonRad
                let sz = cosPhi * cosLam
                let tz = sinPhi * sinLat + sz * cosLat
                if tz <= 0.02 { continue }
                let sinLam = sinLon * cosLonRad - cosLon * sinLonRad
                let sx = cosPhi * sinLam
                let ty = sinPhi * cosLat - sz * sinLat
                let px = cx + sx * radius
                let py = cy - ty * radius
                let dot = sx * lx + ty * ly + tz * lz
                // Foreshorten cells near the limb to disguise the square grid.
                let half = cellPx * (0.55 + 0.45 * tz) * 0.5
                let rect = CGRect(x: px - half, y: py - half, width: half * 2, height: half * 2)
                if dot > 0.55 {
                    pathLit.addRect(rect)
                } else if dot > 0.20 {
                    pathMid.addRect(rect)
                } else {
                    pathDim.addRect(rect)
                }
            }
        }
        ctx.fill(pathDim, with: .color(Palette.landDim))
        ctx.fill(pathMid, with: .color(Palette.landMid))
        ctx.fill(pathLit, with: .color(Palette.landLit))

        // ── Inner limb darkening — clip-free vignette ring at the disc edge ───
        ctx.fill(
            disc,
            with: .radialGradient(
                Gradient(stops: [
                    .init(color: .clear, location: 0),
                    .init(color: .clear, location: 0.65),
                    .init(color: .black.opacity(0.30), location: 1.0),
                ]),
                center: CGPoint(x: cx, y: cy),
                startRadius: 0,
                endRadius: CGFloat(radius)
            )
        )

        // ── Cool-blue rim stroke at the limb ─────────────────────────────────
        ctx.stroke(circlePath(cx, cy, radius - 0.5),
                   with: .color(Palette.rim.opacity(0.50)),
                   lineWidth: 1.2)

        // ── Specular highlight (upper-left) ──────────────────────────────────
        let specR = radius * 0.55
        let specC = CGPoint(x: cx - radius * 0.45, y: cy - radius * 0.55)
        ctx.fill(
            circlePath(Double(specC.x), Double(specC.y), specR),
            with: .radialGradient(
                Gradient(colors: [.white.opacity(0.10), .clear]),
                center: specC,
                startRadius: 0,
                endRadius: CGFloat(specR)
            )
        )

        // ── Day / night terminator ───────────────────────────────────────────
        // The dark side is the hemisphere opposite the sun: a soft dark radial
        // centred on the ANTI-solar point, plus a faint warm hotspot at the
        // sub-solar point when the sun is on the visible face. Both stay inside
        // the planet by being filled through the disc path.
        do {
            let sunLonDeg = 180 - model.sunSpinDeg
            let sunLatDeg = 12.0
            let sPhi = sunLatDeg * .pi / 180
            let sLam = sunLonDeg * .pi / 180 - lonRad
            let sCp = cos(sPhi)
            let ux = sCp * sin(sLam)
            let uy = sin(sPhi)
            let uz = sCp * cos(sLam)
            let syCam = uy * cosLat - uz * sinLat
            let szCam = uy * sinLat + uz * cosLat

            // Night veil, sized 1.6x radius so its edge falls past the day/night
            // boundary instead of showing a visible cutoff on the disc.
            ctx.fill(
                disc,
                with: .radialGradient(
                    Gradient(stops: [
                        .init(color: Palette.night.opacity(0.55), location: 0),
                        .init(color: Palette.night.opacity(0.3025), location: 0.55),
                        .init(color: .clear, location: 1.0),
                    ]),
                    center: CGPoint(x: cx - ux * radius, y: cy + syCam * radius),
                    startRadius: 0,
                    endRadius: CGFloat(radius * 1.6)
                )
            )

            if szCam > 0 {
                ctx.fill(
                    disc,
                    with: .radialGradient(
                        Gradient(colors: [Palette.sun.opacity(0.18 * szCam), .clear]),
                        center: CGPoint(x: cx + ux * radius, y: cy - syCam * radius),
                        startRadius: 0,
                        endRadius: CGFloat(radius * 0.7)
                    )
                )
            }
        }

        // ── Server dots ──────────────────────────────────────────────────────
        // ALL visible nodes are plotted so the user can see every choice. The
        // selected one is skipped here — it gets the bigger pin below.
        let pulse = model.pulse
        for sp in serverPoints where sp.id != selectedServerId {
            let phi = sp.lat * .pi / 180
            let lam = sp.lon * .pi / 180 - lonRad
            let cP = cos(phi)
            let sxs = cP * sin(lam)
            let sys = sin(phi)
            let szs = cP * cos(lam)
            let ty = sys * cosLat - szs * sinLat
            let tz = sys * sinLat + szs * cosLat
            if tz <= 0.02 { continue }
            let px = cx + sxs * radius
            let py = cy - ty * radius
            let a = 0.55 + 0.45 * min(max(tz, 0), 1)
            let haloR = 6.5 + pulse * 1.8
            fill(ctx, px, py, haloR, Palette.accent.opacity(0.18 * a))
            fill(ctx, px, py, haloR * 0.62, Palette.accent.opacity(0.30 * a))
            fill(ctx, px, py, 3.2, Palette.accent.opacity(0.95 * a))
            fill(ctx, px, py, 1.3, .white.opacity(0.85 * a))
        }

        // ── Connection: great-circle arc, then the two pins ──────────────────
        if let sel = selectedCoord {
            drawGreatCircleArc(
                ctx,
                cx: cx, cy: cy, radius: radius,
                cosLat: cosLat, sinLat: sinLat, cosLon: cosLon, sinLon: sinLon,
                startLat: userLat, startLon: userLon,
                endLat: sel.lat, endLon: sel.lon,
                progress: model.arcProgress,
                shimmer: model.arcShimmer,
                color: isConnected ? Palette.connected : Palette.accent
            )
            if let userPos = project(userLat, userLon) {
                drawPin(ctx, userPos, isConnected ? Palette.connected : Palette.accent,
                        pulse: pulse, small: true)
            }
            if let srvPos = project(sel.lat, sel.lon) {
                drawPin(ctx, srvPos, Palette.accent, pulse: pulse, small: false)
            }
        } else if let userPos = project(userLat, userLon) {
            drawPin(ctx, userPos, Palette.accent.opacity(0.95), pulse: pulse, small: true)
        }
    }

    // MARK: - Primitives

    private func circlePath(_ cx: Double, _ cy: Double, _ r: Double) -> Path {
        Path(ellipseIn: CGRect(x: cx - r, y: cy - r, width: r * 2, height: r * 2))
    }

    private func fill(_ ctx: GraphicsContext, _ cx: Double, _ cy: Double,
                      _ r: Double, _ color: Color) {
        ctx.fill(circlePath(cx, cy, r), with: .color(color))
    }

    /// Mullvad-style pin: outer translucent halo + filled disc + inner white dot.
    private func drawPin(_ ctx: GraphicsContext, _ center: CGPoint, _ color: Color,
                         pulse: Double, small: Bool) {
        let x = Double(center.x)
        let y = Double(center.y)
        let halo = (small ? 11.0 : 17.0) + pulse * 5
        fill(ctx, x, y, halo, color.opacity(0.18))
        fill(ctx, x, y, halo * 0.62, color.opacity(0.34))
        fill(ctx, x, y, small ? 5.0 : 7.0, color)
        fill(ctx, x, y, small ? 2.0 : 2.8, .white)
    }

    /// Great-circle arc on the unit sphere via slerp, projected segment by
    /// segment. It clips to the visible hemisphere for free — the polyline simply
    /// breaks where a segment dips behind the limb. `lift > 1` floats it just off
    /// the surface so it reads cleanly against land.
    private func drawGreatCircleArc(
        _ ctx: GraphicsContext,
        cx: Double, cy: Double, radius: Double,
        cosLat: Double, sinLat: Double, cosLon: Double, sinLon: Double,
        startLat: Double, startLon: Double,
        endLat: Double, endLon: Double,
        progress: Double,
        shimmer: Double,
        color: Color
    ) {
        guard progress > 0 else { return }
        let phi1 = startLat * .pi / 180
        let lam1 = startLon * .pi / 180
        let phi2 = endLat * .pi / 180
        let lam2 = endLon * .pi / 180
        let ax = cos(phi1) * sin(lam1)
        let ay = sin(phi1)
        let az = cos(phi1) * cos(lam1)
        let bx = cos(phi2) * sin(lam2)
        let by = sin(phi2)
        let bz = cos(phi2) * cos(lam2)
        let dot = min(max(ax * bx + ay * by + az * bz, -1), 1)
        let omega = acos(dot)
        guard omega >= 1e-3 else { return }
        let sinO = sin(omega)
        let segments = 64
        let travel = max(2, Int(Double(segments) * progress))

        /// Slerp at `t`, lifted off the surface, then run through the same camera
        /// transform `project()` uses. Returns nil behind the limb.
        func point(at t: Double, lift: Double) -> CGPoint? {
            let a = sin((1 - t) * omega) / sinO
            let b = sin(t * omega) / sinO
            let ux = (a * ax + b * bx) * lift
            let uy = (a * ay + b * by) * lift
            let uz = (a * az + b * bz) * lift
            let sx = ux * cosLon - uz * sinLon
            let sz0 = ux * sinLon + uz * cosLon
            let ty = uy * cosLat - sz0 * sinLat
            let tz = uy * sinLat + sz0 * cosLat
            guard tz >= 0 else { return nil }
            return CGPoint(x: cx + sx * radius, y: cy - ty * radius)
        }

        var path = Path()
        var pen = false
        var anyDrawn = false
        for seg in 0...travel {
            let t = Double(seg) / Double(segments)
            guard let p = point(at: t, lift: 1 + 0.045 * sin(.pi * t)) else {
                pen = false
                continue
            }
            if pen {
                path.addLine(to: p)
            } else {
                path.move(to: p)
                pen = true
            }
            anyDrawn = true
        }
        guard anyDrawn else { return }

        // Layered glow, then the crisp core stroke.
        ctx.stroke(path, with: .color(color.opacity(0.10)),
                   style: StrokeStyle(lineWidth: 12, lineCap: .round))
        ctx.stroke(path, with: .color(color.opacity(0.22)),
                   style: StrokeStyle(lineWidth: 6, lineCap: .round))
        ctx.stroke(path, with: .color(color.opacity(0.95)),
                   style: StrokeStyle(lineWidth: 2.4, lineCap: .round))

        // Travelling shimmer, only once the arc is fully drawn.
        guard progress >= 0.99,
              let p = point(at: min(max(shimmer, 0), 1), lift: 1.045) else { return }
        fill(ctx, Double(p.x), Double(p.y), 6, color.opacity(0.45))
        fill(ctx, Double(p.x), Double(p.y), 2.6, .white.opacity(0.9))
    }

    private static func lerpAngleDeg(_ a: Double, _ b: Double, _ t: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return a + d * t
    }

    /// OLED branch of Android's palette. iOS has no light theme, so the
    /// `isLight` fork in `WorldGlobe.kt` collapses to its dark side here.
    private enum Palette {
        static let oceanCore  = Color(hex: 0x1A3050)
        static let oceanRim   = Color(hex: 0x071426)
        static let landDim    = Color(hex: 0x1F4364)
        static let landMid    = Color(hex: 0x356D9F)
        static let landLit    = Color(hex: 0x59A8E0)
        static let atmosphere = Color(hex: 0x4983C7)
        static let rim        = Color(hex: 0x7BB2E6)
        static let night      = Color(hex: 0x02060F)
        static let sun        = Color(hex: 0xFFE6B0)
        static let accent     = BirdoTheme.accent
        static let connected  = BirdoTheme.green
    }
}

// MARK: - Animation model

/// The globe's clock and its three tweens. Held by `@StateObject` and advanced
/// from inside the `Canvas` draw closure — see the note there.
@MainActor
final class GlobeModel: ObservableObject {
    /// Accumulated VISIBLE seconds. Advances only while the timeline is running,
    /// so a backgrounded app resumes the spin where it left off rather than
    /// jumping forward by however long it was away.
    private var clock: Double = 0
    private var lastFrameAt: TimeInterval = 0

    private var focusTween = Tween(0, duration: 1.4)
    private var zoomTween = Tween(1, duration: 1.2)
    private var arcTween = Tween(0, duration: 0.9)

    var focus: Double { focusTween.value }
    var zoom: Double { zoomTween.value }
    var arcProgress: Double { arcTween.value }

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
        focusTween.retarget(hasFocus ? 1 : 0)
        zoomTween.retarget(isConnected ? 1.20 : (hasFocus ? 1.06 : 1.0))
        // The 400 ms lead-in lets the connected colour land before the arc starts
        // drawing, exactly like the Kotlin `delayMillis`.
        arcTween.retarget(isConnected && hasFocus ? 1 : 0,
                          delay: isConnected ? 0.4 : 0)
    }

    func advance() {
        let now = Date.timeIntervalSinceReferenceDate
        defer { lastFrameAt = now }
        guard lastFrameAt > 0 else { return }
        // Clamp so a stalled — or just-resumed — timeline can't teleport things.
        let dt = min(max(now - lastFrameAt, 0), 0.25)
        clock += dt
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

// MARK: - Pre-computed land geometry

/// The ~19k land cells, flattened once per process into a single `[Double]` of
/// `[sinPhi, cosPhi, lonRad]` triples. Rebuilding this per frame — or walking the
/// 259200-cell mask and allocating a tuple per cell — is exactly what makes a
/// globe like this stutter; the render loop just strides the buffer.
/// Pre-tabulated land cells for one level of detail.
///
/// Packs FOUR values per cell — `sinPhi, cosPhi, sinLon, cosLon` — rather than
/// storing the longitude itself. That is the whole point: the renderer needs
/// `sin(lon - lonRad)` and `cos(lon - lonRad)` every frame, and the angle
/// difference identities
///
///     sin(a - b) = sin a · cos b - cos a · sin b
///     cos(a - b) = cos a · cos b + sin a · sin b
///
/// turn those into four multiplies against two frame-constant terms. Storing
/// the raw longitude instead forced `sin()` and `cos()` per cell per frame:
/// with ~18k land cells at 30 fps that was over a million transcendental calls
/// a second, which is what made the globe stutter on iPad.
private struct GlobeLOD {
    let samples: [Double]
    /// Angular extent of one cell at the equator, in radians.
    let cellSizeRad: Double

    init(stride: Int) {
        let rows = WorldLandmask.rowCount()
        let cols = WorldLandmask.colCount()
        let cellLat = Double.pi / Double(rows)
        let cellLon = (2 * Double.pi) / Double(cols)

        var packed: [Double] = []
        packed.reserveCapacity(20_000 * 4)
        var r = 0
        while r < rows {
            let phi = (Double.pi / 2) - (Double(r) + 0.5) * cellLat
            let sinPhi = sin(phi)
            let cosPhi = cos(phi)
            var c = 0
            while c < cols {
                if WorldLandmask.isLandCell(r, c) {
                    let lon = -Double.pi + (Double(c) + 0.5) * cellLon
                    packed.append(sinPhi)
                    packed.append(cosPhi)
                    packed.append(sin(lon))
                    packed.append(cos(lon))
                }
                c += stride
            }
            r += stride
        }
        samples = packed
        cellSizeRad = cellLat * Double(stride) * 1.55
    }
}

private struct GlobeGeometry {
    // `nonisolated(unsafe)` because every access is serialised by `cacheLock`
    // below — the same pattern the timers in VpnViewModel use. Swift 6 cannot
    // see the lock, so it has to be told the invariant is held manually.
    nonisolated(unsafe) private static var cache: [Int: GlobeLOD] = [:]
    private static let cacheLock = NSLock()

    /// Cell density is a COST decision, not a size one.
    ///
    /// A previous attempt picked the stride from the rendered radius, aiming for
    /// roughly 2 pt cells. That was backwards: a large globe satisfies the
    /// threshold at the densest stride, so the bigger the display the more cells
    /// were drawn — which is exactly why iPad was the worst case rather than the
    /// best.
    ///
    /// Cell size already scales with radius (`cellPx = radius * cellSizeRad`), so
    /// a coarser grid still covers the sphere completely; cells simply become
    /// chunkier, which suits a deliberately pixelated globe. Stride 6 draws about
    /// 2k cells against roughly 18k at stride 2 — an order of magnitude less path
    /// building and filling per frame, which is the real remaining cost now that
    /// the trig is gone.
    private static let normalStride = 6
    private static let lowPowerStride = 8

    static func current() -> GlobeLOD {
        let chosen = ProcessInfo.processInfo.isLowPowerModeEnabled ? lowPowerStride : normalStride
        cacheLock.lock()
        defer { cacheLock.unlock() }
        if let hit = cache[chosen] { return hit }
        let built = GlobeLOD(stride: chosen)
        cache[chosen] = built
        return built
    }
}
