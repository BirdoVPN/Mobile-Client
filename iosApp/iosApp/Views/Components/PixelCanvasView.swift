import SwiftUI

/// The signature ambient background — a full-bleed grid of faint white squares
/// on pure black that gently twinkles and lights up under a finger.
/// Port of the desktop `PixelCanvas.tsx`; spec: spec-pixelcanvas-design.md §2
/// (grid §2.1, cell state §2.2, 20fps tick §2.3, power management §2.4).
///
/// Placement (spec §2.5): ONE `PixelCanvasView` at the app root ZStack with
/// content above it; tab-root screens keep transparent backgrounds so the grid
/// shows through; pushed sub-screens use an opaque black base + their OWN
/// canvas so the slide-in fully occludes the tab behind it.
///
/// Simulation state lives in a reference type mutated inside the Canvas draw
/// closure — deliberate and safe here because redraws are driven by the
/// TimelineView clock, not state invalidation (spec warning 3).
@MainActor
final class PixelGridModel: ObservableObject {
    struct Cell {
        var alpha: Double       // start: random(0...0.08)
        var target: Double      // start: 0
        var speed: Double       // random(0.002...0.006) — alpha change PER TICK
        var hoverDecay: Double  // touch-trail energy, 0...1
    }

    /// True when the sim is parked (settled + no touch). Published so the
    /// TimelineView's `paused:` re-evaluates — a frozen frame is visually
    /// indistinguishable, and a VPN app sits open for days (spec §2.4).
    @Published fileprivate(set) var parked = false

    // Simulation state — only touched on the main thread (Canvas renders on
    // main; gestures and scene-phase callbacks are MainActor).
    fileprivate var cells: [Cell] = []
    fileprivate var cols = 0
    fileprivate var rows = 0
    fileprivate var pixel: CGFloat = 15
    fileprivate var lastSize: CGSize = .zero
    fileprivate var lastTickAt: TimeInterval = 0
    fileprivate var parkUpdateQueued = false
    /// Current touch point in canvas coordinates; nil = no finger down.
    var touch: CGPoint? {
        didSet { if touch != nil && parked { parked = false } }
    }

    /// Perf cap — pixel size grows so the grid never exceeds this many cells.
    private static let maxCells: CGFloat = 2000

    nonisolated init() {}

    /// Un-park the sim (touch, foreground, appear). Safe to call repeatedly.
    func wake() {
        if parked { parked = false }
    }

