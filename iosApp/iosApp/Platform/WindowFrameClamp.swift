import CoreGraphics

/// Where a window may sit on a screen.
///
/// Compiled on every platform, though only the macOS app calls it, so the unit
/// tests reach it: CI runs `BirdoVPNTests` on the iOS Simulator and never on a
/// macOS destination, where a test of anything inside `#if os(macOS)` would
/// compile to nothing. AppKit stays in `MacWindow` (BirdoVPNApp.swift); this is
/// only the arithmetic.
enum WindowFrameClamp {
    /// `frame` pulled fully inside `visible`: moved no further than it has to
    /// be, and shrunk only along an axis where it cannot fit at all. A frame
    /// already inside comes back unchanged.
    ///
    /// Both rectangles are in AppKit's screen coordinates, where y grows UP, so
    /// `maxY` is the top edge. Keeping a frame's top at or below `visible.maxY`
    /// is what keeps the title bar CLEAR of the menu bar -- an earlier wording
    /// of this comment said the opposite, and described the defect as though it
    /// were the goal.
    static func clamped(_ frame: CGRect, into visible: CGRect) -> CGRect {
        let width = min(frame.width, visible.width)
        let height = min(frame.height, visible.height)
        return CGRect(x: min(max(frame.minX, visible.minX), visible.maxX - width),
                      y: min(max(frame.minY, visible.minY), visible.maxY - height),
                      width: width,
                      height: height)
    }
}
