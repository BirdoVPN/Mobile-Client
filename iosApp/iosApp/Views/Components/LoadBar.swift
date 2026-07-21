import SwiftUI

/// Server load bar: 36x4pt, radius 2, 10%-white track, fill fraction load/100
/// tinted by load (<50 green, <80 yellow, ≥80 red — solid colors only, no
/// gradient, for list perf). The `%` readout next to it should use
/// `LoadBar.color(for:)` + monospace so both stay in the same color.
/// Spec: spec-home-servers-consent.md §4.4 item 5.
struct LoadBar: View {
    let load: Int   // 0-100
    var width: CGFloat
    var height: CGFloat

    init(load: Int, width: CGFloat = 36, height: CGFloat = 4) {
        self.load = load
        self.width = width
        self.height = height
    }

    /// Shared load tint: <50 → emerald-300, <80 → yellow-400, else red-400.
    static func color(for load: Int) -> Color {
        if load < 50 { return BirdoTheme.greenLight }
        if load < 80 { return BirdoTheme.yellowLight }
        return BirdoTheme.red
    }

    var body: some View {
        let clamped = min(max(load, 0), 100)
        ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(BirdoTheme.white10)
            RoundedRectangle(cornerRadius: 2, style: .continuous)
                .fill(Self.color(for: clamped))
                .frame(width: width * CGFloat(clamped) / 100)
        }
        .frame(width: width, height: height)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(clamped)% load")
    }
}
