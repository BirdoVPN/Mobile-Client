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

    /// MR-1796: the tab bar's UIKit labels are 11pt at `.large`, as before,
    /// grow with the text size, and stop at 14pt so they stay inside the bar
    /// (UITabBar's Large Content Viewer covers the accessibility sizes).
    func testTabBarLabelIsElevenAtLargeGrowsAndIsCapped() {
        func size(_ category: UIContentSizeCategory, selected: Bool = true) -> CGFloat {
            BirdoTheme.tabBarLabelFont(selected: selected,
                                       compatibleWith: UITraitCollection(preferredContentSizeCategory: category))
                .pointSize
        }
        XCTAssertEqual(BirdoTheme.tabBarLabelMaxPointSize, 14)
        XCTAssertEqual(size(.large), 11, accuracy: 0.01)
        XCTAssertEqual(size(.large, selected: false), 11, accuracy: 0.01)
        XCTAssertGreaterThan(size(.extraExtraLarge), 11)
        XCTAssertEqual(size(.accessibilityExtraExtraExtraLarge), 14, accuracy: 0.01)
        XCTAssertEqual(size(.accessibilityExtraExtraExtraLarge, selected: false), 14, accuracy: 0.01)
        for category: UIContentSizeCategory in [.extraExtraExtraLarge, .accessibilityMedium,
                                                .accessibilityLarge, .accessibilityExtraLarge] {
            XCTAssertLessThanOrEqual(size(category), 14.01, category.rawValue)
        }
    }

    /// MR-1796: a glyph in a fixed box (`birdoFont(_:max:)`) renders as
    /// before at `.large` and stops at its cap at the largest size.
    func testCappedModifierIsUnchangedAtLargeAndStopsAtItsCap() {
        let cappedLarge = height(Text("Hg").birdoFont(17, weight: .semibold, max: 24), .large)
        let plainLarge = height(Text("Hg").birdoFont(17, weight: .semibold), .large)
        XCTAssertEqual(cappedLarge, plainLarge, accuracy: 0.5)

        let cappedHuge = height(Text("Hg").birdoFont(17, weight: .semibold, max: 24), .accessibility5)
        let atTheCap = height(Text("Hg").birdoFont(24, weight: .semibold), .large)
        XCTAssertEqual(cappedHuge, atTheCap, accuracy: 0.5)

        let plainHuge = height(Text("Hg").birdoFont(17, weight: .semibold), .accessibility5)
        XCTAssertGreaterThan(plainHuge, cappedHuge * 1.3, "the uncapped one keeps growing")
    }

    /// MR-1796: a TextField prompt's font VALUE is 14pt at `.large` and
    /// scales exactly like `birdoFont(14)`.
    func testScaledFontValueMatchesTheModifier() {
        for size: DynamicTypeSize in [.large, .accessibility3] {
            XCTAssertEqual(height(FontValueProbe(), size), height(Text("Hg").birdoFont(14), size),
                           accuracy: 0.5, "\(size)")
        }
        XCTAssertGreaterThan(height(FontValueProbe(), .accessibility3), height(FontValueProbe(), .large) * 1.3)
    }
    #endif

    /// MR-1796: the cap never changes the default size and holds above it.
    func testCappedTokenClampsOnlyAboveItsCap() {
        let token = BirdoFontToken(size: 17, weight: .semibold, maxSize: 24)
        XCTAssertEqual(token.clamped(17), 17)
        XCTAssertEqual(token.clamped(21), 21)
        XCTAssertEqual(token.clamped(53), 24)
        // A cap below the size would shrink the default, so it is raised to it.
        XCTAssertEqual(BirdoFontToken(size: 17, maxSize: 12).maxSize, 17)
        XCTAssertNil(BirdoFontToken(size: 17).maxSize)
        XCTAssertEqual(BirdoFontToken(size: 17).clamped(53), 53)
    }
}

/// One `Text` styled through `@BirdoScaledFontValue`, as a prompt is.
private struct FontValueProbe: View {
    @BirdoScaledFontValue(14) private var font: Font
    var body: some View { Text("Hg").font(font) }
}
