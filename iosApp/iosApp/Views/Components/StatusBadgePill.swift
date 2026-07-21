import SwiftUI

/// Status pill (BirdoBadge): capsule, 1pt border, padding 12x6, pulsing dot
/// OR 12pt icon + 12pt medium text. Tone table and exact state→text mapping:
/// spec-home-servers-consent.md §3.3, spec-pixelcanvas-design.md §4.2.
struct StatusBadgePill: View {
    enum Tone {
        case neutral, success, warning, danger, info, brand
    }

    let text: String
    var tone: Tone
    var icon: String?
    var pulse: Bool

    /// `pulse: true` replaces the icon with the finite 3-burst PulsingDot
    /// (the connected "Protected" state).
    init(_ text: String, tone: Tone = .neutral, icon: String? = nil, pulse: Bool = false) {
        self.text = text
        self.tone = tone
        self.icon = icon
        self.pulse = pulse
    }

    var body: some View {
        HStack(spacing: 6) {
            if pulse {
                PulsingDot(color: fg)
            } else if let icon {
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .medium))
            }
            Text(text)
                .font(.system(size: 12, weight: .medium))
                .lineLimit(1)
        }
        .foregroundStyle(fg)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(Capsule().fill(bg))
        .overlay(Capsule().strokeBorder(borderColor, lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(text)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private var bg: Color {
        switch tone {
        case .neutral: return BirdoTheme.surfaceRaised
        case .success: return BirdoTheme.greenBg
        case .warning: return BirdoTheme.yellowBg
        case .danger:  return BirdoTheme.redBg
        case .info:    return BirdoTheme.blueBg
        case .brand:   return BirdoTheme.accentBg
        }
    }

    private var fg: Color {
        switch tone {
        case .neutral: return Color.white.opacity(0.85)
        case .success: return BirdoTheme.greenLight
        case .warning: return BirdoTheme.yellowLight
        case .danger:  return BirdoTheme.red
        case .info:    return BirdoTheme.blue
        case .brand:   return BirdoTheme.accentSoft
        }
    }

    private var borderColor: Color {
        switch tone {
        case .neutral: return BirdoTheme.hairlineSoft
        case .success: return BirdoTheme.green.opacity(0.3)
        case .warning: return BirdoTheme.yellow.opacity(0.3)
        case .danger:  return BirdoTheme.red.opacity(0.3)
        case .info:    return BirdoTheme.blue.opacity(0.3)
        case .brand:   return BirdoTheme.accent.opacity(0.3)
        }
    }
}

/// FINITE pulse dot — 8pt container, solid 4.8pt center, expanding ring
/// (scale 1→2, opacity 0.6→0, 1100ms linear, EXACTLY 3 repetitions, then
/// holds invisible). Never infinite: it exists to draw the eye when the
/// status CHANGES; an endless throb burns battery in the app's steady state
/// (spec-pixelcanvas-design.md §4.3). Bump `retrigger` to replay the burst
/// whenever the pill (re)enters connected.
struct PulsingDot: View {
    var color: Color
    var size: CGFloat
    var retrigger: Int

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var ringScale: CGFloat = 1
    @State private var ringOpacity: Double = 0

    init(color: Color, size: CGFloat = 8, retrigger: Int = 0) {
        self.color = color
        self.size = size
        self.retrigger = retrigger
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(color)
                .frame(width: size * 0.6, height: size * 0.6)
            Circle()
                .fill(color)
                .frame(width: size, height: size)
                .scaleEffect(ringScale)
                .opacity(ringOpacity)
        }
        .frame(width: size, height: size)
        .task(id: retrigger) { await runBursts() }
        .accessibilityHidden(true)
    }

    private func runBursts() async {
        guard !reduceMotion else { return }
        for _ in 0..<3 {
            ringScale = 1
            ringOpacity = 0.6
            withAnimation(.linear(duration: 1.1)) {
                ringScale = 2
                ringOpacity = 0
            }
            try? await Task.sleep(nanoseconds: 1_100_000_000)
            if Task.isCancelled { return }
        }
    }
}
