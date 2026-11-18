import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Segmented pill control. `.neutral` is the Login `Email | Anonymous | SSO`
/// tab row (spec-auth-flow.md §1 item 5); `.accent` is Android's
/// BirdoSegmentedControl (theme picker, Monthly/Yearly, TCP/UDP —
/// spec-secondary-screens.md §0.3).
struct SegmentedTabs: View {
    enum Style {
        /// Track 5%-white; selected segment 14%-white fill, pure-white text.
        case neutral
        /// Track `surfaceRaised`; selected segment accent@22% fill,
        /// accent@55% border, accent semibold text.
        case accent
    }

    let items: [String]
    @Binding var selection: Int
    var style: Style
    /// MR-1796: the segments stack at the accessibility sizes, where a third
    /// (or half) of the row is too narrow for a word such as "Anonymous".
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    init(items: [String], selection: Binding<Int>, style: Style = .neutral) {
        self.items = items
        self._selection = selection
        self.style = style
    }

    var body: some View {
        // MR-2383: side by side, every segment as tall as the tallest
        // (`SegmentRow`, pinned by SegmentRowTests).
        SegmentRow(count: items.count, stacked: dynamicTypeSize.isAccessibilitySize) { index in
            segment(index)
        }
        .padding(4)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                .fill(trackFill)
        )
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick), value: selection)
        .accessibilityElement(children: .contain)
    }

    private func segment(_ index: Int) -> some View {
        let selected = index == selection
        return Button {
            guard selection != index else { return }
            Haptics.selectionChanged()
            selection = index
        } label: {
            SegmentLabel(text: items[index],
                         weight: selected && style == .accent ? .semibold : .medium,
                         textColor: segmentText(selected),
                         fill: selected ? selectedFill : Color.clear,
                         border: selected && style == .accent ? BirdoTheme.accentA(0.55) : nil,
                         fillsRowHeight: !dynamicTypeSize.isAccessibilitySize)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? [.isSelected] : [])
    }

    private var trackFill: Color {
        switch style {
        case .neutral: return BirdoTheme.white05
        case .accent:  return BirdoTheme.surfaceRaised
        }
    }

    private var selectedFill: Color {
        switch style {
        case .neutral: return Color.white.opacity(0.14)
        case .accent:  return BirdoTheme.accentA(0.22)
        }
    }

    private func segmentText(_ selected: Bool) -> Color {
        switch style {
        case .neutral: return selected ? .white : BirdoTheme.white60
        case .accent:  return selected ? BirdoTheme.accent : BirdoTheme.white60
        }
    }
}
