import SwiftUI

/// Read-only plan screen.
///
/// Payments are WEBSITE-ONLY (Polar, card) — owner decision 2026-07-12, see
/// docs/IOS-PARITY-CONTRACT.md § Payments. The app displays the current plan
/// (populated from `vpn/stats`) and links out to birdo.app to change it.
/// Deliberately absent: StoreKit, purchase flows, and prices. Do not add
/// them — the website is the single source for pricing and billing.
struct SubscriptionView: View {
    @EnvironmentObject var authVM: AuthViewModel
    @Environment(\.openURL) private var openURL

    /// The only destination the app may send users to for billing.
    private static let managePlanURL =
        URL(string: "https://birdo.app/dashboard/subscription")!

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                currentPlanCard

                // Tier overview — informational only, nothing to buy here.
                VStack(spacing: 12) {
                    ForEach(Plan.allCases) { plan in
                        planCard(plan)
                    }
                }

                managePlanButton

                Text("Plans and billing are managed on birdo.app. Nothing is purchased inside the app.")
                    .font(.caption2)
                    .foregroundColor(BirdoTheme.white40)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)
            }
            .padding()
        }
        .background(BirdoTheme.background.ignoresSafeArea())
        .navigationTitle("Plan")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Current Plan

    private var currentPlanCard: some View {
        // RECON is the contract default plan; a session that has not yet
        // synced `vpn/stats` is shown as the free tier rather than blank.
        let plan = authVM.currentPlan ?? .recon
        return HStack(spacing: 12) {
            Image(systemName: plan.icon)
                .font(.title3)
                .foregroundColor(plan.color)
            VStack(alignment: .leading, spacing: 2) {
                Text("Current Plan")
                    .font(.caption)
                    .foregroundColor(BirdoTheme.white40)
                Text(plan.rawValue)
                    .font(.headline)
                    .foregroundColor(BirdoTheme.white)
            }
            Spacer()
            if let expiry = formattedExpiry(authVM.subscriptionExpiry) {
                VStack(alignment: .trailing, spacing: 2) {
                    Text("Active until")
                        .font(.caption2)
                        .foregroundColor(BirdoTheme.white40)
                    Text(expiry)
                        .font(.caption.weight(.semibold))
                        .foregroundColor(BirdoTheme.accentSoft)
                        .lineLimit(1)
                }
            }
        }
        .padding()
        .background(BirdoTheme.card)
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(BirdoTheme.border, lineWidth: 1)
        )
    }

    // MARK: - Plan Card

    private func planCard(_ plan: Plan) -> some View {
        let isCurrent = (authVM.currentPlan ?? .recon) == plan
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: plan.icon)
                    .font(.title3)
                    .foregroundColor(plan.color)
                Text(plan.rawValue)
                    .font(.headline)
                    .foregroundColor(BirdoTheme.white)
                Spacer()
                if isCurrent {
                    Text("CURRENT")
                        .font(.caption2.weight(.bold))
                        .foregroundColor(plan.color)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(plan.color.opacity(0.12))
                        .cornerRadius(6)
                }
            }

            ForEach(plan.features, id: \.self) { feature in
                HStack(spacing: 8) {
                    Image(systemName: "checkmark")
                        .font(.caption2)
                        .foregroundColor(plan.color)
                    Text(feature)
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white60)
                }
            }
        }
        .padding()
        .background(BirdoTheme.card)
        .cornerRadius(16)
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .stroke(isCurrent ? plan.color.opacity(0.6) : BirdoTheme.border,
                        lineWidth: 1)
        )
    }

    // MARK: - Manage CTA

    private var managePlanButton: some View {
        Button {
            openURL(Self.managePlanURL)
        } label: {
            HStack(spacing: 10) {
                Image(systemName: "safari")
                    .font(.headline)
                Text("Manage your plan on birdo.app")
                    .font(.headline)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundColor(BirdoTheme.white)
            .padding(.vertical, 14)
            .padding(.horizontal, 20)
            .background(BirdoTheme.primaryGradient)
            .cornerRadius(14)
        }
        .accessibilityHint("Opens birdo.app in your browser")
    }

    // MARK: - Expiry Formatting

    /// Format the `subscriptionEndsAt` timestamp for display. Parses ISO-8601
    /// (with or without fractional seconds); falls back to the sanitized raw
    /// string so an unexpected format degrades to text, never a crash.
    private func formattedExpiry(_ raw: String?) -> String? {
        guard let cleaned = sanitizedExpiry(raw) else { return nil }
        let isoFractional = ISO8601DateFormatter()
        isoFractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        let isoPlain = ISO8601DateFormatter()
        if let date = isoFractional.date(from: cleaned) ?? isoPlain.date(from: cleaned) {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        return cleaned
    }

    /// Defensively clean an untrusted expiry string coming from the backend
    /// before it is interpolated into a SwiftUI `Text`. Strips control /
    /// newline characters, trims whitespace, and clamps the length so a
    /// malformed or hostile payload cannot distort the UI. Returns `nil` when
    /// there is nothing meaningful left to show.
    private func sanitizedExpiry(_ raw: String?) -> String? {
        guard let raw = raw else { return nil }
        let stripped = raw.unicodeScalars
            .filter { !CharacterSet.controlCharacters.contains($0) }
        var cleaned = String(String.UnicodeScalarView(stripped))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        let maxLength = 40
        if cleaned.count > maxLength {
            cleaned = String(cleaned.prefix(maxLength))
        }
        return cleaned
    }
}

// MARK: - Supporting Types

/// The three service tiers. Display-only: the enum drives plan naming and
/// accent colours in the UI; entitlements are enforced server-side and the
/// plan itself is changed on the website, never in the app.
enum Plan: String, CaseIterable, Identifiable {
    case recon = "RECON"
    case operative = "OPERATIVE"
    case sovereign = "SOVEREIGN"

    var id: String { rawValue }

    var icon: String {
        switch self {
        case .recon: return "binoculars.fill"
        case .operative: return "eye.slash.fill"
        case .sovereign: return "bolt.shield.fill"
        }
    }

    /// Contract plan accents (docs/IOS-PARITY-CONTRACT.md § Emerald theme):
    /// SOVEREIGN #10B981, OPERATIVE #14B8A6, RECON #64748B.
    var color: Color {
        switch self {
        case .recon: return Color(hex: 0x64748B)
        case .operative: return Color(hex: 0x14B8A6)
        case .sovereign: return BirdoTheme.accent // #10B981
        }
    }

    var features: [String] {
        switch self {
        case .recon:
            return ["1 Device", "Standard Servers", "WireGuard Protocol"]
        case .operative:
            return ["3 Devices", "All Servers", "Stealth Mode", "Kill Switch"]
        case .sovereign:
            return ["10 Devices", "All Servers", "All Features", "Quantum Protection", "Priority Support"]
        }
    }
}
