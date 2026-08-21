import SwiftUI
import StoreKit

/// Subscription screen — the App Store purchase surface.
///
/// 🔴 DELIBERATE REVERSAL. This file used to open with:
///
///     "Subscription screen — INFORMATIONAL ONLY … the backend has no Apple IAP
///      integration, so a StoreKit purchase would charge real money and unlock
///      nothing. Consequently there is NO purchase CTA, no external-site
///      mention and no App Store footnote here."
///
/// That reasoning was correct when it was written and is now obsolete on both
/// halves. The backend rail exists (`POST /payments/store/apple/{purchase-token,
/// link,notifications}`), so a StoreKit purchase now unlocks exactly what it
/// charged for. And App Review rejected iOS 1.4.20 and macOS 1.4.22 on
/// 21 Aug 2026 under Guideline 3.1.1: an app that unlocks a web-bought
/// subscription MUST also sell it through In-App Purchase. Removing the CTA
/// never addressed 3.1.1, because 3.1.1 is about what the app DOES.
///
/// Plan truth is still the server's `GET /vpn/stats` snapshot
/// (`vpnVM.subscription`) and never StoreKit — StoreKit says what Apple
/// charged for, the server says what the account is entitled to, and only the
/// second one can account for a web subscription, a voucher or a refund that
/// arrived by server notification.
struct SubscriptionView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var store: StoreKitService

    @Environment(\.openURL) private var openURL

    /// 0 = Monthly, 1 = Yearly. Defaults to yearly (Android parity).
    @State private var billingIndex = 1

    private var period: StoreBillingPeriod { billingIndex == 1 ? .yearly : .monthly }

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

                    // Money first: being billed twice is the single most
                    // important thing this screen can have to say.
                    if let duplicate = store.duplicateBilling {
                        duplicateBillingCard(duplicate)
                    }

                    if let notice = store.notice {
                        noticeBanner(notice)
                    }

                    Text("Plans & features")
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(BirdoTheme.onSurface)
                        .padding(.top, 8)
                        .accessibilityAddTraits(.isHeader)

                    // Honest degradation: when nothing is purchasable the user
                    // is told why, in place, instead of being shown buttons
                    // that cannot work or a list that looks broken.
                    if case .unavailable(let reason) = store.storefront {
                        storefrontUnavailableCard(reason)
                    }

                    SegmentedTabs(
                        items: ["Monthly", "Yearly · Save 20%"],
                        selection: $billingIndex,
                        style: .accent
                    )

                    ForEach(PlanCardModel.catalog) { plan in
                        planCard(plan)
                    }

                    restoreRow
                    legalFootnote
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)
                .padding(.bottom, 32)
            }
        }
        .navigationTitle("Manage Subscription")
        .modifier(InlineNavigationTitle())
        // Pushed sub-screen: hide the bottom tab bar (spec-home §2 — parity
        // with VpnSettingsView), so it never peeks under a pushed screen.
        .modifier(HideTabBar())
        .onAppear {
            // Cheap thanks to the 30 s cache; keeps the hero + CURRENT
            // badge honest after a voucher/plan change elsewhere.
            vpnVM.refreshSubscription()
        }
        .task {
            // The listener and the first catalogue load start at app launch;
            // this only retries a load that has not succeeded yet, so opening
            // the screen after coming back online is enough to fix it.
            if case .unavailable = store.storefront {
                await store.loadProducts()
            }
        }
        .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick), value: store.notice)
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

    // MARK: - Store banners

    /// Zero products resolved. The App Store has no Birdo products to offer
    /// until the Paid Applications Agreement is active, so this is the state a
    /// real device is in TODAY — it has to read as a temporary, explained
    /// condition, not as a broken screen.
    private func storefrontUnavailableCard(_ reason: String) -> some View {
        BirdoCard(horizontalPadding: 16, verticalPadding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: "cart.badge.questionmark")
                        .font(.system(size: 18))
                        .foregroundStyle(BirdoTheme.yellow)
                        .accessibilityHidden(true)
                    Text("Purchasing is unavailable")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurface)
                }
                Text(reason)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.white60)
                    .fixedSize(horizontal: false, vertical: true)
                PrimaryButton("Try again", variant: .secondary, height: 44) {
                    Task { await store.loadProducts() }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }

    private func noticeBanner(_ notice: StoreNotice) -> some View {
        let tint: Color = {
            switch notice.kind {
            case .success: return BirdoTheme.green
            case .info:    return BirdoTheme.blue
            case .error:   return BirdoTheme.red
            }
        }()
        let icon: String = {
            switch notice.kind {
            case .success: return "checkmark.seal.fill"
            case .info:    return "info.circle.fill"
            case .error:   return "exclamationmark.triangle.fill"
            }
        }()

        return BirdoCard(horizontalPadding: 14, verticalPadding: 14) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: 16))
                    .foregroundStyle(tint)
                    .accessibilityHidden(true)
                Text(notice.text)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    store.notice = nil
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .bold))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Dismiss")
            }
        }
    }

    /// The account is paying on two rails. Never quietly cancel either one —
    /// that is a money movement nobody authorised — so the only correct thing
    /// to do is say so, immediately, and name where to cancel. The server
    /// writes this message because it is the side that knows about the other
    /// subscription; render it verbatim.
    private func duplicateBillingCard(_ duplicate: AppleDuplicateBilling) -> some View {
        BirdoCard(horizontalPadding: 16, verticalPadding: 16) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Image(systemName: "creditcard.trianglebadge.exclamationmark")
                        .font(.system(size: 18))
                        .foregroundStyle(BirdoTheme.yellow)
                        .accessibilityHidden(true)
                    Text("You are being billed twice")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(BirdoTheme.onSurface)
                }
                Text(duplicate.message)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.onSurface)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: 10) {
                    PrimaryButton("Manage App Store subscription",
                                  variant: .secondary, height: 44) {
                        SystemOpen.manageSubscriptions()
                    }
                    PrimaryButton("Dismiss", variant: .ghost, height: 44) {
                        store.duplicateBilling = nil
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .overlay {
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.card, style: .continuous)
                .strokeBorder(BirdoTheme.yellow.opacity(0.55), lineWidth: 1)
        }
    }

    // MARK: - Restore (Apple requires a visible control)

    /// Restore Purchases is MANDATORY for auto-renewable subscriptions and it
    /// must be reachable without buying anything first — a user who reinstalls,
    /// or who signs in on a second device, has no other way back to what they
    /// already pay for. It also stays visible when the storefront is
    /// unavailable, because a previous purchase can still be restored when no
    /// new one can be made.
    private var restoreRow: some View {
        VStack(alignment: .leading, spacing: 8) {
            PrimaryButton("Restore Purchases",
                          loadingTitle: "Restoring…",
                          variant: .secondary,
                          icon: "arrow.clockwise",
                          isLoading: store.isRestoring,
                          height: 48) {
                Task { await store.restorePurchases() }
            }
            .accessibilityIdentifier("subscription_restore")

            Text("Already subscribed on this Apple ID? Restore brings it back to this Birdo account.")
                .font(.system(size: 11))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 4)
    }

    // MARK: - Auto-renew disclosure

    /// Required disclosure for auto-renewable subscriptions: what renews, how
    /// often, that it renews until cancelled, and where to cancel — plus the
    /// terms and privacy links.
    private var legalFootnote: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("About these subscriptions")
                .font(.system(size: 12, weight: .semibold))
                .foregroundStyle(BirdoTheme.white60)

            Text("Operative and Sovereign are auto-renewing subscriptions. Payment is charged to "
                 + "your Apple Account at confirmation of purchase. The subscription renews "
                 + "automatically for the same period unless it is cancelled at least 24 hours "
                 + "before the end of the current period, and your Apple Account is charged for "
                 + "renewal within 24 hours of the period ending. You can manage or cancel it in "
                 + "your Apple Account settings at any time.")
                .font(.system(size: 11))
                .lineSpacing(3)
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
                .fixedSize(horizontal: false, vertical: true)

            HStack(spacing: 14) {
                Button("Terms of Use") {
                    if let url = URL(string: "https://birdo.app/terms") { openURL(url) }
                }
                Button("Privacy Policy") {
                    if let url = URL(string: "https://birdo.app/privacy") { openURL(url) }
                }
                Button("Manage subscription") {
                    SystemOpen.manageSubscriptions()
                }
            }
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(BirdoTheme.accent)
            .buttonStyle(.plain)
        }
        .padding(.top, 12)
    }

    // MARK: - Plan cards

    private func planCard(_ plan: PlanCardModel) -> some View {
        let accent = BirdoTheme.planAccent(plan.slug)
        // Case-insensitive compare against the server slug; no snapshot means
        // no badge (never flash CURRENT on Recon while a paid plan loads).
        let isCurrent = vpnVM.subscription
            .map { $0.plan.caseInsensitiveCompare(plan.slug) == .orderedSame } ?? false
        let product = store.product(plan: plan.slug, period: period)

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

                    Text(priceLabel(for: plan, product: product))
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(plan.slug == "RECON"
                                         ? BirdoTheme.white60
                                         : BirdoTheme.onSurface)
                }

                // 2.3.2: a paid plan's feature list must say, in words, that
                // the features below it are part of a paid subscription.
                if plan.slug != "RECON" {
                    Text("Included with a paid \(plan.name) subscription:")
                        .font(.system(size: 11, weight: .semibold))
                        .tracking(0.4)
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
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

                purchaseControl(plan, product: product, isCurrent: isCurrent)
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
    }

    /// StoreKit's localised price wins whenever it resolved: Apple sets the
    /// price per storefront and per tier, so the catalogue's GBP string is only
    /// ever a placeholder for a device that could not reach the store.
    private func priceLabel(for plan: PlanCardModel, product: Product?) -> String {
        if let product {
            return product.displayPrice + period.priceSuffix
        }
        return period == .yearly ? plan.yearlyPrice : plan.monthlyPrice
    }

    @ViewBuilder
    private func purchaseControl(_ plan: PlanCardModel,
                                 product: Product?,
                                 isCurrent: Bool) -> some View {
        if plan.slug == "RECON" {
            // Free tier: nothing to buy, and no button pretending otherwise.
            EmptyView()
        } else if isCurrent {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(BirdoTheme.green)
                Text("This is your current plan")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BirdoTheme.onSurface)
                Spacer(minLength: 0)
                Button("Manage") { SystemOpen.manageSubscriptions() }
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(BirdoTheme.accent)
                    .buttonStyle(.plain)
            }
            .padding(.top, 4)
        } else if let product {
            VStack(alignment: .leading, spacing: 6) {
                PrimaryButton(
                    authVM.isGuest
                        ? "Sign in to subscribe"
                        : "Subscribe · \(product.displayPrice)\(period.priceSuffix)",
                    loadingTitle: "Contacting the App Store…",
                    variant: .brand,
                    isLoading: store.purchasingProductId == product.id,
                    // One purchase at a time: a second tap while the App Store
                    // sheet is up is how a double charge gets attempted.
                    isEnabled: store.purchasingProductId == nil,
                    height: 48
                ) {
                    Task { await store.purchase(product) }
                }
                .accessibilityIdentifier("subscribe_\(product.id)")

                Text(period.renewalSentence + " Cancel any time in your Apple Account settings.")
                    .font(.system(size: 11))
                    .foregroundStyle(BirdoTheme.onSurfaceFaint)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 4)
        } else {
            // The plan exists, the product did not resolve. Say exactly that
            // rather than rendering a dead button.
            Text("Not available to purchase in the app right now.")
                .font(.system(size: 12))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
                .padding(.top, 4)
        }
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
/// §5.4 — feature lists match Android verbatim).
///
/// The prices here are a FALLBACK ONLY, shown when StoreKit could not resolve a
/// product. Anything actually purchasable is priced by `Product.displayPrice`,
/// because Apple — not this catalogue — decides what a storefront charges.
private struct PlanCardModel: Identifiable, Sendable {
    let slug: String
    let name: String
    let tagline: String
    let monthlyPrice: String
    let yearlyPrice: String
    let isPopular: Bool
    let features: [String]

    var id: String { slug }

    init(slug: String,
         name: String,
         tagline: String,
         monthlyPrice: String,
         yearlyPrice: String,
         isPopular: Bool,
         features: [String]) {
        self.slug = slug
        self.name = name
        self.tagline = tagline
        self.monthlyPrice = monthlyPrice
        self.yearlyPrice = yearlyPrice
        self.isPopular = isPopular
        self.features = features
    }

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
