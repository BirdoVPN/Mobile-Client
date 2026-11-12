import XCTest

/// Guards the App Store Guideline 4 fix (macOS 1.4.23 rejected: "windows laid
/// out and partially hidden under menu bar").
///
/// `WindowFrameClamp.clamped` is the arithmetic behind
/// `MacWindow.clampToVisibleFrame` (BirdoVPNApp.swift). It is CoreGraphics-only
/// and compiled straight into this un-hosted bundle, so it runs on the iOS
/// Simulator with the rest of the suite. These tests pin the arithmetic only:
/// which windows it is applied to, and when, is AppKit and needs a real Mac.
final class WindowFrameClampTests: XCTestCase {

    /// A 1440x900 screen with a 32pt menu bar and a 70pt Dock, in AppKit's
    /// coordinates: y grows up, so the top of this rectangle (868) is the
    /// bottom of the menu bar and its bottom (70) is the top of the Dock.
    private let visible = CGRect(x: 0, y: 70, width: 1440, height: 798)

    // MARK: - Above the visible top: the rejection itself

    func testFrameAboveTheVisibleTopMovesBelowTheMenuBar() {
        // Top edge at 1060, 192pt above the bottom of the menu bar.
        let frame = CGRect(x: 200, y: 300, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible),
                       CGRect(x: 200, y: 108, width: 480, height: 760))
    }

    func testClampedTopNeverPassesTheVisibleTop() {
        let frame = CGRect(x: 200, y: 5000, width: 480, height: 760)
        XCTAssertLessThanOrEqual(WindowFrameClamp.clamped(frame, into: visible).maxY, visible.maxY)
    }

    // MARK: - Off to the side, and below

    func testFrameOffTheRightEdgeMovesBackOntoTheScreen() {
        let frame = CGRect(x: 1200, y: 100, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible),
                       CGRect(x: 960, y: 100, width: 480, height: 760))
    }

    func testFrameOffTheLeftEdgeMovesBackOntoTheScreen() {
        let frame = CGRect(x: -300, y: 100, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible),
                       CGRect(x: 0, y: 100, width: 480, height: 760))
    }

    func testFrameBelowTheVisibleBottomMovesAboveTheDock() {
        let frame = CGRect(x: 200, y: -200, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible),
                       CGRect(x: 200, y: 70, width: 480, height: 760))
    }

    // MARK: - Larger than the screen

    func testFrameLargerThanTheScreenBecomesTheVisibleFrame() {
        let frame = CGRect(x: -50, y: 0, width: 2000, height: 1200)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible), visible)
    }

    func testOnlyTheAxisThatDoesNotFitShrinks() {
        let frame = CGRect(x: 300, y: 100, width: 480, height: 1000)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible),
                       CGRect(x: 300, y: 70, width: 480, height: 798))
    }

    // MARK: - Already inside

    func testFrameAlreadyInsideIsUnchanged() {
        let frame = CGRect(x: 300, y: 100, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible), frame)
    }

    /// Touching the top and right edges is inside. `MacWindow` skips
    /// `setFrame` when nothing changed, so this is also what stops a window
    /// that already fits being moved at all.
    func testFrameTouchingTheEdgesIsUnchanged() {
        let frame = CGRect(x: 960, y: 108, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: visible), frame)
    }

    func testClampingTwiceChangesNothingMore() {
        let frames = [
            CGRect(x: 200, y: 300, width: 480, height: 760),
            CGRect(x: 1200, y: 100, width: 480, height: 760),
            CGRect(x: -50, y: 0, width: 2000, height: 1200),
        ]
        for frame in frames {
            let once = WindowFrameClamp.clamped(frame, into: visible)
            XCTAssertEqual(WindowFrameClamp.clamped(once, into: visible), once)
        }
    }

    // MARK: - A second display

    /// A display to the left of and above the main one has a negative x and a
    /// y beyond the main screen's height, so nothing here may assume an origin
    /// at zero. `MacWindow` passes each window its own screen's visible frame.
    func testSecondDisplayIsClampedAgainstItsOwnFrame() {
        let second = CGRect(x: -1920, y: 900, width: 1920, height: 1055)
        let frame = CGRect(x: -1800, y: 1200, width: 480, height: 760)
        XCTAssertEqual(WindowFrameClamp.clamped(frame, into: second),
                       CGRect(x: -1800, y: 1195, width: 480, height: 760))
    }
}
