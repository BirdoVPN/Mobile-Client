import SwiftUI

/// Port forwarding management (spec-secondary-screens.md §6). Pushed from
/// VPN Settings → Features; the entry row there carries the SOVEREIGN lock,
/// and this screen keeps its own defensive gate for the downgrade/deep-link
/// path. Enforcement is server-side — backend refusals ("Port forwarding
/// requires a Sovereign subscription", "No active VPN connection. Connect to
/// a server first.", …) render verbatim in the error surface.
struct PortForwardView: View {
    @EnvironmentObject var vpnVM: VpnViewModel
    @EnvironmentObject var authVM: AuthViewModel

    @State private var portText = ""
    /// 0 = TCP, 1 = UDP (default tcp — Android parity).
    @State private var protocolIndex = 0
    /// Local in-flight flag for POST /vpn/port-forwards — the view drives the
    /// create call itself so the "Add rule" button can spin and stay
    /// single-flight (VpnViewModel exposes no isCreating state).
    @State private var isCreating = false
    @State private var pendingDelete: PortForwardEntry?
    @State private var showDeleteConfirm = false

    // The backend's `createPortForwardSchema` accepts only `tcp` or `udp`
    // (a strict zod enum) — there is no combined option, so offering "Both"
    // produced a rule the API always rejected. Sent lower-cased on the wire.
    private let protocols = ["TCP", "UDP"]

