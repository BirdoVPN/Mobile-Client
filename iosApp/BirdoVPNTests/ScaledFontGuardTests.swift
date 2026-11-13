import XCTest

/// MR-1796 / MR-2240 -- Dynamic Type. A fixed `.font(.system(size:))` ignores
/// the user's text size; every size goes through `birdoFont` / the theme tokens
/// (`BirdoScaledFont`, which wraps `@ScaledMetric`). This guard keeps the 204
/// fixed fonts from coming back: it reads the app's Swift sources and fails on
/// any `.font(.system(size:` outside the one place that builds the scaled font.
final class ScaledFontGuardTests: XCTestCase {

    func testNoFixedSystemSizeFontsInTheApp() throws {
        // .../iosApp/BirdoVPNTests/ThisFile.swift -> .../iosApp/iosApp
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("iosApp", isDirectory: true)
        guard FileManager.default.fileExists(atPath: root.path) else {
            throw XCTSkip("app sources not on disk next to the test bundle")
        }
        let files = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil)?
            .compactMap { $0 as? URL }
            .filter { $0.pathExtension == "swift" && $0.lastPathComponent != "BirdoTheme.swift" } ?? []
        XCTAssertFalse(files.isEmpty)

        var offenders: [String] = []
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            for (i, line) in text.components(separatedBy: "\n").enumerated()
            where line.contains(".font(.system(size:") {
                offenders.append("\(file.lastPathComponent):\(i + 1)")
            }
        }
        XCTAssertTrue(offenders.isEmpty,
                      "Use .birdoFont(size, weight:) so the text scales: \(offenders.prefix(10))")
    }
}
