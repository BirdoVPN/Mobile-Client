import XCTest

/// MR-1796 / MR-2240 -- Dynamic Type. A fixed-size font ignores the user's
/// text size; every size goes through `birdoFont` / the theme tokens
/// (`BirdoScaledFont`, which wraps `@ScaledMetric`) or, for UIKit chrome,
/// `BirdoTheme.scaledUIFont` (`UIFontMetrics`). This guard keeps the 204
/// fixed fonts from coming back: it reads the app's Swift sources and fails
/// on any fixed-size font, any font frozen at one size, and any view that
/// pins the text size of its subtree, outside the two builder lines in
/// BirdoTheme.swift.
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
        var allowed: [String] = []
        var sawTheme = false
        for file in files {
            let text = try String(contentsOf: file, encoding: .utf8)
            if file.lastPathComponent == FixedFontScan.themeFile {
                sawTheme = true
                // Each exempt builder line must be there, exactly once: a stale
                // or duplicated exemption fails rather than hiding something.
                XCTAssertEqual(FixedFontScan.themeExemptionProblems(in: text), [])
            }
            for line in try FixedFontScan.offendingLines(in: text, fileName: file.lastPathComponent) {
                offenders.append("\(file.lastPathComponent):\(line)")
            }
            for line in FixedFontScan.allowedLines(in: text) {
                allowed.append("\(file.lastPathComponent):\(line)")
            }
        }
        XCTAssertTrue(sawTheme, "\(FixedFontScan.themeFile) not found under \(root.path)")
        XCTAssertTrue(offenders.isEmpty,
                      "Use .birdoFont(size, weight:) so the text scales: \(offenders.prefix(10))")
        // MR-2383: every app line the allow marker exempts is named in
        // `FixedFontScan.allowedInApp`, so a new one is a reviewed change to
        // that list. There is none today.
        XCTAssertEqual(allowed.sorted(), FixedFontScan.allowedInApp,
                       "an allow marker in the app must be added to FixedFontScan.allowedInApp")
    }

    /// The scan itself: every kind of offender, planted in an otherwise clean
    /// file, is caught on its own line; scaled fonts, reads of the text size,
    /// comments and marked lines are not.
    func testTheScanCatchesPlantedOffendersAndNothingElse() throws {
        let planted = [
            // Fixed sizes.
            ".font(.system(size: 12))",
            ".font(.system(\n    size: 12, weight: .bold))",
            "UIFont.systemFont(ofSize: 11, weight: .medium)",
            "UIFont.boldSystemFont(ofSize: 11)",
            "NSFont.monospacedSystemFont(ofSize: 11, weight: .regular)",
            "Font.custom(\"Inter\", fixedSize: 12)",
            "Font.custom(fontName(for: style), fixedSize: 12)",
            "Font.custom(names[index(of: style)], fixedSize: size(for: style))",
            "UIFont(name: \"Inter\", size: 12)",
            "UIFont(name: fontName(for: style), size: 12)",
            "UIFont(descriptor: UIFontDescriptor.preferredFontDescriptor(withTextStyle: .body), size: 12)",
            "NSFont(name: \"Inter\", size: 12)",
            "let f = UIFont.preferredFont(forTextStyle: .body).withSize(12)",
            "let f = CTFontCreateWithName(\"Inter\" as CFString, 12, nil)",
            // Frozen at the size in effect when it was made.
            "Text(\"x\").font(Font(UIFont.preferredFont(forTextStyle: .body)))",
            // A subtree pinned to one text size.
            ".dynamicTypeSize(.large)",
            ".dynamicTypeSize(...DynamicTypeSize.accessibility1)",
            ".environment(\\.dynamicTypeSize, .large)",
            ".environment(\\.sizeCategory, .large)",
            // The allow marker needs a reason.
            ".dynamicTypeSize(.large) // scaled-font-guard: allow",
            // MR-2383: the rarer bypasses. A platform font made into a Font
            // is frozen at the size it had, however it is spelled.
            "let f = Font(uiFont)",
            "Text(\"x\").font(Font(theme.titleFont))",
            ".font(.init(UIFont.preferredFont(forTextStyle: .body)))",
            "let f = Font.init(uiFont)",
            ".transformEnvironment(\\.dynamicTypeSize) { $0 = .large }",
            ".transformEnvironment(\\.sizeCategory) { $0 = .large }",
            "UIFontDescriptor(fontAttributes: [.size: 12])",
            "base.addingAttributes([.family: \"Inter\", .size: 12])",
            "base.addingAttributes([UIFontDescriptor.AttributeName.size: 12])",
            "UIFontDescriptor(name: \"Inter\", size: 12)",
            // MR-2383: the marker counts only as the line's trailing comment.
            "Text(\"// scaled-font-guard: allow x\").font(.system(size: 12))",
            ".font(.system(size: 12)) // see below // scaled-font-guard: allow x",
        ]
        for code in planted {
            let lines = try FixedFontScan.offendingLines(in: "import SwiftUI\n\n" + code + "\n", fileName: "Planted.swift")
            XCTAssertEqual(lines, [3], "not caught (once, on line 3): \(code)")
        }

        let clean = [
            ".birdoFont(12, weight: .bold)",
            ".birdoFont(8, weight: .bold, max: 9)",
            ".font(BirdoTheme.Fonts.bodySmall)",
            ".font(.subheadline)",
            "Font.custom(\"Inter\", size: 12, relativeTo: .body)\n    .fixedSize(horizontal: false, vertical: true)",
            "BirdoTheme.tabBarLabelFont(selected: true, compatibleWith: traits)",
            "@Environment(\\.dynamicTypeSize) private var dynamicTypeSize",
            "if dynamicTypeSize.isAccessibilitySize { stacked() }",
            "// was .font(.system(size: 12))",
            "    /// `UIFont(name:size:)` ignores Dynamic Type",
            "X().environment(\\.dynamicTypeSize, .accessibility5) // scaled-font-guard: allow preview at AX5",
            // A string with // in it before the marker is still code.
            "Link(\"x\", destination: URL(string: \"https://birdo.app\")!).dynamicTypeSize(.large) // scaled-font-guard: allow preview",
            // Near misses of the MR-2383 patterns.
            "Text(name).font(font)",
            "let size = proxy.size",
            "items.sorted { $0.size < $1.size }",
            "call(x, .size(12))",
            "@Environment(\\.sizeCategory) private var category",
        ]
        for code in clean {
            XCTAssertEqual(try FixedFontScan.offendingLines(in: code + "\n", fileName: "Clean.swift"), [], code)
        }

        // CRLF and characters outside the BMP before the match keep the line right.
        XCTAssertEqual(try FixedFontScan.offendingLines(in: "// \u{1F310}\r\nlet x = 1\r\n.font(.system(size: 9))\r\n",
                                                        fileName: "Crlf.swift"), [3])
    }

    /// MR-2383: the marker exempts a line only as its trailing comment, with
    /// a reason, after code; `allowedLines` names exactly those lines.
    func testTheAllowMarkerCountsOnlyAsATrailingComment() {
        let marked = ".dynamicTypeSize(.large) // scaled-font-guard: allow preview"
        XCTAssertTrue(FixedFontScan.isAllowed(marked))
        XCTAssertTrue(FixedFontScan.isAllowed("Text(\"a \\\" b\").dynamicTypeSize(.large) // scaled-font-guard: allow preview"),
                      "an escaped quote does not end the string")
        XCTAssertFalse(FixedFontScan.isAllowed(".dynamicTypeSize(.large) // scaled-font-guard: allow"), "no reason")
        XCTAssertFalse(FixedFontScan.isAllowed("Text(\"// scaled-font-guard: allow x\").dynamicTypeSize(.large)"),
                       "inside a string literal")
        XCTAssertFalse(FixedFontScan.isAllowed(".dynamicTypeSize(.large) // why // scaled-font-guard: allow x"),
                       "after an earlier comment")
        XCTAssertFalse(FixedFontScan.isAllowed("    // scaled-font-guard: allow x"), "a whole-line comment exempts nothing")

        let text = ["import SwiftUI",
                    marked,
                    "// scaled-font-guard: allow x",
                    "Text(\"// scaled-font-guard: allow x\")",
                    "X().environment(\\.sizeCategory, .large) // scaled-font-guard: allow test"].joined(separator: "\n")
        XCTAssertEqual(FixedFontScan.allowedLines(in: text), [2, 5])
        XCTAssertEqual(FixedFontScan.allowedInApp, [], "no app line is exempt today")
    }

    /// The exemption is the two builder LINES, in BirdoTheme.swift only, each
    /// exactly once; anything else there, or those lines elsewhere, is caught.
    func testTheThemeExemptionIsTheExactBuilderLinesOnce() throws {
        let swiftUI = "        " + FixedFontScan.themeBuilderLines[0]
        let uiKit = "            " + FixedFontScan.themeBuilderLines[1]
        let theme = FixedFontScan.themeFile

        XCTAssertEqual(try FixedFontScan.offendingLines(in: swiftUI + "\n" + uiKit, fileName: theme), [])
        XCTAssertEqual(try FixedFontScan.offendingLines(in: swiftUI, fileName: "HomeView.swift"), [1])
        XCTAssertEqual(try FixedFontScan.offendingLines(in: uiKit, fileName: "HomeView.swift"), [1])
        // A near copy of the builder is not the builder.
        XCTAssertEqual(try FixedFontScan.offendingLines(
            in: "        return .system(size: scaledSize, weight: .bold, design: design)", fileName: theme), [1])
        XCTAssertEqual(try FixedFontScan.offendingLines(in: "a\n.font(.system(size: 12))", fileName: theme), [2])

        XCTAssertEqual(FixedFontScan.themeExemptionProblems(in: swiftUI + "\n" + uiKit), [])
        XCTAssertEqual(FixedFontScan.themeExemptionProblems(in: swiftUI + "\n" + swiftUI + "\n" + uiKit).count, 1)
        XCTAssertEqual(FixedFontScan.themeExemptionProblems(in: swiftUI).count, 1)
    }
}