    var body: some View {
        ZStack {
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView()

            ScrollView {
                VStack(alignment: .leading, spacing: 4) {
                    infoNote

                    if let err = vpnVM.portForwardError {
                        ErrorBanner(err)
                            .padding(.top, 8)
                    }

                    if isLockedOut {
                        lockedCard
                            .padding(.top, 8)
                    } else {
                        SectionHeader("New rule")
                        newRuleCard
                    }

                    SectionHeader("Active rules")
                    rulesList
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .padding(.bottom, 32)
                .animation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick),
                           value: vpnVM.portForwardError)
            }
        }
        .navigationTitle("Port Forwarding")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            vpnVM.loadPortForwards()
            // Keeps the defensive plan gate honest; 30 s cache makes it cheap.
            vpnVM.refreshSubscription()
        }
        .birdoConfirmDialog(
            isPresented: $showDeleteConfirm,
            title: "Delete this rule?",
            message: deleteMessage,
            iconColor: BirdoTheme.red,
            confirmLabel: "Delete",
            onConfirm: confirmDelete,
            onCancel: { pendingDelete = nil }
        )
    }

    // MARK: - Plan gate

    /// Locked only when the server snapshot POSITIVELY says the plan is below
    /// SOVEREIGN — an unloaded snapshot shows the form and lets the backend
    /// refuse (the entry-point lock in VPN Settings is the primary surface).
    private var isLockedOut: Bool {
        guard let sub = vpnVM.subscription else { return false }
        return !sub.isSovereign
    }

    private var lockedCard: some View {
        NavigationLink {
            SubscriptionView()
        } label: {
            BirdoCard(cornerRadius: BirdoTheme.Radius.md) {
                HStack(spacing: 12) {
                    Image(systemName: "lock.fill")
                        .font(.system(size: 20))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Sovereign feature")
                            .font(BirdoTheme.Fonts.titleSmall)
                            .foregroundStyle(BirdoTheme.onSurface)
                        // The backend's own refusal string, so the copy can
                        // never drift from what a rejected POST would say.
                        Text("Port forwarding requires a Sovereign subscription")
                            .font(BirdoTheme.Fonts.bodySmall)
                            .foregroundStyle(BirdoTheme.white60)
                            .fixedSize(horizontal: false, vertical: true)
                            .multilineTextAlignment(.leading)
                    }
                    Spacer(minLength: 8)
                    Image(systemName: "chevron.right")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityLabel("Premium feature — upgrade to unlock")
    }

    // MARK: - Info note

    private var infoNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle")
                .font(.system(size: 18))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
                .accessibilityHidden(true)
            Text("Forward external ports on your VPN server to a local port on your device. Useful for hosting services behind the VPN.")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.white60)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: BirdoTheme.Radius.sub, style: .continuous)
                .fill(BirdoTheme.surfaceRaised)
        )
    }

    // MARK: - New rule

    private var portIsValid: Bool {
        guard let port = Int(portText) else { return false }
        return (1...65535).contains(port)
    }

    /// Error only when non-empty and invalid — an empty field is neutral.
    private var portFieldError: String? {
        (!portText.isEmpty && !portIsValid) ? "Port must be 1-65535" : nil
    }

    private var newRuleCard: some View {
        BirdoCard(cornerRadius: BirdoTheme.Radius.md) {
            VStack(alignment: .leading, spacing: 14) {
                BirdoTextField(
                    "Internal port",
                    placeholder: "e.g. 8080",
                    text: $portText,
                    error: portFieldError,
                    keyboardType: .numberPad
                )
                .onChange(of: portText) { _, newValue in
                    let filtered = String(newValue.filter(\.isNumber).prefix(5))
                    if filtered != newValue { portText = filtered }
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Protocol")
                        .font(BirdoTheme.Fonts.bodyMedium)
                        .foregroundStyle(BirdoTheme.white60)
                    SegmentedTabs(items: protocols,
                                  selection: $protocolIndex,
                                  style: .accent)
                }

                PrimaryButton(
                    "Add rule",
                    variant: .brand,
                    icon: "plus",
                    isLoading: isCreating,
                    isEnabled: portIsValid
                ) {
                    addRule()
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func addRule() {
        guard portIsValid, let port = Int(portText), !isCreating else { return }
        let proto = protocols[protocolIndex].lowercased()
        isCreating = true
        Task {
            do {
                let entry = try await APIClient.shared.createPortForward(port: port, proto: proto)
                vpnVM.portForwards.append(entry)
                vpnVM.portForwardError = nil
                portText = ""
            } catch {
                // Backend refusals shown verbatim on this screen (S2).
                vpnVM.portForwardError = error.localizedDescription
                _ = authVM.logoutIfUnauthorized(error)
            }
            isCreating = false
        }
    }

    // MARK: - Active rules

    @ViewBuilder
    private var rulesList: some View {
        if vpnVM.isLoadingPortForwards && vpnVM.portForwards.isEmpty {
            HStack {
                Spacer()
                ProgressView()
                    .tint(BirdoTheme.accent)
                Spacer()
            }
            .padding(.vertical, 24)
        } else if vpnVM.portForwards.isEmpty {
            BirdoCard(cornerRadius: BirdoTheme.Radius.md) {
                Text("No port forwarding rules yet. Add one above to get started.")
                    .font(BirdoTheme.Fonts.bodyMedium)
                    .foregroundStyle(BirdoTheme.white60)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            VStack(spacing: 4) {
                ForEach(vpnVM.portForwards) { entry in
                    ruleRow(entry)
                }
            }
        }
    }

    private func ruleRow(_ entry: PortForwardEntry) -> some View {
        BirdoCard(cornerRadius: BirdoTheme.Radius.md, verticalPadding: 10) {
            HStack(spacing: 12) {
                Image(systemName: "arrow.left.arrow.right")
                    .font(.system(size: 20))
                    .foregroundStyle(BirdoTheme.green)
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 0) {
                        Text(entry.externalPort.map(String.init) ?? "—")
                            .font(BirdoTheme.Fonts.titleSmall)
                            .monospacedDigit()
                            .foregroundStyle(BirdoTheme.onSurface)
                        Text(" → ")
                            .font(BirdoTheme.Fonts.titleSmall)
                            .foregroundStyle(BirdoTheme.onSurfaceFaint)
                        Text(String(entry.internalPort))
                            .font(BirdoTheme.Fonts.titleSmall)
                            .monospacedDigit()
                            .foregroundStyle(BirdoTheme.onSurface)
                    }
                    Text(entry.proto.uppercased())
                        .font(BirdoTheme.Fonts.labelSmall)
                        .foregroundStyle(BirdoTheme.white60)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(
                            RoundedRectangle(cornerRadius: 4, style: .continuous)
                                .fill(BirdoTheme.surfaceRaised)
                        )
                }
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(
                    "Port \(entry.externalPort.map(String.init) ?? "unassigned") to \(entry.internalPort), \(entry.proto.uppercased())"
                )

                Spacer(minLength: 8)

                Button {
                    pendingDelete = entry
                    showDeleteConfirm = true
                } label: {
                    Image(systemName: "trash")
                        .font(.system(size: 18))
                        .foregroundStyle(BirdoTheme.red)
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Delete rule")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    // MARK: - Delete confirmation

    /// Deleting tears down a live DNAT mapping — spell out the consequence.
    private var deleteMessage: String {
        guard let target = pendingDelete else { return "" }
        let external = target.externalPort.map(String.init) ?? String(target.internalPort)
        return "Port \(external) → \(target.internalPort) will stop being forwarded. Any service relying on it becomes unreachable."
    }

    private func confirmDelete() {
        // `pendingDelete` is read here, not in the dialog modifier, because
        // the modifier clears `isPresented` before invoking the callback.
        if let target = pendingDelete {
            vpnVM.deletePortForward(id: target.id)
        }
        pendingDelete = nil
    }
}

// MARK: - Model

/// One row of `GET /vpn/port-forwards`. The wire field is `protocol` — a
/// Swift keyword — so it maps to `proto`; the wire's extra `enabled` flag is
/// deliberately not decoded (the UI has no disabled-rule state).
/// Referenced by APIClient (decode) and VpnViewModel (published list).
struct PortForwardEntry: Identifiable, Decodable, Equatable, Sendable {
    let id: String
    let internalPort: Int
    let externalPort: Int?
    let proto: String

    private enum CodingKeys: String, CodingKey {
        case id, internalPort, externalPort
        case proto = "protocol"
    }
}
