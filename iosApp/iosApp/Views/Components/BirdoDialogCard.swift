import SwiftUI

/// The card of a confirmation dialog: radius 16, `surfaceRaised` fill, soft
/// hairline border, padding 24, at most 384pt wide. Its content scrolls and
/// its footer (the buttons) never does.
///
/// MR-1796: at the accessibility text sizes the delete-account dialog (a long
/// message, the store warning and a password field) and the sign-out dialog
/// were taller than a phone, so their buttons were off screen and the
/// action could not be confirmed or cancelled. Now the card is never taller
/// than the space it is shown in: the content takes its own height when
/// everything fits (exactly as before) and otherwise scrolls in what the
/// buttons leave.
struct BirdoDialogCard<Content: View, Footer: View>: View {
    private let content: Content
    private let footer: Footer

    init(@ViewBuilder content: () -> Content, @ViewBuilder footer: () -> Footer) {
        self.content = content()
        self.footer = footer()
    }

    var body: some View {
        ScrollingBodyFooterLayout {
            ScrollView(.vertical) {
                content
                    .frame(maxWidth: .infinity)
                    .padding([.horizontal, .top], 24)
                    .padding(.bottom, 12)
            }
            .scrollBounceBehavior(.basedOnSize)

            footer
                .padding(.top, 8)
                .padding([.horizontal, .bottom], 24)
        }
        .frame(maxWidth: 384)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                .fill(BirdoTheme.surfaceRaised)
        )
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
        )
    }
}

/// Two subviews: a scrolling body over a pinned footer. The body gets its
/// ideal height (a `ScrollView`'s ideal height is its content's) when that
/// fits, and only the height the footer leaves when it does not. The footer
/// always gets its own height, at the bottom.
struct ScrollingBodyFooterLayout: Layout {
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard subviews.count == 2 else { return .zero }
        let ideal = ProposedViewSize(width: proposal.width, height: nil)
        let content = subviews[0].sizeThatFits(ideal)
        let footer = subviews[1].sizeThatFits(ideal)
        let room = proposal.height.map { Swift.max(0, $0 - footer.height) } ?? content.height
        let width = proposal.width ?? Swift.max(content.width, footer.width)
        return CGSize(width: width, height: Swift.min(content.height, room) + footer.height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        guard subviews.count == 2 else { return }
        let footer = subviews[1].sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
        let contentHeight = Swift.max(0, bounds.height - footer.height)
        subviews[0].place(at: bounds.origin, anchor: .topLeading,
                          proposal: ProposedViewSize(width: bounds.width, height: contentHeight))
        subviews[1].place(at: CGPoint(x: bounds.minX, y: bounds.maxY - footer.height), anchor: .topLeading,
                          proposal: ProposedViewSize(width: bounds.width, height: footer.height))
    }
}
