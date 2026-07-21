import SwiftUI

/// Plan chip: capsule filled with the plan-identity gradient, uppercased slug
/// in 12pt bold white (Profile identity card, Subscription hero). The lock
/// variant prefixes a small lock glyph and dims the chip — for surfaces that
/// advertise a plan the user does not have.
/// Spec: spec-secondary-screens.md §3.1 (plan chip), §0.1 (planGradient);
/// plan slugs are free-form strings — unknown slugs fall back to slate.
struct PlanBadge: View {
    let plan: String      // "RECON" | "OPERATIVE" | "SOVEREIGN" (any case)
    var locked: Bool

    init(_ plan: String, locked: Bool = false) {
        self.plan = plan
        self.locked = locked
    }

    var body: some View {
        HStack(spacing: 4) {
            if locked {
                Image(systemName: "lock.fill")
                    .font(.system(size: 9, weight: .bold))
            }
            Text(plan.uppercased())
                .font(.system(size: 12, weight: .bold))
                .lineLimit(1)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(Capsule().fill(BirdoTheme.planGradient(plan)))
        .opacity(locked ? 0.75 : 1)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(
            locked
                ? "Locked — requires the \(plan.uppercased()) plan"
                : BirdoTheme.planDisplayName(plan)
        )
    }
}
