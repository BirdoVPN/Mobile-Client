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
        let row = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 4))
            : AnyLayout(HStackLayout(spacing: 4))
        row {
            ForEach(items.indices, id: \.self) { index in
                segment(index)
            }
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
            Text(items[index])
                .birdoFont(13, weight: selected && style == .accent ? .semibold : .medium)
                .foregroundStyle(segmentText(selected))
                // Wraps to a second line before it shrinks (the Yearly label
                // with its saving, from XL), then shrinks a little rather than cut.
                .lineLimit(2)
                .minimumScaleFactor(0.8)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 40)
                .background(
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(selected ? selectedFill : Color.clear)
                )
                .overlay {
                    if selected && style == .accent {
                        RoundedRectangle(cornerRadius: 9, style: .continuous)
                            .strokeBorder(BirdoTheme.accentA(0.55), lineWidth: 1)
                    }
                }
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
