import XCTest
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// MR-1796: the confirm dialog's card (`BirdoDialogCard`) is never taller
/// than the space it is shown in, so its pinned buttons stay on screen at the
/// accessibility text sizes, and it is exactly as tall as before when its
/// content fits.
@MainActor
final class DialogLayoutTests: XCTestCase {
    #if canImport(UIKit)
    /// An iPhone SE (375 x 667) minus the dialog's 24pt margins on each side.
    private let space = CGSize(width: 375 - 48, height: 667 - 48)

    /// About the length of the delete-account message plus its store warning.
    private let longMessage = String(
        repeating: "Deleting your account removes it and everything tied to it from Birdo. ",
        count: 6)

    private func fitted<V: View>(_ view: V, _ size: DynamicTypeSize) -> CGSize {
        let host = UIHostingController(rootView: view.environment(\.dynamicTypeSize, size))
        return host.sizeThatFits(in: space)
    }

    private func content(_ message: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "trash").birdoFont(36, max: 56)
            Text("Delete account?").font(BirdoTheme.Fonts.titleLarge)
            Text(message)
                .font(BirdoTheme.Fonts.bodyMedium)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var buttons: some View {
        HStack(spacing: 10) {
            Text("Cancel").frame(maxWidth: .infinity, minHeight: 40)
            Text("Delete").frame(maxWidth: .infinity, minHeight: 40)
        }
    }

    /// The old card: one VStack (12 + 8 between the content and the buttons)
    /// padded 24, with nothing to scroll.
    private func unscrolled(_ message: String) -> some View {
        VStack(spacing: 20) {
            content(message)
            buttons
        }
        .padding(24)
        .frame(maxWidth: 384)
    }

    func testAtTheLargestSizeTheCardFitsTheScreenWithItsButtons() {
        // The old layout is taller than the phone here: its buttons were off screen.
        XCTAssertGreaterThan(fitted(unscrolled(longMessage), .accessibility5).height, space.height)

        let card = fitted(BirdoDialogCard { content(longMessage) } footer: { buttons }, .accessibility5)
        XCTAssertLessThanOrEqual(card.height, space.height + 0.5)
        // The buttons are laid out at the card's bottom edge, inside it.
        XCTAssertGreaterThan(card.height, fitted(buttons, .accessibility5).height + 48)
    }

    func testAtLargeAShortCardIsExactlyAsTallAsBefore() {
        let message = "Are you sure you want to sign out?"
        let before = fitted(unscrolled(message), .large)
        let card = fitted(BirdoDialogCard { content(message) } footer: { buttons }, .large)
        XCTAssertEqual(card.height, before.height, accuracy: 0.5)
        XCTAssertLessThan(card.height, space.height)
    }
    #endif
}
