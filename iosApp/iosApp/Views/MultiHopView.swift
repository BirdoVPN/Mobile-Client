import SwiftUI

/// Multi-hop (double VPN) — entry and exit server selection.
///
/// SOVEREIGN-only (spec-platform-constraints §3.5): the gate reads the
/// server-truth plan from GET /vpn/stats via `vpnVM.isSovereign` — never
/// StoreKit, never the auth method. Non-Sovereign users get the upsell card
/// (Android's exact snackbar copy) routing to the Subscription screen, and
/// the gate is re-validated on entry so a downgraded plan can never
/// resurrect the paid feature. The backend refuses anyway — this lock is
/// cosmetic, the server is the authority.
struct MultiHopView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var entryExpanded = false
    @State private var exitExpanded = false
    @State private var selectedEntry: ServerInfo?
    @State private var selectedExit: ServerInfo?
    @State private var showSubscription = false

    /// Only nodes the user can actually use — locked (`!accessible`) and
    /// offline nodes must never be selectable as hops.
    private var selectableServers: [ServerInfo] {
        vpnVM.servers.filter { $0.isOnline && $0.accessible }
    }

    private var sameServerWarning: Bool {
        guard let e = selectedEntry, let x = selectedExit else { return false }
        return e.id == x.id
    }

    private var canConnect: Bool {
        vpnVM.isSovereign && selectedEntry != nil && selectedExit != nil
            && !sameServerWarning && !vpnVM.isConnecting
    }

    var body: some View {
        ZStack {
            // Pushed sub-screen contract: opaque black base + own canvas.
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView()

            ScrollView {
                VStack(spacing: BirdoTheme.Spacing.lg) {
                    if vpnVM.isSovereign {
                        pickerContent
                    } else if vpnVM.subscription == nil {
                        // Plan not hydrated yet — don't flash the lock at a
                        // Sovereign user on a slow first load.
                        ProgressView()
                            .tint(BirdoTheme.accent)
                            .padding(.top, BirdoTheme.Spacing.xxxl)
                    } else {
                        lockedContent
                    }
                }
                .padding(.horizontal, BirdoTheme.Spacing.screenH)
                .padding(.vertical, BirdoTheme.Spacing.screenV)
            }
        }
        .navigationTitle("Multi-Hop")
        .navigationBarTitleDisplayMode(.inline)
        .navigationDestination(isPresented: $showSubscription) {
            SubscriptionView()
        }
        .task {
            // Re-validate the gate on entry; the 30s cache keeps this cheap.
            vpnVM.refreshSubscription()
        }
    }

    // MARK: - Unlocked content

    @ViewBuilder
    private var pickerContent: some View {
        // Header info
        BirdoCard {
            HStack(spacing: BirdoTheme.Spacing.md) {
                Image(systemName: "arrow.triangle.branch")
                    .font(.title3)
                    .foregroundColor(BirdoTheme.accent)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("Double VPN")
                        .font(BirdoTheme.Fonts.titleMedium)
                        .foregroundColor(BirdoTheme.onSurface)
                    Text("Route traffic through two servers for extra privacy")
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundColor(BirdoTheme.onSurfaceMuted)
                }
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }

        serverSelector(
            label: "Entry Server",
            icon: "1.circle.fill",
            server: selectedEntry,
            expanded: $entryExpanded,
            onSelect: { selectedEntry = $0 }
        )

        Image(systemName: "arrow.down")
            .font(.system(size: 18, weight: .semibold))
            .foregroundColor(BirdoTheme.white40)
            .accessibilityHidden(true)

        serverSelector(
            label: "Exit Server",
            icon: "2.circle.fill",
            server: selectedExit,
            expanded: $exitExpanded,
            onSelect: { selectedExit = $0 }
        )

        if sameServerWarning {
            // Android copy, verbatim (spec-home-servers-consent §3.7).
            Text("Entry and exit must be different servers.")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundColor(BirdoTheme.red)
                .frame(maxWidth: .infinity)
                .multilineTextAlignment(.center)
        }

        PrimaryButton(
            "Connect Multi-Hop",
            variant: .brand,
            icon: "power",
            isEnabled: canConnect
        ) {
            guard let entry = selectedEntry, let exit = selectedExit else { return }
            vpnVM.connectMultiHop(entryId: entry.id, exitId: exit.id)
            dismiss()
        }
        .padding(.top, BirdoTheme.Spacing.sm)
    }

    // MARK: - Locked content (plan < SOVEREIGN)

    @ViewBuilder
    private var lockedContent: some View {
        BirdoCard {
            VStack(spacing: BirdoTheme.Spacing.lg) {
                ZStack {
                    Circle()
                        .fill(BirdoTheme.white05)
                        .frame(width: 64, height: 64)
                    Image(systemName: "lock.fill")
                        .font(.system(size: 24))
                        .foregroundColor(BirdoTheme.onSurfaceMuted)
                }
                .accessibilityHidden(true)

                Text("Multi-Hop")
                    .font(BirdoTheme.Fonts.titleLarge)
                    .foregroundColor(BirdoTheme.onSurface)

                PlanBadge("SOVEREIGN", locked: true)

                // Android's upsell copy, verbatim.
                Text("Multi-Hop is a SOVEREIGN feature. Upgrade to enable.")
                    .font(BirdoTheme.Fonts.bodyMedium)
                    .foregroundColor(BirdoTheme.onSurfaceMuted)
                    .multilineTextAlignment(.center)

                PrimaryButton("View plans", variant: .brand) {
                    // Upgrade flow parity: refresh the plan truth, then push
                    // the (informational-only) Subscription screen.
                    vpnVM.refreshSubscription(force: true)
                    showSubscription = true
                }
            }
            .frame(maxWidth: .infinity)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Multi-Hop — SOVEREIGN plan required")
    }

    // MARK: - Server Selector

    @ViewBuilder
    private func serverSelector(
        label: String,
        icon: String,
        server: ServerInfo?,
        expanded: Binding<Bool>,
        onSelect: @escaping (ServerInfo) -> Void
    ) -> some View {
        VStack(spacing: 0) {
            // Header — Android's pair-card overline style ("ENTRY SERVER" /
            // "EXIT SERVER" uppercase accent) with the chosen node inline.
            Button {
                withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.standard)) {
                    expanded.wrappedValue.toggle()
                }
            } label: {
                HStack(spacing: BirdoTheme.Spacing.sm) {
                    Image(systemName: icon)
                        .foregroundColor(BirdoTheme.accent)
                        .accessibilityHidden(true)
                    Text(label.uppercased())
                        .font(.system(size: 12, weight: .bold))
                        .tracking(1)
                        .foregroundColor(BirdoTheme.accent)
                    Spacer()
                    if let srv = server {
                        Text("\(srv.flag) \(srv.name)")
                            .font(BirdoTheme.Fonts.titleSmall)
                            .foregroundColor(BirdoTheme.onSurface)
                            .lineLimit(1)
                    } else {
                        Text("Choose…")
                            .font(BirdoTheme.Fonts.titleSmall)
                            .foregroundColor(BirdoTheme.white40)
                    }
                    Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white40)
                }
                .padding(BirdoTheme.Spacing.lg)
                .background(BirdoTheme.surface)
                .contentShape(Rectangle())
            }
            .accessibilityLabel(label)
            .accessibilityValue(server.map { $0.name } ?? "Not chosen")

            // Expandable list — usable nodes only.
            if expanded.wrappedValue {
                VStack(spacing: 0) {
                    ForEach(selectableServers) { srv in
                        Button {
                            onSelect(srv)
                            withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
                                expanded.wrappedValue = false
                            }
                        } label: {
                            HStack(spacing: BirdoTheme.Spacing.sm) {
                                Text(srv.flag)
                                    .font(.title3)
                                Text(srv.name)
                                    .font(BirdoTheme.Fonts.bodyMedium)
                                    .foregroundColor(BirdoTheme.onSurface)
                                    .lineLimit(1)
                                Spacer()
                                Text("\(srv.load)%")
                                    .font(BirdoTheme.Fonts.mono)
                                    .monospacedDigit()
                                    .foregroundColor(LoadBar.color(for: Int(srv.load)))
                                if server?.id == srv.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundColor(BirdoTheme.green)
                                        .accessibilityHidden(true)
                                }
                            }
                            .padding(.horizontal, BirdoTheme.Spacing.lg)
                            .padding(.vertical, 10)
                            .contentShape(Rectangle())
                        }
                        Divider().overlay(BirdoTheme.hairlineSoft)
                    }

                    if selectableServers.isEmpty {
                        Text("No servers available")
                            .font(BirdoTheme.Fonts.bodySmall)
                            .foregroundColor(BirdoTheme.onSurfaceMuted)
                            .padding(BirdoTheme.Spacing.lg)
                    }
                }
                .background(BirdoTheme.surfaceRaised)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: BirdoTheme.Radius.md))
        .overlay(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.md)
                .strokeBorder(BirdoTheme.Gradients.glassStroke, lineWidth: 1)
        )
    }
}
