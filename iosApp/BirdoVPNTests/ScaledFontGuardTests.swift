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
            .filter { $0.pathExtension == "swift" } ?? []
        XCTAssertFalse(files.isEmpty)

        // Whole-file, multi-line patterns: `.system(size:`, `systemFont(ofSize:`,
        // any `ofSize:` and a `.custom(... fixedSize:)`. The ONE exemption is the
        // builder in BirdoTheme.swift (`system(size: scaledSize`), removed from
        // that file's text before matching -- not the whole file.
        let patterns = [#"system\(\s*size:"#, #"ofSize:"#, #"\.custom\([^)]*fixedSize:"#]
        let regexes = try patterns.map { try NSRegularExpression(pattern: $0, options: [.dotMatchesLineSeparators]) }
        var offenders: [String] = []
        for file in files {
            var text = try String(contentsOf: file, encoding: .utf8)
            if file.lastPathComponent == "BirdoTheme.swift" {
                text = text.replacingOccurrences(of: "system(size: scaledSize", with: "")
            }
            let range = NSRange(text.startIndex..., in: text)
            for re in regexes {
                for m in re.matches(in: text, range: range) {
                    let line = text.prefix(m.range.location).filter { $0 == "
" }.count + 1
                    offenders.append("\(file.lastPathComponent):\(line)")
                }
            }
        }
        XCTAssertTrue(offenders.isEmpty,
                      "Use .birdoFont(size, weight:) so the text scales: \(offenders.prefix(10))")
    }
}
