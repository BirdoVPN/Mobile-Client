import SwiftUI

/// MR-1796 / MR-2383: the layout of `SegmentedTabs`, free of app types so
/// SegmentRowTests can lay it out at the large text sizes.
///
/// Side by side, every segment is as tall as the tallest (MR-2383): each
/// `SegmentLabel` takes any height it is offered (`fillsRowHeight`), and the
/// row is fixed at its ideal height, which is the tallest label's. Before, a
/// one-line segment beside one whose label wraps ("Monthly | Yearly · Save up
/// to 20%") sat 1-6 pt shorter at XXL/XXXL, its selected fill visibly short.
/// Stacked (the accessibility sizes, where a third or half of the row is too
/// narrow for a word such as "Anonymous") neither applies: each segment keeps
/// its own height, exactly as before (review of #636).
struct SegmentRow<Segment: View>: View {
    let count: Int
    let stacked: Bool
    @ViewBuilder let segment: (Int) -> Segment

    var body: some View {
        let row = stacked
            ? AnyLayout(VStackLayout(spacing: 4))
            : AnyLayout(HStackLayout(spacing: 4))
        row {
            ForEach(0..<count, id: \.self) { index in
                segment(index)
            }
        }
        .fixedSize(horizontal: false, vertical: !stacked)
    }
}

/// One segment's text, as `SegmentedTabs` draws it. It wraps to a second line
/// before it shrinks (the Yearly label with its saving, from XL), then shrinks
/// a little rather than cut. At least 40 pt tall, and as tall as its row.
struct SegmentLabel: View {
    let text: String
    let weight: Font.Weight
    let textColor: Color
    /// The selected segment's fill; clear for the others.
    let fill: Color
    /// The selected segment's border, nil for none.
    let border: Color?
    /// Side by side (`SegmentRow` not stacked): take the row's height, so the
    /// fill matches the neighbours'. Never when stacked, where a flexible
    /// height has nothing to match and could only squeeze or stretch.
    let fillsRowHeight: Bool

    var body: some View {
        Text(text)
            .birdoFont(13, weight: weight)
            .foregroundStyle(textColor)
            .lineLimit(2)
            .minimumScaleFactor(0.8)
            .multilineTextAlignment(.center)
            // Side by side, maxHeight is the row's height, so the fill matches
            // its neighbours'; stacked, the label's own (review of #636).
            .frame(maxWidth: .infinity, minHeight: 40, maxHeight: fillsRowHeight ? CGFloat.infinity : nil)
            .background(
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(fill)
            )
            .overlay {
                if let border {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .strokeBorder(border, lineWidth: 1)
                }
            }
    }
}
