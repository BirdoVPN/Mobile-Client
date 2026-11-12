import XCTest
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// A-M38 (MA-IOS-006): the theme's font tokens follow Dynamic Type and are
/// exactly the old fixed point sizes at the default `.large` category.
@MainActor
final class DynamicTypeTests: XCTestCase {

    /// Every named token with the point size it had before (`.system(size:)`).
    private let named: [(String, BirdoFontToken, CGFloat)] = [
        ("displayLarge", BirdoTheme.Fonts.displayLarge, 36),
        ("displayMedium", BirdoTheme.Fonts.displayMedium, 30),
        ("headlineLarge", BirdoTheme.Fonts.headlineLarge, 26),
        ("headlineMedium", BirdoTheme.Fonts.headlineMedium, 20),
        ("titleLarge", BirdoTheme.Fonts.titleLarge, 18),
        ("titleMedium", BirdoTheme.Fonts.titleMedium, 16),
        ("titleSmall", BirdoTheme.Fonts.titleSmall, 14),
        ("bodyLarge", BirdoTheme.Fonts.bodyLarge, 16),
        ("bodyMedium", BirdoTheme.Fonts.bodyMedium, 14),
        ("bodySmall", BirdoTheme.Fonts.bodySmall, 12),
        ("labelLarge", BirdoTheme.Fonts.labelLarge, 14),
        ("labelMedium", BirdoTheme.Fonts.labelMedium, 12),
        ("labelSmall", BirdoTheme.Fonts.labelSmall, 10),
        ("sectionHeader", BirdoTheme.Fonts.sectionHeader, 11),
        ("statValue", BirdoTheme.Fonts.statValue, 12),
        ("statCaption", BirdoTheme.Fonts.statCaption, 9),
        ("mono", BirdoTheme.Fonts.mono, 14),
    ]

    func testTokensKeepTheirOriginalPointSizes() {
        for (name, token, size) in named {
            XCTAssertEqual(token.size, size, "\(name) must stay \(size)pt at the default size")
        }
    }

    func testEveryOneOffSizeMapsToATextStyle() {
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 8), .caption2)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 12), .caption)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 13), .footnote)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 16), .callout)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 17), .body)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 20), .title3)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 22), .title2)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 28), .title)
        XCTAssertEqual(BirdoFontToken.nearestTextStyle(for: 36), .largeTitle)
    }

    #if canImport(UIKit)
    /// Rendered height of one line of text under a Dynamic Type override.
    private func height<V: View>(_ view: V, _ size: DynamicTypeSize) -> CGFloat {
        let host = UIHostingController(rootView: view.environment(\.dynamicTypeSize, size))
        return host.sizeThatFits(in: CGSize(width: 1000, height: 10_000)).height
    }

    func testTokenMatchesTheOldFixedFontAtLarge() {
        for (name, token, size) in named {
            let scaled = height(Text("Hg").font(token), .large)
            let old = height(Text("Hg").font(.system(size: size, weight: token.weight, design: token.design)), .large)
            XCTAssertEqual(scaled, old, accuracy: 0.5, "\(name) must render as before at .large")
        }
    }

    func testTokensGrowAtAccessibilitySizes() {
        for (name, token, _) in named {
            let large = height(Text("Hg").font(token), .large)
            let big = height(Text("Hg").font(token), .accessibility3)
            XCTAssertGreaterThan(big, large * 1.3, "\(name) must grow at .accessibility3")
        }
    }

    func testOneOffModifierGrowsToo() {
        let large = height(Text("Hg").birdoFont(13, weight: .semibold), .large)
        let big = height(Text("Hg").birdoFont(13, weight: .semibold), .accessibility3)
        XCTAssertGreaterThan(big, large * 1.3)
    }
    #endif
}
