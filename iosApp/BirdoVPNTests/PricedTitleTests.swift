import XCTest
import SwiftUI

/// MR-2430: the Subscribe button's price is never cut or shrunk. It used to be
/// one Text, "Subscribe · CHF 1,099.00/yr", under PrimaryButton's lineLimit(2)
/// + minimumScaleFactor(0.85). At AX5 in a narrow width the whole string needs
/// three lines even at 85 %, so the button showed two and cut the period
/// (App Review 3.1.2). Now the price goes in apart (`PrimaryButton(_:price:)`)
/// and `PricedTitle` restacks it. Laid out for real (ImageRenderer), each
/// height read back.
@MainActor
final class PricedTitleTests: XCTestCase {

    /// The button's width in a plan card: the screen less its 16 pt margins
    /// and the card's 20 pt padding on each side. An iPhone SE (375 pt), and
    /// the narrowest window iPadOS gives an app (Slide Over, 320 pt).
    private let phone: CGFloat = 375 - 72
    private let narrow: CGFloat = 320 - 72
    private var widths: [CGFloat] { [phone, narrow] }

    /// A long localised price (the one #636's plan-card test uses) and an
    /// ordinary one.
    private let long = StorePriceParts(amount: "CHF 1,099.00", period: "/yr")
    private let short = StorePriceParts(amount: "$39.00", period: "/yr")

    private final class Box {
        var height: CGFloat?
    }

    /// `view`'s laid-out height in `width`, at `size`.
    private func height<V: View>(_ view: V, width: CGFloat, size: DynamicTypeSize) -> CGFloat {
        let box = Box()
        let probe = view
            .background(GeometryReader { proxy -> Color in
                box.height = proxy.size.height
                return Color.clear
            })
            .frame(width: width)
            .environment(\.dynamicTypeSize, size)
        _ = ImageRenderer(content: probe).cgImage
        guard let found = box.height else {
            XCTFail("not laid out")
            return 0
        }
        return found
    }

    /// The Subscribe button as SubscriptionView builds it.
    private func subscribe(_ price: StorePriceParts) -> some View {
        PrimaryButton("Subscribe", price: price, variant: .brand, height: 48) {}
    }

    /// The button as it was: the price baked into its one title.
    private func oldSubscribe(_ price: StorePriceParts) -> some View {
        PrimaryButton(PricedTitle.oneLine("Subscribe", price), variant: .brand, height: 48) {}
    }

    /// What the whole label needs at full size (PrimaryButton's 14 pt
    /// semibold), free to wrap.
    private func needed(_ price: StorePriceParts, width: CGFloat, size: DynamicTypeSize) -> CGFloat {
        height(Text(PricedTitle.oneLine("Subscribe", price))
                   .birdoFont(14, weight: .semibold)
                   .fixedSize(horizontal: false, vertical: true),
               width: width, size: size)
    }

    /// One line of the label's font, at `scale` of its size.
    private func line(_ size: DynamicTypeSize, scale: CGFloat = 1) -> CGFloat {
        height(Text("Hg").birdoFont(14 * scale, weight: .semibold, relativeTo: .subheadline),
               width: 400, size: size)
    }

    /// The bug. Even shrunk to its 85 % floor, the whole string needs three
    /// lines at AX5 in a narrow width; the old button had room for two.
    func testTheOldButtonCouldNotHoldTheWholePriceAtAX5() {
        let shrunk = height(Text(PricedTitle.oneLine("Subscribe", long))
                                .birdoFont(14 * 0.85, weight: .semibold, relativeTo: .subheadline)
                                .fixedSize(horizontal: false, vertical: true),
                            width: narrow, size: .accessibility5)
        XCTAssertGreaterThan(shrunk, line(.accessibility5, scale: 0.85) * 2.5,
                             "two lines hold it at 85 %: this case no longer shows the bug")
        let old = height(oldSubscribe(long), width: narrow, size: .accessibility5)
        XCTAssertLessThan(old, needed(long, width: narrow, size: .accessibility5) - 1,
                          "the old button was tall enough after all")
    }

    /// The fix. At AX3 and AX5 the button is at least as tall as its whole
    /// label needs at full size, so nothing is cut and nothing shrinks.
    func testTheButtonHoldsTheWholePriceAtAX3AndAX5() {
        for size: DynamicTypeSize in [.accessibility3, .accessibility5] {
            for width in widths {
                for price in [short, long] {
                    let button = height(subscribe(price), width: width, size: size)
                    let full = needed(price, width: width, size: size)
                    XCTAssertGreaterThanOrEqual(button, full - 1, "\(price.whole) at \(size) in \(width) pt")
                }
            }
        }
    }

    /// At AX5 the long price leaves the title's line, and its period leaves
    /// the amount's: three lines at least, where the old button had two.
    func testAtAX5TheLongPriceStacksUnderTheTitle() {
        for width in widths {
            let button = height(subscribe(long), width: width, size: .accessibility5)
            XCTAssertGreaterThan(button, line(.accessibility5) * 2.5, "\(width) pt")
        }
    }

    /// At the default size the label is one line, as before, and the button
    /// keeps its 48 pt.
    func testAtTheDefaultSizeTheButtonIsUnchanged() {
        for width in widths {
            for price in [short, long] {
                let now = height(subscribe(price), width: width, size: .large)
                XCTAssertEqual(now, height(oldSubscribe(price), width: width, size: .large), accuracy: 0.5)
                XCTAssertEqual(now, 48, accuracy: 0.5, "\(price.whole) in \(width) pt")
            }
        }
    }

    /// VoiceOver reads what the button always said: the price and its period.
    func testTheSpokenLabelIsTheWholePrice() {
        XCTAssertEqual(PricedTitle.oneLine("Subscribe", short), "Subscribe · $39.00/yr")
        let monthly = StorePriceParts(amount: "£3.99", period: StoreBillingPeriod.monthly.priceSuffix)
        XCTAssertEqual(PricedTitle.oneLine("Subscribe", monthly), "Subscribe · £3.99/mo")
    }

    // MARK: - Source pins (SubscriptionView cannot be compiled into this bundle)

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

    func testTheSubscribeButtonPassesItsPriceApart() throws {
        let view = try source("iosApp/Views/SubscriptionView.swift")
        XCTAssertTrue(view.contains("authVM.isGuest ? \"Sign in to subscribe\" : \"Subscribe\","))
        XCTAssertTrue(view.contains("price: authVM.isGuest ? nil : priceParts(for: plan, product: product),"))
        XCTAssertFalse(view.contains("\"Subscribe · \\("), "the price is baked into the title again")

        let button = try source("iosApp/Views/Components/PrimaryButton.swift")
        XCTAssertTrue(button.contains(".accessibilityLabel(spokenLabel)"))
        guard let start = button.range(of: "struct PricedTitle: View {"),
              let end = button.range(of: "struct PressScaleButtonStyle") else {
            return XCTFail("PricedTitle not found")
        }
        let priced = String(button[start.lowerBound..<end.lowerBound])
        XCTAssertFalse(priced.contains(".lineLimit("), "a price must never be cut (App Review 3.1.2)")
        XCTAssertFalse(priced.contains(".minimumScaleFactor("), priced)
    }
}
