import XCTest

/// MR-2244 [MAC-SECOND-WINDOW]: on the Mac a `birdo://` link goes to the window
/// that is already open, and never opens a second one.
///
/// Which window SwiftUI hands an external URL to is decided by the scene
/// runtime on a real Mac, and this bundle is un-hosted and runs on the iOS
/// Simulator, so the wiring is pinned in the source instead: the WindowGroup's
/// content claims every URL (`handlesExternalEvents(preferring:allowing:)`
/// with "*" on both sides) on macOS, and the deep-link handler it hands them
/// to (`.onOpenURL` in ContentView) is still there. Before the fix SwiftUI
/// opened a new window for each `birdo://` URL, because no open scene claimed
/// it.
final class MacSingleWindowTests: XCTestCase {

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/MacSingleWindowTests.swift -> .../iosApp
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

    private let claimsEveryURL = #".handlesExternalEvents(preferring: ["*"], allowing: ["*"])"#

    /// The window that is open takes every URL, on macOS only.
    func testTheOpenWindowClaimsEveryBirdoURLOnTheMac() throws {
        let app = try source("iosApp/BirdoVPNApp.swift")
        // The WindowGroup's content: from the scene up to its own modifiers.
        let content = slice(app, from: "WindowGroup {", to: ".defaultSize(")
        guard let claim = content.range(of: claimsEveryURL) else {
            return XCTFail("the WindowGroup's content claims no URL: macOS opens a second window for a birdo:// link")
        }
        // Inside an `#if os(macOS)` block: iOS is left as it was.
        guard let macOnly = content.range(of: "#if os(macOS)", options: .backwards,
                                          range: content.startIndex..<claim.lowerBound) else {
            return XCTFail("the URL claim is not inside #if os(macOS)")
        }
        XCTAssertFalse(content[macOnly.upperBound..<claim.lowerBound].contains("#endif"),
                       "the URL claim is not inside #if os(macOS)")
        XCTAssertEqual(app.components(separatedBy: claimsEveryURL).count - 1, 1,
                       "one claim, on the one WindowGroup")
    }

    /// The scene itself is not narrowed: with no window open (the app keeps
    /// running after its window closes) SwiftUI must still open one for the
    /// URL, or the link would be dropped.
    func testTheSceneStillOpensAWindowWhenNoneIsOpen() throws {
        let app = try source("iosApp/BirdoVPNApp.swift")
        XCTAssertFalse(app.contains(".handlesExternalEvents(matching:"),
                       "the scene's own URL matching was narrowed")
        XCTAssertTrue(app.contains("WindowGroup {"))
    }

    /// The claimed URL reaches the deep-link handler in that window.
    func testTheDeepLinkHandlerIsInTheWindowContent() throws {
        let view = try source("iosApp/ContentView.swift")
        let handler = slice(view, from: ".onOpenURL { url in", to: "default: break")
        XCTAssertTrue(handler.contains(#"url.scheme?.lowercased() == "birdo""#))
    }
}
