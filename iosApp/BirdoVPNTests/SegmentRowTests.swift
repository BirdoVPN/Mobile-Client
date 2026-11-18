import XCTest
import SwiftUI

/// MR-2383: side by side, every segment of `SegmentedTabs` is as tall as the
/// tallest (`SegmentRow` + `SegmentLabel`). Before, at XXL/XXXL a one-line
/// segment beside one whose label wraps was 1-6 pt shorter, its selected fill
/// visibly short. Laid out for real (ImageRenderer), each label's final
/// height read back.
@MainActor
final class SegmentRowTests: XCTestCase {

    /// Each segment's laid-out height, by index.
    private final class Heights {
        var byIndex: [Int: CGFloat] = [:]
    }

    /// The Subscription screen's billing row, in the width a phone gives it.
    private let billing = ["Monthly", "Yearly · Save up to 20%"]
    private let login = ["Email", "Anonymous", "SSO"]

    private func heights(_ items: [String], width: CGFloat, size: DynamicTypeSize,
                         stacked: Bool = false) -> [CGFloat] {
        let measured = Heights()
        let row = SegmentRow(count: items.count, stacked: stacked) { index in
            SegmentLabel(text: items[index], weight: .medium, textColor: .white,
                         fill: index == 0 ? .gray : .clear, border: nil,
                         fillsRowHeight: !stacked)
                .background(GeometryReader { proxy -> Color in
                    measured.byIndex[index] = proxy.size.height
                    return Color.clear
                })
        }
        .frame(width: width)
        .environment(\.dynamicTypeSize, size)
        let renderer = ImageRenderer(content: row)
        _ = renderer.cgImage
        XCTAssertEqual(measured.byIndex.count, items.count, "not every segment was laid out")
        return items.indices.compactMap { measured.byIndex[$0] }
    }

    func testSideBySideSegmentsAreEquallyTallAtXXLAndXXXL() {
        for size: DynamicTypeSize in [.large, .xLarge, .xxLarge, .xxxLarge] {
            for items in [billing, login] {
                let found = heights(items, width: 300, size: size)
                guard let tallest = found.max(), let shortest = found.min() else { continue }
                XCTAssertEqual(tallest, shortest, accuracy: 0.5, "\(items) at \(size): \(found)")
                XCTAssertGreaterThanOrEqual(shortest, 40 - 0.5, "the 40 pt minimum stands")
            }
        }
    }

    /// The case the fix is for: at XXXL the Yearly label wraps past the 40 pt
    /// minimum, and Monthly beside it is as tall, not 40.
    func testAWrappedLabelPullsItsNeighbourUp() {
        let found = heights(billing, width: 300, size: .xxxLarge)
        XCTAssertEqual(found.count, 2)
        guard found.count == 2 else { return }
        XCTAssertGreaterThan(found[1], 40.5, "the Yearly label no longer wraps here; widen the case")
        XCTAssertEqual(found[0], found[1], accuracy: 0.5)
    }

    /// Review of #636 (L1): stacked (the accessibility sizes) no segment
    /// takes a flexible height. Each is exactly as tall as it is alone: the
    /// wrapped "Yearly · Save up to 20%" is neither squeezed nor cut, and
    /// Monthly is not stretched to match it.
    func testStackedSegmentsKeepTheirOwnHeightsAtAX3AndAX5() {
        for size: DynamicTypeSize in [.accessibility3, .accessibility5] {
            let stacked = heights(billing, width: 220, size: size, stacked: true)
            XCTAssertEqual(stacked.count, 2)
            guard stacked.count == 2 else { continue }
            let alone = billing.map { heights([$0], width: 220, size: size, stacked: true).first ?? 0 }
            XCTAssertEqual(stacked[0], alone[0], accuracy: 0.5, "Monthly at \(size)")
            XCTAssertEqual(stacked[1], alone[1], accuracy: 0.5, "Yearly at \(size): squeezed or stretched")
            XCTAssertGreaterThan(stacked[1], stacked[0] + 0.5, "the wrapped Yearly label is taller at \(size)")
        }
    }

    func testOnlyTheSideBySideRowFillsTheRowHeight() throws {
        let row = try source("iosApp/Views/Components/SegmentRow.swift")
        XCTAssertTrue(row.contains(".fixedSize(horizontal: false, vertical: !stacked)"))
        XCTAssertTrue(row.contains("maxHeight: fillsRowHeight ? CGFloat.infinity : nil"))
        let tabs = try source("iosApp/Views/Components/SegmentedTabs.swift")
        XCTAssertTrue(tabs.contains("fillsRowHeight: !dynamicTypeSize.isAccessibilitySize"))
    }

    // MARK: - Source pins (these views cannot be compiled into this bundle)

    private func source(_ path: String) throws -> String {
        // .../iosApp/BirdoVPNTests/ThisFile.swift -> .../iosApp
        let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
        let url = root.appendingPathComponent(path)
        guard FileManager.default.fileExists(atPath: url.path) else {
            XCTFail("\(path) not found under \(root.path): this pin would be vacuous")
            return ""
        }
        return try String(contentsOf: url, encoding: .utf8).replacingOccurrences(of: "\r\n", with: "\n")
    }

    /// The modifiers that follow `start`, up to the next blank line.
    private func chain(after start: String, in text: String) -> String {
        guard let found = text.range(of: start) else {
            XCTFail("not found: \(start)")
            return ""
        }
        let rest = text[found.upperBound...]
        let end = rest.range(of: "\n\n")?.lowerBound ?? rest.endIndex
        return String(rest[..<end])
    }

    func testSegmentedTabsUsesTheRow() throws {
        let tabs = try source("iosApp/Views/Components/SegmentedTabs.swift")
        XCTAssertTrue(tabs.contains("SegmentRow(count: items.count, stacked: dynamicTypeSize.isAccessibilitySize)"))
        XCTAssertTrue(tabs.contains("SegmentLabel(text: items[index],"))
    }

    /// MR-2383 AX5 cosmetics: the Limit pill stays "100 GB / month" on one
    /// line, and a plan's price never breaks mid-word: one line, shrinking
    /// to 70% first.
    func testThePillAndThePriceStayOnOneLine() throws {
        let limit = try source("iosApp/Views/LimitView.swift")
        let pill = chain(after: "GB / month\" : \"Unlimited\")", in: limit)
        XCTAssertTrue(pill.contains(".lineLimit(1)"), pill)
        XCTAssertTrue(pill.contains(".minimumScaleFactor(0.7)"), pill)

        let plans = try source("iosApp/Views/SubscriptionView.swift")
        let price = chain(after: "let price = Text(priceLabel(for: plan, product: product))", in: plans)
        XCTAssertTrue(price.contains(".lineLimit(1)"), price)
        XCTAssertTrue(price.contains(".minimumScaleFactor(0.7)"), price)
    }
}