/// What counts as a fixed-size font, and where the only exemptions are.
enum FixedFontScan {
    static let themeFile = "BirdoTheme.swift"

    /// The ONLY exemptions: these exact lines (ignoring indentation), each
    /// exactly once, in BirdoTheme.swift. The SwiftUI builder
    /// (`BirdoFontToken.font(scaled:)`, fed by @ScaledMetric) and the UIKit
    /// one (`BirdoTheme.scaledUIFont`, UIFontMetrics).
    static let themeBuilderLines = [
        "return .system(size: scaledSize, weight: weight, design: design)",
        ".scaledFont(for: .systemFont(ofSize: size, weight: weight),",
    ]

    /// A line carrying this marker and a reason is not scanned: for a preview
    /// or test that pins a text size on purpose. MR-2383: only as the line's
    /// trailing comment, after code (`isAllowed`).
    static let allowMarker = "// scaled-font-guard: allow"

    /// MR-2383: every app line the marker exempts, as "File.swift:line". The
    /// guard asserts the app's against this list, so each one is reviewed.
    static let allowedInApp: [String] = []

    /// The arguments of one call: anything up to its closing parenthesis,
    /// with nested calls two levels deep.
    private static let callArgs = #"(?:[^()]|\((?:[^()]|\([^()]*\))*\))*?"#

