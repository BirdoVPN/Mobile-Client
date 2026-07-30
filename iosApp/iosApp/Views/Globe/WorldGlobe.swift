import SwiftUI

// ─────────────────────────────────────────────────────────────────────────────
// Mullvad-style 3D rotating orthographic globe — SwiftUI Canvas port of the
// Android `WorldGlobe` (compose). Same projection math, land grid, day/night
// terminator, server pins and great-circle connection arc.
//
// Driven by a single ~30 fps animation clock (TimelineView), which SwiftUI
// pauses automatically when the view is off-screen — so it never burns frames
// in the background. Reduce Motion freezes it to one static frame.
// ─────────────────────────────────────────────────────────────────────────────

struct WorldGlobe: View {
    let servers: [ServerInfo]
    let selectedServerId: String?
    let isConnected: Bool
    var autoRotate: Bool = true
    /// Default "you are here" = London, matching the Android default.
    var userLat: Double = 51.51
    var userLon: Double = -0.13

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // Time-driven tweens (focus/zoom/arc) so every animated value is a pure
    // function of the same clock the Canvas reads — no SwiftUI implicit
    // animation fighting the per-frame redraw.
    @State private var focusTween = Tween(0)
    @State private var zoomTween = Tween(1)
    @State private var arcTween = Tween(0)
    @State private var started = false

    var body: some View {
        let points = Self.serverPoints(servers)
        let selected = points.first { $0.id == selectedServerId }
        let hasFocus = selected != nil

        // Focal target: midpoint of (user, server) so the arc sweeps the front face.
        var focusLat: Double = 0, focusLon: Double = 0
        if let s = selected {
            let rawDLon = s.lon - userLon
            let shifted = rawDLon > 180 ? s.lon - 360 : (rawDLon < -180 ? s.lon + 360 : s.lon)
            focusLat = (userLat + s.lat) / 2
            focusLon = (userLon + shifted) / 2
        }

        return TimelineView(.animation(minimumInterval: 1.0 / 24.0, paused: reduceMotion)) { timeline in
            let ms = timeline.date.timeIntervalSince1970 * 1000
            Canvas { ctx, size in
                Self.render(
                    ctx: &ctx, size: size, ms: ms,
                    focus: focusTween.value(ms), zoom: zoomTween.value(ms), arc: arcTween.value(ms),
                    autoRotate: autoRotate, isConnected: isConnected,
                    focusLatTarget: focusLat, focusLonTarget: focusLon,
                    points: points, selected: selected,
                    selectedServerId: selectedServerId,
                    userLat: userLat, userLon: userLon
                )
            }
        }
        .onAppear {
            let now = Self.nowMs()
            focusTween.retarget(to: hasFocus ? 1 : 0, ms: now, duration: 1)
            zoomTween.retarget(to: isConnected ? 1.20 : (hasFocus ? 1.06 : 1), ms: now, duration: 1)
            arcTween.retarget(to: (isConnected && hasFocus) ? 1 : 0, ms: now, duration: 1)
            started = true
        }
        .onChange(of: hasFocus) { _, nf in
            let now = Self.nowMs()
            focusTween.retarget(to: nf ? 1 : 0, ms: now, duration: 1400)
            zoomTween.retarget(to: isConnected ? 1.20 : (nf ? 1.06 : 1), ms: now, duration: 1200)
            arcTween.retarget(to: (isConnected && nf) ? 1 : 0, ms: now, duration: 900)
        }
        .onChange(of: isConnected) { _, ic in
            let now = Self.nowMs()
            zoomTween.retarget(to: ic ? 1.20 : (hasFocus ? 1.06 : 1), ms: now, duration: 1200)
            arcTween.retarget(to: (ic && hasFocus) ? 1 : 0, ms: now, duration: 900)
        }
        .accessibilityHidden(true)
    }

    static func nowMs() -> Double { Date().timeIntervalSince1970 * 1000 }

    // MARK: - Server points

    struct GlobePoint { let id: String; let lat: Double; let lon: Double }

