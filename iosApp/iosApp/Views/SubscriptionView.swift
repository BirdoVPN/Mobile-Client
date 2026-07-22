import SwiftUI

/// Subscription screen — INFORMATIONAL ONLY (spec-platform-constraints.md
/// §2.4; mirrors Android's store-build "Plans & features" mode).
///
/// Plan truth is the server's `GET /vpn/stats` snapshot (`vpnVM.subscription`),
/// never StoreKit — the backend has no Apple IAP integration, so a StoreKit
/// purchase would charge real money and unlock nothing. Consequently there is
/// NO purchase CTA, no external-site mention and no App Store footnote here:
/// the plan cards are a feature comparison, nothing more.
struct SubscriptionView: View {
    @EnvironmentObject var vpnVM: VpnViewModel

    /// 0 = Monthly, 1 = Yearly. Defaults to yearly (Android parity).
    @State private var billingIndex = 1

    var body: some View {
        ZStack {
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView()

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    if let sub = vpnVM.subscription {
                        currentPlanHero(sub)
                    } else if let err = vpnVM.subscriptionError {
                        // Per-screen error surface (S2) — only while there is
                        // no snapshot at all; a stale-but-good snapshot wins.
                        ErrorBanner(err)
                    }

                    Text("Plans & features")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BirdoTheme.onSurface)
                        .padding(.top, 8)
                        .accessibilityAddTraits(.isHeader)

                    // Toggles the displayed price only — nothing is purchasable.
                    SegmentedTabs(
                        items: ["Monthly", "Yearly · Save 20%"],
                        selection: $billingIndex,
                        style: .accent
                    )

                    ForEach(PlanCardModel.catalog) { plan in
                        planCard(plan)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 32)
            }
        }
        .navigationTitle("Manage Subscription")
        .navigationBarTitleDisplayMode(.inline)
        // Pushed sub-screen: hide the bottom tab bar (spec-home §2 — parity
        // with VpnSettingsView), so it never peeks under a pushed screen.
        .toolbar(.hidden, for: .tabBar)
        .onAppear {
            // Cheap thanks to the 30 s cache; keeps the hero + CURRENT
            // badge honest after a voucher/plan change elsewhere.
            vpnVM.refreshSubscription()
        }
    }

    // MARK: - Current plan hero (server truth)

    private func currentPlanHero(_ sub: VpnStats) -> some View {
        let accent = BirdoTheme.planAccent(sub.plan)
        let isActive = sub.status.caseInsensitiveCompare("ACTIVE") == .orderedSame

        return BirdoCard(cornerRadius: BirdoTheme.Radius.lg,
                         horizontalPadding: 18,
                         verticalPadding: 18) {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                            .fill(accent.opacity(0.15))
                            .frame(width: 44, height: 44)
                        Image(systemName: "crown.fill")
                            .font(.system(size: 22))
                            .foregroundStyle(accent)
                    }
                    .accessibilityHidden(true)

                    VStack(alignment: .leading, spacing: 2) {
                        Text(sub.plan.uppercased())
                            .font(.system(size: 16, weight: .bold))
                            .foregroundStyle(BirdoTheme.onSurface)
                            .lineLimit(1)
                        Text(isActive ? "Active subscription" : "Inactive")
                            .font(BirdoTheme.Fonts.bodySmall)
                            .foregroundStyle(isActive ? BirdoTheme.green : BirdoTheme.white60)
                    }

                    Spacer(minLength: 8)

                    StatusBadgePill(isActive ? "ACTIVE" : "INACTIVE",
                                    tone: isActive ? .success : .neutral)
                }

                HStack(spacing: 0) {
                    heroMetric("Devices", "\(sub.activeConnections)/\(sub.maxConnections)")
                    heroMetric("Bandwidth", sub.hasBandwidthCap
                               ? "\(Self.formatGb(sub.bandwidthLimitGb)) GB"
                               : "Unlimited")
                    heroMetric("Premium", sub.hasPremiumServers ? "Yes" : "No")
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func heroMetric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.system(size: 12, weight: .semibold))
                .tracking(1)
                .foregroundStyle(BirdoTheme.white60)
            Text(value)
                .font(.system(size: 14, weight: .bold))
                .monospacedDigit()
                .foregroundStyle(BirdoTheme.onSurface)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// "10" for whole limits, one decimal otherwise — never "10.0 GB".
    private static func formatGb(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0
            ? String(Int(value))
            : String(format: "%.1f", value)
    }

    // MARK: - Plan cards (canonical catalog, no CTA)

    private func planCard(_ plan: PlanCardModel) -> some View {
        let accent = BirdoTheme.planAccent(plan.slug)
        // Case-insensitive compare against the server slug; no snapshot means
        // no badge (never flash CURRENT on Recon while a paid plan loads).
        let isCurrent = vpnVM.subscription
            .map { $0.plan.caseInsensitiveCompare(plan.slug) == .orderedSame } ?? false

        return BirdoCard(horizontalPadding: 20, verticalPadding: 20) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 8) {
                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 8) {
                            Text(plan.name)
                                .font(.system(size: 16, weight: .bold))
                                .foregroundStyle(BirdoTheme.onSurface)
                            if plan.isPopular {
                                planChip("POPULAR", color: accent)
                            }
                            if isCurrent {
                                planChip("CURRENT", color: BirdoTheme.green)
                            }
                        }
                        Text(plan.tagline)
                            .font(BirdoTheme.Fonts.bodySmall)
                            .foregroundStyle(BirdoTheme.white60)
                    }

                    Spacer(minLength: 8)

                    Text(billingIndex == 1 ? plan.yearlyPrice : plan.monthlyPrice)
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(plan.slug == "RECON"
                                         ? BirdoTheme.white60
                                         : BirdoTheme.onSurface)
                }

                VStack(alignment: .leading, spacing: 0) {
                    ForEach(plan.features, id: \.self) { feature in
                        HStack(spacing: 8) {
                            Image(systemName: "checkmark")
                                .font(.system(size: 12, weight: .semibold))
                                .foregroundStyle(accent)
                                .frame(width: 16)
                                .accessibilityHidden(true)
                            Text(feature)
                                .font(BirdoTheme.Fonts.bodySmall)
                                .foregroundStyle(BirdoTheme.white60)
                        }
                        .padding(.vertical, 3)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .overlay {
            // Popular card swaps the glass hairline for a plan-accent border.
            if plan.isPopular {
                RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                    .strokeBorder(
                        LinearGradient(colors: [accent, accent.opacity(0.3)],
                                       startPoint: .topLeading,
                                       endPoint: .bottomTrailing),
                        lineWidth: 1
                    )
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func planChip(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.system(size: 10, weight: .bold))
            .foregroundStyle(color)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill(color.opacity(0.15))
            )
    }
}

// MARK: - Plan catalog

/// Canonical plan data (birdo-web/lib/plans.ts via spec-secondary-screens.md
/// §5.4 — prices are display strings, feature lists match Android verbatim).
private struct PlanCardModel: Identifiable, Sendable {
    let slug: String
    let name: String
    let tagline: String
    let monthlyPrice: String
    let yearlyPrice: String
    let isPopular: Bool
    let features: [String]

    var id: String { slug }

    static let catalog: [PlanCardModel] = [
        PlanCardModel(
            slug: "RECON",
            name: "Recon",
            tagline: "Test the waters",
            monthlyPrice: "Free",
            yearlyPrice: "Free",
            isPopular: false,
            features: [
                "1 device connection",
                "2 server locations",
                "10 GB monthly bandwidth",
                "WireGuard® encryption",
                "Post-quantum encryption",
                "Kill switch",
                "DNS leak protection",
            ]
        ),
        PlanCardModel(
            slug: "OPERATIVE",
            name: "Operative",
            tagline: "Most popular",
            monthlyPrice: "£3.99/mo",
            yearlyPrice: "£38/yr",
            isPopular: true,
            features: [
                "5 device connections",
                "All server locations",
                "Unlimited bandwidth",
                "WireGuard® encryption",
                "Post-quantum encryption",
                "Kill switch",
                "Split tunneling",
                "Stealth mode",
                "Speed test",
                "2FA / TOTP",
                "Biometric lock",
                "Priority support",
            ]
        ),
        PlanCardModel(
            slug: "SOVEREIGN",
            name: "Sovereign",
            tagline: "Full control",
            monthlyPrice: "£9.99/mo",
            yearlyPrice: "£99/yr",
            isPopular: false,
            features: [
                "10 device connections",
                "All server locations",
                "Unlimited bandwidth",
                "WireGuard® encryption",
                "Post-quantum encryption",
                "Kill switch",
                "Split tunneling",
                "Stealth mode",
                "Multi-hop routing",
                "Port forwarding",
                "Speed test",
                "2FA / TOTP",
                "Biometric lock",
                "Custom DNS",
                "Priority support",
            ]
        ),
    ]
}
