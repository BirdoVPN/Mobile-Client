import SwiftUI

/// Limit tab — Android `LimitScreen` parity (spec-secondary-screens.md §4).
///
/// Shown to EVERY user type. Semantics: `bandwidthLimitGb == 0` means
/// UNLIMITED (wire null is coerced to 0 by the decoder) — `hasCap` gates on
/// `> 0`; never render "0 GB / month". Free (RECON) plans get a 270°
/// speed-dial gauge of the cap; paid plans get the unlimited state.
///
/// Data honesty rule: never present a frozen number as live — the freshness
/// line under the gauge is mandatory.
///
/// Tab-root screen: transparent background over the app-root PixelCanvas.
struct LimitView: View {
    @EnvironmentObject var vpnVM: VpnViewModel

    @State private var showSubscription = false

    private var stats: VpnStats? { vpnVM.subscription }
    private var limitGb: Double { stats?.bandwidthLimitGb ?? 0 }
    private var hasCap: Bool { limitGb > 0 }
    private var usedGb: Double { stats?.bandwidthUsedGb ?? 0 }
    private var fraction: Double {
        hasCap ? min(max(usedGb / limitGb, 0), 1) : 0
    }
    private var meterColor: Color {
        if fraction >= 0.9 { return BirdoTheme.red }
        if fraction >= 0.75 { return BirdoTheme.yellow }
        return BirdoTheme.accent
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                planHeader

                if let error = vpnVM.subscriptionError {
                    ErrorBanner(error, icon: "exclamationmark.circle")
                }

                usageCard

                if stats != nil && hasCap {
                    upgradeCard
                }

                Spacer(minLength: 32)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 24)
        }
        .scrollIndicators(.hidden)
        .modifier(HideNavigationBar())
        .navigationDestination(isPresented: $showSubscription) {
            SubscriptionView()
        }
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick),
                   value: vpnVM.subscriptionError)
        .task {
            // The user deliberately opened their usage — bypass the 30 s
            // stats cache (spec-secondary-screens.md warning 11).
            vpnVM.refreshSubscription(force: true)
        }
    }

    // MARK: - Plan header (§4.1)

    private var planHeader: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("YOUR PLAN")
                .font(BirdoTheme.Fonts.sectionHeader)
                .tracking(1.5)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .padding(.leading, 4)
                .accessibilityAddTraits(.isHeader)
            HStack(alignment: .center) {
                Text(BirdoTheme.planDisplayName(vpnVM.currentPlan))
                    .font(.system(size: 22, weight: .bold))
                    .foregroundStyle(BirdoTheme.onSurface)
                Spacer()
                if stats != nil {
                    Text(hasCap ? "\(Self.wholeGb(limitGb)) GB / month" : "Unlimited")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(BirdoTheme.accent)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Capsule().fill(BirdoTheme.accentA(0.16)))
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - Usage card (§4.2 — three states)

    private var usageCard: some View {
        BirdoCard {
            Group {
                if stats == nil {
                    loadingState
                } else if !hasCap {
                    unlimitedState
                } else {
                    cappedGauge
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    private var loadingState: some View {
        VStack(spacing: 12) {
            ProgressView()
                .progressViewStyle(.circular)
                .tint(BirdoTheme.accent)
                .scaleEffect(1.3)
            Text("Loading your usage…")
                .font(.system(size: 13))
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
        }
        .padding(.vertical, 48)
    }

    private var unlimitedState: some View {
        VStack(spacing: 12) {
            Circle()
                .fill(BirdoTheme.accentA(0.14))
                .frame(width: 56, height: 56)
                .overlay(
                    Image(systemName: "speedometer")
                        .font(.system(size: 30))
                        .foregroundStyle(BirdoTheme.accent)
                )
            Text("Unlimited data")
                .font(.system(size: 18, weight: .semibold))
                .foregroundStyle(BirdoTheme.onSurface)
            Text("Your plan has no data cap — use as much as you like.")
                .font(.system(size: 13))
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .multilineTextAlignment(.center)
        }
        .padding(.vertical, 20)
    }

    private var cappedGauge: some View {
        let neverSynced = stats?.bandwidthLastSyncAt == nil
        let isFresh = stats?.bandwidthIsFresh == true

        return VStack(spacing: 0) {
            SpeedDialGauge(
                fraction: fraction,
                usedLabel: Self.gaugeValue(usedGb),
                limitLabel: "of \(Self.wholeGb(limitGb)) GB used",
                percentLabel: "\(Int((fraction * 100).rounded()))%",
                color: meterColor
            )
            .padding(.top, 4)

            // Freshness — the meter is only as honest as the last sync.
            HStack(spacing: 6) {
                Circle()
                    .fill(isFresh ? BirdoTheme.accent : BirdoTheme.onSurfaceFaint)
                    .frame(width: 7, height: 7)
                Text(freshnessText(neverSynced: neverSynced, isFresh: isFresh))
                    .font(.system(size: 12))
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
            }

            // Prominent refresh: a tonal accent pill, unmistakably a button.
            Button {
                vpnVM.refreshSubscription(force: true)
            } label: {
                HStack(spacing: 7) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 16))
                    Text("Refresh usage")
                        .font(.system(size: 13, weight: .semibold))
                }
                .foregroundStyle(BirdoTheme.accent)
                .padding(.horizontal, 16)
                .padding(.vertical, 9)
                .background(Capsule().fill(BirdoTheme.accentA(0.14)))
            }
            .buttonStyle(PressScaleButtonStyle())
            .padding(.top, 10)

            Rectangle()
                .fill(BirdoTheme.hairlineSoft)
                .frame(height: 1)
                .padding(.vertical, 14)

            // Used / Left / Resets — equal columns with hairline separators.
            HStack(spacing: 0) {
                statCell("Used", Self.formatGb(usedGb), meterColor)
                statDivider
                statCell("Left", Self.formatGb(max(0, limitGb - usedGb)), BirdoTheme.onSurface)
                statDivider
                statCell("Resets",
                         Self.formatResetDate(stats?.bandwidthPeriodEnd) ?? "Monthly",
                         BirdoTheme.onSurface)
            }

            Text("Counts uploads and downloads · Updates about every minute")
                .font(.system(size: 11))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 14)
        }
    }

    private func freshnessText(neverSynced: Bool, isFresh: Bool) -> String {
        if neverSynced {
            return "Awaiting first sync — connect to start counting"
        }
        if isFresh {
            return Self.relativeAgo(stats?.bandwidthLastSyncAt).map { "Updated \($0)" }
                ?? "Up to date"
        }
        return Self.relativeAgo(stats?.bandwidthLastSyncAt).map { "Last update \($0)" }
            ?? "May be delayed"
    }

    private func statCell(_ label: String, _ value: String, _ valueColor: Color) -> some View {
        VStack(spacing: 3) {
            Text(label.uppercased())
                .font(.system(size: 10, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
            Text(value)
                .font(.system(size: 15, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(valueColor)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    private var statDivider: some View {
        Rectangle()
            .fill(BirdoTheme.hairlineSoft)
            .frame(width: 1, height: 30)
    }

    // MARK: - Plan card (§4.3)
    //
    // P1-ios-limit-upgrade-cta-dead-end: this used to read "Upgrade for
    // unlimited" and push SubscriptionView — which is INFORMATIONAL ONLY by
    // design (see SubscriptionView's kdoc: the backend has no Apple IAP, and a
    // StoreKit purchase would charge real money and unlock nothing, so there is
    // deliberately no purchase CTA there). The button therefore promised a
    // purchase and delivered a feature comparison: the users it dead-ended were
    // the ones actively trying to pay.
    //
    // There is no in-app upgrade flow to wire it to, so the upgrade CTA is gone.
    // What is left is the honest version of what the destination actually is —
    // "View plans", the exact wording MultiHopView already uses for the same
    // screen — and body copy that no longer says "Upgrade to Operative" as
    // though tapping here would do it.

    private var upgradeCard: some View {
        BirdoCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    RoundedRectangle(cornerRadius: 9, style: .continuous)
                        .fill(BirdoTheme.accentA(0.14))
                        .frame(width: 34, height: 34)
                        .overlay(
                            Image(systemName: "bolt.fill")
                                .font(.system(size: 19))
                                .foregroundStyle(BirdoTheme.accent)
                        )
                    Text(fraction >= 0.9 ? "You're almost out of data" : "Need more data?")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurface)
                }
                Text("Free accounts include \(Self.wholeGb(limitGb)) GB per month. Paid plans include unlimited data on every server.")
                    .font(.system(size: 13))
                    .lineSpacing(5)
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
                PrimaryButton("View plans", height: 48) {
                    showSubscription = true
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Formatting (Android LimitScreen parity)

    /// Big dial number: "0.52", "1.7", "10" — the unit lives in the line
    /// below. ≥10 → integer; ≥1 → 1 decimal; else 2 decimals.
    private static func gaugeValue(_ gb: Double) -> String {
        let safe = max(0, gb)
        if safe >= 10 { return String(Int(safe.rounded())) }
        if safe >= 1 { return trimmedDecimal(safe, maxPlaces: 1) }
        return trimmedDecimal(safe, maxPlaces: 2)
    }

    private static func formatGb(_ gb: Double) -> String {
        let safe = max(0, gb)
        if safe >= 10 { return "\(Int(safe.rounded())) GB" }
        return "\(trimmedDecimal(safe, maxPlaces: 2)) GB"
    }

    /// Kotlin-style Double rendering: trailing zeros trimmed but at least one
    /// decimal kept ("0.50" → "0.5", "0.00" → "0.0").
    private static func trimmedDecimal(_ value: Double, maxPlaces: Int) -> String {
        var s = String(format: "%.\(maxPlaces)f", value)
        while s.hasSuffix("0") && !s.hasSuffix(".0") {
            s.removeLast()
        }
        return s
    }

    /// Whole-number GB for the allowance pill / copy ("10", not "10.0").
    private static func wholeGb(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(value))
            : String(format: "%.1f", value)
    }

    /// "2026-07-31T23:59:59.999Z" → "Jul 31" in the device timezone;
    /// nil / parse failure → nil.
    private static func formatResetDate(_ iso: String?) -> String? {
        guard let date = parseInstant(iso) else { return nil }
        let formatter = DateFormatter()
        // Fixed-format string ⇒ fixed locale + Gregorian calendar, or users on
        // non-Gregorian device calendars (Buddhist, Japanese…) see a wrong date.
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.dateFormat = "MMM d"
        return formatter.string(from: date)
    }

    /// Relative "3 min ago" / "just now" from an ISO instant; nil on parse
    /// failure.
    private static func relativeAgo(_ iso: String?) -> String? {
        guard let date = parseInstant(iso) else { return nil }
        let secs = max(0, Int(Date().timeIntervalSince(date)))
        if secs < 60 { return "just now" }
        if secs < 3600 { return "\(secs / 60) min ago" }
        if secs < 86400 { return "\(secs / 3600) h ago" }
        return "\(secs / 86400) d ago"
    }

    private static func parseInstant(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        let withFractional = ISO8601DateFormatter()
        withFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = withFractional.date(from: iso) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: iso)
    }
}

// MARK: - Speed-dial gauge

/// Speedometer arc: 270° sweep starting at 135° (gap at the bottom), 18pt
/// round-capped stroke, knob at the current position (colored ring +
/// surface-colored core), fill animated over 900 ms. The percentage readout
/// sits in the bottom gap so the centre stays uncluttered.
private struct SpeedDialGauge: View {
    let fraction: Double
    let usedLabel: String
    let limitLabel: String
    let percentLabel: String
    let color: Color

    @State private var animated: Double = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private static let size: CGFloat = 232
    private static let stroke: CGFloat = 18
    private static let startDeg: Double = 135
    private static let sweepDeg: Double = 270
    /// Arc diameter — the stroke is centered on this circle, so the outer
    /// edge lands exactly on the 232pt square.
    private var dial: CGFloat { Self.size - Self.stroke }

    var body: some View {
        ZStack {
            // Track.
            Circle()
                .trim(from: 0, to: Self.sweepDeg / 360)
                .stroke(Color.white.opacity(0.14),
                        style: StrokeStyle(lineWidth: Self.stroke, lineCap: .round))
                .rotationEffect(.degrees(Self.startDeg))
                .frame(width: dial, height: dial)

            // Progress arc.
            Circle()
                .trim(from: 0, to: (Self.sweepDeg / 360) * animated)
                .stroke(color,
                        style: StrokeStyle(lineWidth: Self.stroke, lineCap: .round))
                .rotationEffect(.degrees(Self.startDeg))
                .frame(width: dial, height: dial)

            // Knob: placed on the arc via container rotation so it travels
            // along the circle (a plain offset would animate in a straight
            // line).
            ZStack {
                Circle()
                    .fill(color)
                    .frame(width: 13, height: 13)
                    .overlay(
                        Circle()
                            .fill(BirdoTheme.surface)
                            .frame(width: 5.5, height: 5.5)
                    )
                    .offset(x: dial / 2)
            }
            .frame(width: Self.size, height: Self.size)
            .rotationEffect(.degrees(Self.startDeg + Self.sweepDeg * animated))

            // Centre readout: the number is the hero, the unit line supports it.
            VStack(spacing: 2) {
                Text(usedLabel)
                    .font(.system(size: 40, weight: .bold, design: .monospaced))
                    .foregroundStyle(BirdoTheme.onSurface)
                Text(limitLabel)
                    .font(.system(size: 13))
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
            }
        }
        .frame(width: Self.size, height: Self.size)
        .overlay(alignment: .bottom) {
            // Percentage sits in the bottom gap of the arc.
            Text(percentLabel)
                .font(.system(size: 13, weight: .semibold, design: .monospaced))
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .padding(.bottom, 2)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(usedLabel) \(limitLabel), \(percentLabel)")
        .onAppear {
            setFill(fraction)
        }
        .onChange(of: fraction) { _, newValue in
            setFill(newValue)
        }
    }

    private func setFill(_ target: Double) {
        let clamped = min(max(target, 0), 1)
        if reduceMotion {
            animated = clamped
        } else {
            withAnimation(BirdoTheme.Motion.easeStandard(0.9)) {
                animated = clamped
            }
        }
    }
}
