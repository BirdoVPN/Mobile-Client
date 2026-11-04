import SwiftUI

extension BirdoTheme {
    /// Widths for screens that outgrow a phone (iPad, Stage Manager, a Mac
    /// window). Android's AdaptiveContainer does the same job.
    enum Layout {
        /// Settings, Profile, Limit and the sub-screens: rows and buttons wider
        /// than this stop reading as one line of thought.
        static let column: CGFloat = 640
        /// The plans screen, which puts its cards side by side when it can.
        static let wideColumn: CGFloat = 1080
        /// One plan card when the cards share a row. Three of these plus their
        /// gaps is the width at which the row is chosen over the stack.
        static let planCardMinWidth: CGFloat = 300
    }
}

extension View {
    /// Full width on a phone, a centred column wherever there is more room.
    /// Goes on the scrolling content itself, so the ScrollView still fills the
    /// screen and the indicator stays at the edge.
    func adaptiveColumn(_ width: CGFloat = BirdoTheme.Layout.column) -> some View {
        frame(maxWidth: width).frame(maxWidth: .infinity)
    }

    /// For a scrolling screen with no top bar of its own: without this, scrolled
    /// content runs under the clock on iOS and the title bar on macOS, and the
    /// two read as one line of text. A zero-height inset leaves the layout as it
    /// was; only its background reaches up into the safe area.
    func topEdgeScrim() -> some View {
        safeAreaInset(edge: .top, spacing: 0) {
            Color.clear
                .frame(height: 0)
                .background(BirdoTheme.black.opacity(0.92), ignoresSafeAreaEdges: .top)
        }
    }
}