    /// §2.1 — rebuild grid geometry for a new canvas size.
    fileprivate func rebuild(for size: CGSize) {
        lastSize = size
        guard size.width > 1, size.height > 1 else {
            cells = []; cols = 0; rows = 0
            return
        }
        var px = min(max(size.width / 80, 15), 25)
        if (size.width / px).rounded(.up) * (size.height / px).rounded(.up) > Self.maxCells {
            px = (size.width * size.height / Self.maxCells).squareRoot()
        }
        pixel = px
        cols = Int((size.width / px).rounded(.up))
        rows = Int((size.height / px).rounded(.up))
        cells = (0..<(cols * rows)).map { _ in
            Cell(alpha: .random(in: 0...0.08),
                 target: 0,
                 speed: .random(in: 0.002...0.006),
                 hoverDecay: 0)
        }
        // rebuild() runs inside the render pass — never publish from here.
        if parked {
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated { self?.wake() }
            }
        }
    }

    /// §2.3 — one 50ms tick. Constants are PER-TICK at 20fps; the internal
    /// 45ms gate keeps the rates correct even if the timeline fires faster
    /// (spec warning 2).
    fileprivate func tick() {
        let now = Date.timeIntervalSinceReferenceDate
        guard now - lastTickAt >= 0.045 else { return }
        lastTickAt = now

        let p = touch ?? CGPoint(x: -1000, y: -1000)
        var anyActive = false
        for i in cells.indices {
            var c = cells[i]
            // 1. Touch trail: light up within 60pt, fade out over ~12.5s.
            let cx = (CGFloat(i % cols) + 0.5) * pixel
            let cy = (CGFloat(i / cols) + 0.5) * pixel
            let dx = p.x - cx, dy = p.y - cy
            if dx * dx + dy * dy < 3600 {
                c.hoverDecay = min(1.0, c.hoverDecay + 0.08)
            } else if c.hoverDecay > 0 {
                c.hoverDecay = max(0, c.hoverDecay - 0.004)
            }
            // 2. Twinkle: p = 0.001 per cell per tick.
            if Double.random(in: 0..<1) < 0.001 {
                c.target = .random(in: 0...0.15)
            }
            // 3. Chase target linearly, clamping onto it.
            if c.alpha < c.target {
                c.alpha = min(c.target, c.alpha + c.speed)
            } else if c.alpha > c.target {
                c.alpha = max(c.target, c.alpha - c.speed)
            }
            if c.alpha != c.target || c.hoverDecay > 0 { anyActive = true }
            cells[i] = c
        }

        // §2.4 — park when fully settled. Deferred off the render pass so we
        // never publish during a view update.
        let shouldPark = !anyActive && touch == nil
        if shouldPark != parked && !parkUpdateQueued {
            parkUpdateQueued = true
            DispatchQueue.main.async { [weak self] in
                MainActor.assumeIsolated {
                    guard let self else { return }
                    self.parkUpdateQueued = false
                    let settledNow = self.touch == nil && !self.cells.contains {
                        $0.alpha != $0.target || $0.hoverDecay > 0
                    }
                    self.parked = settledNow
                }
            }
        }
    }
}

struct PixelCanvasView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @StateObject private var model: PixelGridModel

    /// Pass a model when the host screen wants the touch trail (attach
    /// `.pixelCanvasTouchTrail(model)` to the ROOT container); omit it for a
    /// twinkle-only background. The first model passed is retained for the
    /// view's lifetime — do not swap models between renders.
    init(model: PixelGridModel? = nil) {
        _model = StateObject(wrappedValue: model ?? PixelGridModel())
    }

    private var isPaused: Bool {
        reduceMotion || model.parked || scenePhase != .active
    }

    var body: some View {
        // .animation(minimumInterval:) never fires faster than 20fps; the
        // model's own 45ms gate keeps per-tick constants honest (FRAME_MS=50).
        TimelineView(.animation(minimumInterval: 0.05, paused: isPaused)) { _ in
            Canvas { ctx, size in
                let m = model
                if size != m.lastSize { m.rebuild(for: size) }
                if !reduceMotion { m.tick() }
                let px = m.pixel
                for r in 0..<m.rows {
                    for c in 0..<m.cols {
                        let cell = m.cells[r * m.cols + c]
                        let a = min(0.25, cell.alpha + cell.hoverDecay * 0.2)
                        guard a > 0.004 else { continue }
                        ctx.fill(
                            Path(CGRect(x: CGFloat(c) * px, y: CGFloat(r) * px,
                                        width: px - 1, height: px - 1)),
                            with: .color(.white.opacity(a))
                        )
                    }
                }
            }
        }
        .background(BirdoTheme.black)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.wake() }
        }
    }
}

extension View {
    /// Feeds a finger-drag trail into a `PixelGridModel`. Attach to the ROOT
    /// container of a screen (the canvas itself is hit-test disabled);
    /// `simultaneousGesture` keeps buttons and scrolling working. Only enable
    /// on gesture-light screens (Login/Consent) — spec §2.5.
    @MainActor
    func pixelCanvasTouchTrail(_ model: PixelGridModel) -> some View {
        simultaneousGesture(
            DragGesture(minimumDistance: 0)
                .onChanged { value in
                    model.touch = value.location
                }
                .onEnded { _ in
                    model.touch = nil
                }
        )
    }
}