    static func serverPoints(_ servers: [ServerInfo]) -> [GlobePoint] {
        servers.compactMap { s in
            CountryCoords.forCountry(s.countryCode).map { GlobePoint(id: s.id, lat: $0.lat, lon: $0.lon) }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Time-based easing tween (easeInOutCubic ≈ Compose FastOutSlowIn)
// ─────────────────────────────────────────────────────────────────────────────

private struct Tween {
    var from: Double
    var to: Double
    var startMs: Double = 0
    var durationMs: Double = 1
    init(_ v: Double) { from = v; to = v }
    func value(_ ms: Double) -> Double {
        if durationMs <= 0 { return to }
        let t = min(max((ms - startMs) / durationMs, 0), 1)
        let e = t < 0.5 ? 4 * t * t * t : 1 - pow(-2 * t + 2, 3) / 2
        return from + (to - from) * e
    }
    mutating func retarget(to newTo: Double, ms: Double, duration: Double) {
        from = value(ms); to = newTo; startMs = ms; durationMs = duration
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Precomputation (once) + colours
// ─────────────────────────────────────────────────────────────────────────────

private func rgb(_ hex: UInt32, _ a: Double = 1) -> Color {
    Color(.sRGB,
          red: Double((hex >> 16) & 0xFF) / 255,
          green: Double((hex >> 8) & 0xFF) / 255,
          blue: Double(hex & 0xFF) / 255,
          opacity: a)
}

private struct LandSamples { let data: [Float]; let count: Int; let cellSizeRad: Double }

extension WorldGlobe {
    fileprivate static let landSamples: LandSamples = precomputeLandSamples()
    fileprivate static let stars: [Float] = precomputeStars(90)

    private static func precomputeLandSamples() -> LandSamples {
        let rows = WorldLandmask.rowCount(), cols = WorldLandmask.colCount()
        let cellLat = Double.pi / Double(rows)
        let cellLon = (2 * Double.pi) / Double(cols)
        // Stride 4 (vs Android's 2): samples 1/4 the grid → ~4× fewer land
        // squares to rebuild + tessellate per frame. The cellSizeRad below scales
        // with the stride (and the 1.55 overlap factor), so the larger squares
        // still overlap into solid continents — just a slightly coarser coastline,
        // which keeps the framerate smooth on older hardware (A10 iPad).
        let strideRow = 4, strideCol = 4
        var counted = 0
        var r = 0
        while r < rows {
            var c = 0
            while c < cols { if WorldLandmask.isLandCell(r, c) { counted += 1 }; c += strideCol }
            r += strideRow
        }
        var arr = [Float](repeating: 0, count: counted * 3)
        var idx = 0
        r = 0
        while r < rows {
            let phi = (Double.pi / 2) - (Double(r) + 0.5) * cellLat
            let sinPhi = Float(sin(phi)), cosPhi = Float(cos(phi))
            var c = 0
            while c < cols {
                if WorldLandmask.isLandCell(r, c) {
                    let lon = -Double.pi + (Double(c) + 0.5) * cellLon
                    arr[idx] = sinPhi; arr[idx + 1] = cosPhi; arr[idx + 2] = Float(lon); idx += 3
                }
                c += strideCol
            }
            r += strideRow
        }
        return LandSamples(data: arr, count: idx / 3, cellSizeRad: cellLat * Double(strideRow) * 1.55)
    }

    private static func precomputeStars(_ count: Int) -> [Float] {
        var state: UInt64 = 0xB16D0
        func next() -> Float {
            state = state &* 6364136223846793005 &+ 1442695040888963407
            return Float((state >> 40) & 0xFFFFFF) / Float(0x1000000)
        }
        var arr = [Float](repeating: 0, count: count * 3)
        for i in 0..<arr.count { arr[i] = next() }
        return arr
    }

    fileprivate static func cyclePhase(_ ms: Double, _ period: Double) -> Double {
        ms.truncatingRemainder(dividingBy: period) / period
    }

    fileprivate static func lerpAngleDeg(_ a: Double, _ b: Double, _ t: Double) -> Double {
        var d = (b - a).truncatingRemainder(dividingBy: 360)
        if d > 180 { d -= 360 }
        if d < -180 { d += 360 }
        return a + d * t
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendering (single hot path)
// ─────────────────────────────────────────────────────────────────────────────

extension WorldGlobe {
    // Dark (OLED) palette — the app is dark-themed; these are the Android
    // `else`-branch (night-earth) colours.
    fileprivate static let cSpace = rgb(0x030714)
    fileprivate static let cStar = rgb(0xB6C5E2)
    fileprivate static let cOceanCore = rgb(0x1A3050)
    fileprivate static let cOceanRim = rgb(0x071426)
    fileprivate static let cLandDim = rgb(0x1F4364)
    fileprivate static let cLandMid = rgb(0x356D9F)
    fileprivate static let cLandLit = rgb(0x59A8E0)
    fileprivate static let cAtmosphere = rgb(0x4983C7)
    fileprivate static let cRim = rgb(0x7BB2E6)
    fileprivate static let cAccent = rgb(0x10B981)
    fileprivate static let cConnected = rgb(0x34D399)

    private static func disc(_ center: CGPoint, _ r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r))
    }

    private static func dot(_ ctx: inout GraphicsContext, _ center: CGPoint, _ r: CGFloat, _ color: Color) {
        ctx.fill(disc(center, r), with: .color(color))
    }

    // swiftlint:disable:next function_body_length
    fileprivate static func render(
        ctx: inout GraphicsContext, size: CGSize, ms: Double,
        focus: Double, zoom: Double, arc: Double,
        autoRotate: Bool, isConnected: Bool,
        focusLatTarget: Double, focusLonTarget: Double,
        points: [GlobePoint], selected: GlobePoint?,
        selectedServerId: String?,
        userLat: Double, userLon: Double
    ) {
        let w = size.width, h = size.height
        if w <= 0 || h <= 0 { return }

        let pulse = cyclePhase(ms, 1800)
        let arcShimmer = cyclePhase(ms, 2400)
        let twinkle = cyclePhase(ms, 3600)
        let idleSpin = cyclePhase(ms, 90_000) * 360
        let sunSpin = cyclePhase(ms, 120_000) * 360

        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(cSpace))

        let cx = w * 0.5, cy = h * 0.5
        let baseR = min(w, h) * 0.40   // was 0.46 — a touch smaller per feedback
        let radius = baseR * CGFloat(zoom)
        let atmR = radius * 1.22
        let atmR2 = atmR * atmR
        let ctr = CGPoint(x: cx, y: cy)

        // Camera
        let idleLon = autoRotate ? (idleSpin + userLon - 25) : userLon
        let effLat = focusLatTarget * focus
        let effLon = lerpAngleDeg(idleLon, focusLonTarget, focus)
        let latRad = effLat * .pi / 180, lonRad = effLon * .pi / 180
        let cosLat = cos(latRad), sinLat = sin(latRad)
        let cosLon = cos(lonRad), sinLon = sin(lonRad)
        let lx = -0.42, ly = -0.55, lz = 0.72

        // Star field (occluded by disc)
        var s = 0
        while s < stars.count {
            let sx = CGFloat(stars[s]) * w, sy = CGFloat(stars[s + 1]) * h, sb = Double(stars[s + 2])
            s += 3
            let dx = sx - cx, dy = sy - cy
            if dx * dx + dy * dy < atmR2 { continue }
            let tw = 0.55 + 0.45 * sin(twinkle * 2 * .pi + sb * 13.7)
            dot(&ctx, CGPoint(x: sx, y: sy), sb > 0.85 ? 1.5 : 0.9,
                cStar.opacity((sb * 0.65 + 0.18) * tw))
        }

        // Outer atmosphere (Fresnel ring)
        ctx.fill(disc(ctr, atmR), with: .radialGradient(
            Gradient(stops: [
                .init(color: cAtmosphere.opacity(0), location: 0.78),
                .init(color: cAtmosphere.opacity(0.36), location: 0.92),
                .init(color: cAtmosphere.opacity(0), location: 1.0),
            ]), center: ctr, startRadius: 0, endRadius: atmR))

        // Ocean disc (directional radial gradient)
        ctx.fill(disc(ctr, radius), with: .radialGradient(
            Gradient(colors: [cOceanCore, cOceanRim]),
            center: CGPoint(x: cx - radius * 0.22, y: cy - radius * 0.28),
            startRadius: 0, endRadius: radius * 1.05))

        // Continents — project packed samples, classify by lighting into 3 paths
        var pDim = Path(), pMid = Path(), pLit = Path()
        let cellPx = radius * CGFloat(landSamples.cellSizeRad)
        let data = landSamples.data
        let limit = landSamples.count * 3
        var i = 0
        while i < limit {
            let sinPhi = Double(data[i]), cosPhi = Double(data[i + 1]), lon = Double(data[i + 2])
            i += 3
            let lam = lon - lonRad
            let sinL = sin(lam), cosL = cos(lam)
            let sxu = cosPhi * sinL
            let syu = sinPhi
            let szu = cosPhi * cosL
            let ty = syu * cosLat - szu * sinLat
            let tz = syu * sinLat + szu * cosLat
            if tz <= 0.02 { continue }
            let px = cx + CGFloat(sxu) * radius
            let py = cy - CGFloat(ty) * radius
            let dotL = sxu * lx + ty * ly + tz * lz
            let half = cellPx * CGFloat(0.55 + 0.45 * tz) * 0.5
            let rect = CGRect(x: px - half, y: py - half, width: 2 * half, height: 2 * half)
            if dotL > 0.55 { pLit.addRect(rect) }
            else if dotL > 0.20 { pMid.addRect(rect) }
            else { pDim.addRect(rect) }
        }
        ctx.fill(pDim, with: .color(cLandDim))
        ctx.fill(pMid, with: .color(cLandMid))
        ctx.fill(pLit, with: .color(cLandLit))

        // Inner limb darkening
        ctx.fill(disc(ctr, radius), with: .radialGradient(
            Gradient(stops: [
                .init(color: .clear, location: 0),
                .init(color: .clear, location: 0.65),
                .init(color: Color.black.opacity(0.30), location: 1.0),
            ]), center: ctr, startRadius: 0, endRadius: radius))

        // Rim stroke
        ctx.stroke(disc(ctr, radius - 0.5), with: .color(cRim.opacity(0.50)), lineWidth: 1.2)

        // Specular highlight (upper-left)
        let specC = CGPoint(x: cx - radius * 0.45, y: cy - radius * 0.55)
        ctx.fill(disc(specC, radius * 0.55), with: .radialGradient(
            Gradient(colors: [Color.white.opacity(0.10), .clear]),
            center: specC, startRadius: 0, endRadius: radius * 0.55))

        // Day/night terminator
        do {
            let sPhi = Double(12) * .pi / 180
            let sLam = (180 - sunSpin) * .pi / 180 - lonRad
            let sCp = cos(sPhi)
            let ux = sCp * sin(sLam), uy = sin(sPhi), uz = sCp * cos(sLam)
            let sxCam = ux
            let syCam = uy * cosLat - uz * sinLat
            let szCam = uy * sinLat + uz * cosLat
            let sunP = CGPoint(x: cx + CGFloat(sxCam) * radius, y: cy - CGFloat(syCam) * radius)
            let antiP = CGPoint(x: cx - CGFloat(sxCam) * radius, y: cy + CGFloat(syCam) * radius)
            ctx.fill(disc(ctr, radius), with: .radialGradient(
                Gradient(stops: [
                    .init(color: rgb(0x02060F, 0.55), location: 0.0),
                    .init(color: rgb(0x02060F, 0.55 * 0.55), location: 0.55),
                    .init(color: .clear, location: 1.0),
                ]), center: antiP, startRadius: 0, endRadius: radius * 1.6))
            if szCam > 0 {
                ctx.fill(disc(ctr, radius), with: .radialGradient(
                    Gradient(colors: [rgb(0xFFE6B0, 0.18 * szCam), .clear]),
                    center: sunP, startRadius: 0, endRadius: radius * 0.7))
            }
        }

        // Camera projection helper (returns nil on the back hemisphere)
        func project(_ latDeg: Double, _ lonDeg: Double) -> CGPoint? {
            let phi = latDeg * .pi / 180
            let lam = lonDeg * .pi / 180 - lonRad
            let cP = cos(phi), sP = sin(phi)
            let sxp = cP * sin(lam), syp = sP, szp = cP * cos(lam)
            let ty = syp * cosLat - szp * sinLat
            let tz = syp * sinLat + szp * cosLat
            if tz < 0 { return nil }
            return CGPoint(x: cx + CGFloat(sxp) * radius, y: cy - CGFloat(ty) * radius)
        }

        // Server dots — all except the selected one (that gets a big pin below)
        for sp in points where sp.id != selectedServerId {
            let phi = sp.lat * .pi / 180
            let lam = sp.lon * .pi / 180 - lonRad
            let cP = cos(phi)
            let sxs = cP * sin(lam), sys = sin(phi), szs = cP * cos(lam)
            let ty = sys * cosLat - szs * sinLat
            let tz = sys * sinLat + szs * cosLat
            if tz <= 0.02 { continue }
            let p = CGPoint(x: cx + CGFloat(sxs) * radius, y: cy - CGFloat(ty) * radius)
            let depth = min(max(tz, 0), 1)
            let a = 0.55 + 0.45 * depth
            let haloR = CGFloat(6.5 + pulse * 1.8)
            dot(&ctx, p, haloR, cAccent.opacity(0.18 * a))
            dot(&ctx, p, haloR * 0.62, cAccent.opacity(0.30 * a))
            dot(&ctx, p, 3.2, cAccent.opacity(0.95 * a))
            dot(&ctx, p, 1.3, Color.white.opacity(0.85 * a))
        }

        // Connection arc + pins
        if let sel = selected {
            let arcColor = isConnected ? cConnected : cAccent
            drawArc(&ctx, cx: cx, cy: cy, radius: radius,
                    cosLat: cosLat, sinLat: sinLat, cosLon: cosLon, sinLon: sinLon,
                    startLat: userLat, startLon: userLon, endLat: sel.lat, endLon: sel.lon,
                    progress: arc, shimmer: arcShimmer, color: arcColor)
            if let up = project(userLat, userLon) {
                drawPin(&ctx, up, isConnected ? cConnected : cAccent, pulse, small: true)
            }
            if let srv = project(sel.lat, sel.lon) {
                drawPin(&ctx, srv, cAccent, pulse, small: false)
            }
        } else if let up = project(userLat, userLon) {
            drawPin(&ctx, up, cAccent.opacity(0.95), pulse, small: true)
        }
    }

    private static func drawPin(_ ctx: inout GraphicsContext, _ center: CGPoint, _ color: Color, _ pulse: Double, small: Bool) {
        let halo = CGFloat((small ? 11.0 : 17.0) + pulse * 5)
        let dsc: CGFloat = small ? 5 : 7
        let inner: CGFloat = small ? 2 : 2.8
        dot(&ctx, center, halo, color.opacity(0.18))
        dot(&ctx, center, halo * 0.62, color.opacity(0.34))
        dot(&ctx, center, dsc, color)
        dot(&ctx, center, inner, .white)
    }

    // swiftlint:disable:next function_parameter_count
    private static func drawArc(
        _ ctx: inout GraphicsContext,
        cx: CGFloat, cy: CGFloat, radius: CGFloat,
        cosLat: Double, sinLat: Double, cosLon: Double, sinLon: Double,
        startLat: Double, startLon: Double, endLat: Double, endLon: Double,
        progress: Double, shimmer: Double, color: Color
    ) {
        if progress <= 0 { return }
        let phi1 = startLat * .pi / 180, lam1 = startLon * .pi / 180
        let phi2 = endLat * .pi / 180, lam2 = endLon * .pi / 180
        let ax = cos(phi1) * sin(lam1), ay = sin(phi1), az = cos(phi1) * cos(lam1)
        let bx = cos(phi2) * sin(lam2), by = sin(phi2), bz = cos(phi2) * cos(lam2)
        let d = min(max(ax * bx + ay * by + az * bz, -1), 1)
        let omega = acos(d)
        if omega < 1e-3 { return }
        let sinO = sin(omega)
        let segments = 64
        let travel = max(Int(Double(segments) * progress), 2)

        var path = Path()
        var pen = false, anyDrawn = false
        var seg = 0
        while seg <= travel {
            let t = Double(seg) / Double(segments)
            let a = sin((1 - t) * omega) / sinO
            let b = sin(t * omega) / sinO
            let lift = 1 + 0.045 * sin(.pi * t)
            let ux = (a * ax + b * bx) * lift
            let uy = (a * ay + b * by) * lift
            let uz = (a * az + b * bz) * lift
            let sx = ux * cosLon - uz * sinLon
            let sz0 = ux * sinLon + uz * cosLon
            let ty = uy * cosLat - sz0 * sinLat
            let tz = uy * sinLat + sz0 * cosLat
            if tz < 0 {
                pen = false
            } else {
                let p = CGPoint(x: cx + CGFloat(sx) * radius, y: cy - CGFloat(ty) * radius)
                if !pen { path.move(to: p); pen = true } else { path.addLine(to: p) }
                anyDrawn = true
            }
            seg += 1
        }
        if !anyDrawn { return }

        ctx.stroke(path, with: .color(color.opacity(0.10)), style: StrokeStyle(lineWidth: 12, lineCap: .round))
        ctx.stroke(path, with: .color(color.opacity(0.22)), style: StrokeStyle(lineWidth: 6, lineCap: .round))
        ctx.stroke(path, with: .color(color.opacity(0.95)), style: StrokeStyle(lineWidth: 2.4, lineCap: .round))

        // Travelling shimmer once the arc is fully drawn
        if progress >= 0.99 {
            let t = min(max(shimmer, 0), 1)
            let a = sin((1 - t) * omega) / sinO
            let b = sin(t * omega) / sinO
            let ux = (a * ax + b * bx) * 1.045, uy = (a * ay + b * by) * 1.045, uz = (a * az + b * bz) * 1.045
            let sx = ux * cosLon - uz * sinLon
            let sz0 = ux * sinLon + uz * cosLon
            let ty = uy * cosLat - sz0 * sinLat
            let tz = uy * sinLat + sz0 * cosLat
            if tz >= 0 {
                let p = CGPoint(x: cx + CGFloat(sx) * radius, y: cy - CGFloat(ty) * radius)
                dot(&ctx, p, 6, color.opacity(0.45))
                dot(&ctx, p, 2.6, Color.white.opacity(0.9))
            }
        }
    }
}
