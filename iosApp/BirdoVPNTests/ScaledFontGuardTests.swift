import XCTest

/// MR-1796 / MR-2240 -- Dynamic Type. A fixed-size font ignores the user's
/// text size; every size goes through `birdoFont` / the theme tokens
/// (`BirdoScaledFont`, which wraps `@ScaledMetric`) or, for UIKit chrome,
/// `BirdoTheme.scaledUIFont` (`UIFontMetrics`). This guard keeps the 204
/// fixed fonts from coming back: it reads the app's Swift sources and fails
/// on any fixed-size font outside the two builders in BirdoTheme.swift.
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

        var offenders: [String] = []
        var sawTheme = false
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            if file.lastPathComponent == FixedFontScan.themeFile {
                sawTheme = true
                // An exemption that no longer matches the theme is stale: fail
                // rather than let it quietly exempt something else later.
                for builder in FixedFontScan.themeBuilders {
                    XCTAssertTrue(text.contains(builder), "stale exemption: \(builder)")
                }
            }
            for line in try FixedFontScan.offendingLines(in: text, fileName: file.lastPathComponent) {
                offenders.append("\(file.lastPathComponent):\(line)")
            }
        }
        XCTAssertTrue(sawTheme, "\(FixedFontScan.themeFile) not found under \(root.path)")
        XCTAssertTrue(offenders.isEmpty,
                      "Use .birdoFont(size, weight:) so the text scales: \(offenders.prefix(10))")
    }

    /// The scan itself: it must catch every kind of fixed font, planted in an
    /// otherwise clean file, on the right line, and must not catch scaled
    /// fonts or a whole-line comment that only names one.
    func testTheScanCatchesPlantedOffendersAndNothingElse() throws {
        let planted = [
            ".font(.system(size: 12))",
            ".font(.system(\n    size: 12, weight: .bold))",
            "UIFont.systemFont(ofSize: 11, weight: .medium)",
            "UIFont.boldSystemFont(ofSize: 11)",
            "NSFont.monospacedSystemFont(ofSize: 11, weight: .regular)",
            "Font.custom(\"Inter\", fixedSize: 12)",
            "UIFont(name: \"Inter\", size: 12)",
            "UIFont(name: fontName(for: style), size: 12)",
            "UIFont(descriptor: UIFontDescriptor.preferredFontDescriptor(withTextStyle: .body), size: 12)",
            "NSFont(name: \"Inter\", size: 12)",
        ]
        for code in planted {
            let lines = try FixedFontScan.offendingLines(in: "import SwiftUI\n\n" + code + "\n", fileName: "Planted.swift")
            XCTAssertEqual(lines, [3], "not caught on line 3: \(code)")
        }

        let clean = [
            ".birdoFont(12, weight: .bold)",
            ".birdoFont(8, weight: .bold, max: 9)",
            ".font(BirdoTheme.Fonts.bodySmall)",
            ".font(.subheadline)",
            "Font.custom(\"Inter\", size: 12, relativeTo: .body)",
            "BirdoTheme.tabBarLabelFont(selected: true)",
            "// was .font(.system(size: 12))",
            "    /// `UIFont(name:size:)` ignores Dynamic Type",
        ]
        for code in clean {
            XCTAssertEqual(try FixedFontScan.offendingLines(in: code + "\n", fileName: "Clean.swift"), [], code)
        }

        // The builders are exempt in the theme file only, and nothing else is.
        let builder = "        return .system(size: scaledSize, weight: weight, design: design)"
        XCTAssertEqual(try FixedFontScan.offendingLines(in: builder, fileName: FixedFontScan.themeFile), [])
        XCTAssertEqual(try FixedFontScan.offendingLines(in: builder, fileName: "HomeView.swift"), [1])
        XCTAssertEqual(try FixedFontScan.offendingLines(in: "a\n.font(.system(size: 12))", fileName: FixedFontScan.themeFile), [2])

        // CRLF and characters outside the BMP before the match keep the line right.
        XCTAssertEqual(try FixedFontScan.offendingLines(in: "// \u{1F310}\r\nlet x = 1\r\n.font(.system(size: 9))\r\n",
                                                        fileName: "Crlf.swift"), [3])
    }
}

/// What counts as a fixed-size font, and where the only exemptions are.
enum FixedFontScan {
    static let themeFile = "BirdoTheme.swift"

    /// The ONLY exemptions, removed from BirdoTheme.swift's text before
    /// matching (not the whole file): the SwiftUI builder
    /// (`BirdoFontToken.font(scaled:)`, fed by @ScaledMetric) and the UIKit
    /// one for the tab bar (`BirdoTheme.scaledUIFont`, UIFontMetrics).
    static let themeBuilders = ["system(size: scaledSize", "scaledFont(for: .systemFont(ofSize: size,"]

    /// Multi-line patterns over a whole file.
    static let patterns = [
        #"system\(\s*size:"#,                                   // Font.system(size:)
        #"ofSize:"#,                                            // UIFont/NSFont .systemFont(ofSize:) and kin
        #"\.custom\([^)]*fixedSize:"#,                          // Font.custom(_:fixedSize:)
        #"\b(?:UI|NS)Font\(\s*(?:name|descriptor):.{0,200}?\bsize:"#, // UIFont(name:size:), UIFont(descriptor:size:)
    ]

    /// 1-based line numbers of every fixed-size font in one file's text.
    /// Whole-line `//` comments are blanked first (lines keep their place).
    /// Split as NSString (UTF-16), so a CRLF file still splits at each LF.
    static func offendingLines(in text: String, fileName: String) throws -> [Int] {
        var code = (text as NSString).components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespaces).hasPrefix("//") ? "" : $0 }
            .joined(separator: "\n")
        if fileName == themeFile {
            for builder in themeBuilders {
                code = code.replacingOccurrences(of: builder, with: "")
            }
        }
        let ns = code as NSString
        var lines: [Int] = []
        for pattern in patterns {
            let re = try NSRegularExpression(pattern: pattern, options: [.dotMatchesLineSeparators])
            for m in re.matches(in: code, range: NSRange(location: 0, length: ns.length)) {
                let before = ns.substring(to: m.range.location)
                lines.append(before.unicodeScalars.filter { $0 == "\n" }.count + 1)
            }
        }
        return lines.sorted()
    }
}