    /// Multi-line patterns over a whole file.
    static let patterns = [
        #"system\(\s*size:"#,                                            // Font.system(size:)
        #"ofSize:"#,                                                     // UIFont/NSFont .systemFont(ofSize:) and kin
        #"\.custom\("# + callArgs + #"\bfixedSize:"#,                    // Font.custom(_:fixedSize:)
        #"\b(?:UI|NS)Font\(\s*(?:name|descriptor):"# + callArgs + #"\bsize:"#, // UIFont(name:size:), (descriptor:size:)
        #"\.withSize\("#,                                                // UIFont/descriptor .withSize(_:)
        #"\bCTFontCreateWith\w*\("#,                                     // Core Text at a point size
        #"\bFont\(\s*(?:UI|NS)Font\b"#,                                  // Font(UIFont...): a snapshot
        #"\.dynamicTypeSize\("#,                                         // pins or limits a subtree
        #"\.environment\(\s*\\\.(?:dynamicTypeSize|sizeCategory)\b"#,    // pins a subtree
        // MR-2383: the rarer bypasses.
        #"\bFont\(\s*[a-z_][\w.]*\s*\)"#,                                // Font(uiFont): a snapshot
        #"\.font\(\s*\.init\("#,                                         // .font(.init(UIFont...))
        #"\bFont\.init\("#,                                              // Font.init(...)
        #"\.transformEnvironment\(\s*\\\.(?:dynamicTypeSize|sizeCategory)\b"#, // rewrites a subtree's size
        #"\bAttributeName\.size\b"#,                                     // UIFontDescriptor size attribute
        #"[\[,]\s*\.size\s*:"#,                                          // [.size: 12] in font attributes
        #"\b(?:UI|NS)FontDescriptor\(\s*name:"# + callArgs + #"\bsize:"#, // UIFontDescriptor(name:size:)
    ]

    /// 1-based line numbers of every offender in one file's text. Whole-line
    /// `//` comments, marked lines and (in the theme file) the builder lines
    /// are blanked first; lines keep their place. Split as NSString (UTF-16),
    /// so a CRLF file still splits at each LF.
    static func offendingLines(in text: String, fileName: String) throws -> [Int] {
        let isTheme = fileName == themeFile
        let code = (text as NSString).components(separatedBy: "\n")
            .map { line -> String in
                let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
                if trimmed.hasPrefix("//") { return "" }
                if isTheme && themeBuilderLines.contains(trimmed) { return "" }
                if isAllowed(line) { return "" }
                return line
            }
            .joined(separator: "\n")
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

    /// MR-2383: the marker exempts a line only as its trailing `//` comment,
    /// with a reason, after code: never inside a string literal (where it
    /// would hide the very call it sits in) and never after an earlier `//`.
    /// A whole-line comment is blanked anyway and exempts nothing else.
    static func isAllowed(_ line: String) -> Bool {
        guard let marker = line.range(of: allowMarker) else { return false }
        let reason = line[marker.upperBound...].trimmingCharacters(in: .whitespacesAndNewlines)
        guard !reason.isEmpty else { return false }
        let code = line[..<marker.lowerBound]
        guard !code.trimmingCharacters(in: .whitespaces).isEmpty else { return false }
        var inString = false
        var escaped = false
        var previous: Character?
        for character in code {
            if inString {
                if escaped {
                    escaped = false
                } else if character == "\\" {
                    escaped = true
                } else if character == "\"" {
                    inString = false
                }
                previous = nil
            } else {
                if character == "\"" {
                    inString = true
                } else if character == "/" && previous == "/" {
                    return false
                }
                previous = character
            }
        }
        return !inString
    }

    /// 1-based numbers of the lines the marker exempts (`isAllowed`).
    static func allowedLines(in text: String) -> [Int] {
        (text as NSString).components(separatedBy: "\n").enumerated().compactMap { index, line in
            let trimmed = line.trimmingCharacters(in: .whitespacesAndNewlines)
            return !trimmed.hasPrefix("//") && isAllowed(line) ? index + 1 : nil
        }
    }

    /// Each builder line must appear in the theme exactly once.
    static func themeExemptionProblems(in themeText: String) -> [String] {
        let trimmed = (themeText as NSString).components(separatedBy: "\n")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
        return themeBuilderLines.compactMap { builder in
            let count = trimmed.filter { $0 == builder }.count
            return count == 1 ? nil : "\(builder) appears \(count) times"
        }
    }
}
