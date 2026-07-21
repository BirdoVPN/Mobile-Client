import SwiftUI

/// VPN Settings (pushed from Settings) — Android VpnSettingsScreen parity
/// (spec-secondary-screens §2) with the platform deltas:
///   - NO Stealth Mode row: iOS has no Xray transport, so the toggle is
///     omitted entirely (platform-constraints §3.7) — not shown locked.
///   - Kill switch confirm-before-disable drives SettingsViewModel's T2 API
///     (`killSwitchToggleBinding` + confirm/cancel) so the switch never
///     bounces: the binding echoes "off" while the dialog is up, Cancel
///     animates it back on, Confirm commits without a snap.
///   - Custom WireGuard port persists per valid keystroke (T3). The radio
///     selection is LOCAL UI state — deriving it from the persisted port is
///     the exact bug that made "Custom" unreachable.
///   - Apply-on-change (§0.6): toggle flips blip via the ViewModel's 1200 ms
///     debounce; field edits (DNS/port/MTU) persist immediately but re-apply
///     ONCE on screen exit via `commitPendingReapply()` in `.onDisappear`.
///
/// Pushed-screen contract: opaque black base + own PixelCanvasView (the
/// app-root canvas is covered by pushed content). T1: no nested
/// NavigationView — the outer NavigationStack owns the push; the system bar
/// is hidden in favor of the Android-style glass top bar with a back chip.
@MainActor
struct VpnSettingsView: View {
    @EnvironmentObject var settingsVM: SettingsViewModel
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.dismiss) private var dismiss

    /// WireGuard port radio selection — LOCAL UI state (T3): "Custom" is a
    /// mode the user chose, never re-derived from the persisted port.
    private enum PortChoice {
        case auto, port51820, port53, custom
    }

    @State private var portChoice: PortChoice = .auto
    @State private var customPortText = ""
    @State private var customPortValid = false
    @State private var mtuText = ""
    @State private var didSyncFromStore = false
    @State private var showSubscription = false

    var body: some View {
        ZStack {
            BirdoTheme.black.ignoresSafeArea()
            PixelCanvasView()

            VStack(spacing: 0) {
                topBar

                ScrollView {
                    VStack(alignment: .leading, spacing: 4) {
                        SectionHeader("Security")
                        VpnToggleRow(icon: "checkmark.shield.fill", iconColor: BirdoTheme.green,
                                     title: "Kill Switch",
                                     description: "Block all traffic if VPN disconnects",
                                     isOn: settingsVM.killSwitchToggleBinding)
                        VpnToggleRow(icon: "lock.fill", iconColor: BirdoTheme.accent,
                                     title: "Quantum Protection",
                                     description: "Add post-quantum pre-shared key exchange via BirdoPQ v1 (ML-KEM-1024, NIST FIPS 203). Protects against future quantum computer attacks.",
                                     isOn: $settingsVM.quantumProtectionEnabled)

                        SectionHeader("Network")
                        VpnToggleRow(icon: "network", iconColor: BirdoTheme.blue,
                                     title: "Local Network Sharing",
                                     description: "Allow access to devices on your local network (printers, NAS, etc.) while connected to VPN",
                                     isOn: $settingsVM.localNetworkSharing)

                        SectionHeader("DNS")
                        customDnsRow
                        // Locked rows render checked = persisted && unlocked
                        // (§0.5), so the fields also stay hidden while locked.
                        if vpnVM.isSovereign && settingsVM.customDnsEnabled {
                            dnsFields
                        }

                        SectionHeader("WireGuard")
                        portCard
                        mtuCard
                        infoNote

                        SectionHeader("Features")
                        portForwardingRow

                        Spacer().frame(height: 32)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 8)
                }
            }
        }
        .overlay(alignment: .bottom) { reapplyToast }
        .navigationBarBackButtonHidden(true)
        // Android parity: sub-screens hide the bottom tab bar too.
        .toolbar(.hidden, for: .navigationBar, .tabBar)
        .navigationDestination(isPresented: $showSubscription) { SubscriptionView() }
        .birdoConfirmDialog(
            isPresented: $settingsVM.showKillSwitchDisableConfirm,
            title: "Disable kill switch?",
            // Android's copy minus its trailing "Always-on VPN" sentence
            // (Android-only system setting; an iOS-equivalent clause is
            // pending owner review — spec-secondary-screens warning 4).
            message: "If the VPN drops while the kill switch is off, your apps can fall back to the normal, unencrypted connection and briefly leak your real IP address and DNS queries. For the strongest protection keep it on.",
            confirmLabel: "Turn off anyway",
            onConfirm: { settingsVM.confirmDisableKillSwitch() },
            onCancel: { settingsVM.cancelDisableKillSwitch() }
        )
        .onAppear { syncFromStore() }
        .onDisappear {
            // §0.6 path 3: one blip with the FINAL field values (DNS/port/MTU).
            settingsVM.commitPendingReapply()
        }
    }

    // MARK: - Top bar (Android BirdoTopBar with back chip)

    private var topBar: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Button {
                    dismiss()
                } label: {
                    ZStack {
                        Circle()
                            .fill(BirdoTheme.white05)
                            .frame(width: 40, height: 40)
                        Image(systemName: "chevron.left")
                            .font(.system(size: 17, weight: .semibold))
                            .foregroundStyle(BirdoTheme.onSurface)
                    }
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(PressScaleButtonStyle())
                .accessibilityLabel("Back")

                Text("VPN Settings")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(BirdoTheme.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(minHeight: 56)

            Rectangle()
                .fill(BirdoTheme.hairlineSoft)
                .frame(height: 1)
        }
        .background(BirdoTheme.glassStrong.ignoresSafeArea(edges: .top))
    }

    // MARK: - DNS (SOVEREIGN-gated, §0.5)

    @ViewBuilder
    private var customDnsRow: some View {
        if vpnVM.isSovereign {
            VpnToggleRow(icon: "server.rack", iconColor: BirdoTheme.accent,
                         title: "Custom DNS Servers",
                         description: "Use your own DNS servers instead of the VPN defaults",
                         isOn: $settingsVM.customDnsEnabled)
        } else {
            VpnLockedRow(icon: "server.rack",
                         title: "Custom DNS Servers",
                         description: "Use your own DNS servers instead of the VPN defaults",
                         action: routeToUpgrade)
        }
    }

    /// Fields hold raw typing; the ViewModel persists only valid-or-empty
    /// values and VPNManager re-validates at tunnel-build time regardless.
    private var dnsFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            BirdoTextField("Primary DNS",
                           placeholder: "e.g. 1.1.1.1",
                           text: $settingsVM.customDnsPrimary,
                           error: dnsError(settingsVM.customDnsPrimary),
                           keyboardType: .decimalPad)
            BirdoTextField("Secondary DNS (optional)",
                           placeholder: "e.g. 1.0.0.1",
                           text: $settingsVM.customDnsSecondary,
                           error: dnsError(settingsVM.customDnsSecondary),
                           keyboardType: .decimalPad)
        }
        .padding(.top, 8)
        .padding(.bottom, 4)
    }

    /// Error only when non-blank AND invalid (blank = "use VPN defaults").
    /// Same predicate the tunnel builder uses, so UI and gate never drift.
    private func dnsError(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !SettingsViewModel.isValidDnsAddress(trimmed) else { return nil }
        return "Enter a valid IP address"
    }

    // MARK: - WireGuard port (T3)

    private var portCard: some View {
        BirdoCard(cornerRadius: 14) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 14) {
                    Image(systemName: "wifi.router")
                        .font(.system(size: 20))
                        .foregroundStyle(BirdoTheme.green)
                        .frame(width: 24)
                        .accessibilityHidden(true)
                    Text("WireGuard Port")
                        .font(BirdoTheme.Fonts.titleSmall)
                        .foregroundStyle(BirdoTheme.onSurface)
                }

                portRadio("Automatic", isSelected: portChoice == .auto) {
                    selectPreset(.auto, value: "auto")
                }
                portRadio("51820", isSelected: portChoice == .port51820) {
                    selectPreset(.port51820, value: "51820")
                }
                portRadio("53", isSelected: portChoice == .port53) {
                    selectPreset(.port53, value: "53")
                }
                portRadio("Custom", isSelected: portChoice == .custom) {
                    selectCustom()
                }

                if portChoice == .custom {
                    // Error shown while invalid AND while empty, so the UI
                    // never implies a half-typed port is applied.
                    BirdoTextField("Port number",
                                   placeholder: "",
                                   text: $customPortText,
                                   error: customPortValid ? nil : "Enter a port between 1 and 65535",
                                   keyboardType: .numberPad)
                        .onChange(of: customPortText) { _, newValue in
                            let filtered = String(newValue.filter(\.isNumber).prefix(5))
                            if filtered != newValue { customPortText = filtered }
                            // Valid values persist per keystroke; invalid or
                            // partial text persists nothing (T3 fix).
                            customPortValid = settingsVM.applyCustomWireGuardPort(filtered)
                        }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func portRadio(_ label: String, isSelected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                ZStack {
                    Circle()
                        .strokeBorder(isSelected ? BirdoTheme.accent : BirdoTheme.white40, lineWidth: 2)
                        .frame(width: 20, height: 20)
                    if isSelected {
                        Circle()
                            .fill(BirdoTheme.accent)
                            .frame(width: 10, height: 10)
                    }
                }
                Text(label)
                    .font(BirdoTheme.Fonts.bodyMedium)
                    .foregroundStyle(Color.white.opacity(0.85))
                Spacer(minLength: 0)
            }
            .frame(minHeight: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isSelected] : [])
    }

    private func selectPreset(_ choice: PortChoice, value: String) {
        withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
            portChoice = choice
        }
        // Presets persist immediately (+ pending reapply-on-exit).
        settingsVM.setWireGuardPortPreset(value)
    }

    private func selectCustom() {
        withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
            portChoice = .custom
        }
        // Re-validate whatever is already in the field: a valid prefilled
        // port persists right away; empty/partial persists nothing until
        // the user types a valid value.
        customPortValid = settingsVM.applyCustomWireGuardPort(customPortText)
    }

    // MARK: - WireGuard MTU

    private var mtuCard: some View {
        BirdoCard(cornerRadius: 14) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: "slider.horizontal.3")
                        .font(.system(size: 20))
                        .foregroundStyle(BirdoTheme.yellow)
                        .frame(width: 24)
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("WireGuard MTU")
                            .font(BirdoTheme.Fonts.titleSmall)
                            .foregroundStyle(BirdoTheme.onSurface)
                        Text("Packet size — lower values improve reliability on unstable networks")
                            .font(BirdoTheme.Fonts.bodySmall)
                            .foregroundStyle(BirdoTheme.onSurfaceMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                mtuAutoRow

                if settingsVM.wireGuardMtu != 0 {
                    BirdoTextField(placeholder: "e.g. 1420",
                                   text: $mtuText,
                                   supportingText: "Valid range: 1280-1500",
                                   keyboardType: .numberPad)
                        .onChange(of: mtuText) { _, newValue in
                            let filtered = String(newValue.filter(\.isNumber).prefix(4))
                            if filtered != newValue { mtuText = filtered }
                            // The ViewModel clamps to 1280–1500 on persist; 0
                            // is the "Automatic" sentinel and is never written
                            // from here (an empty field keeps the last value).
                            if let value = Int32(filtered), value > 0 {
                                settingsVM.wireGuardMtu = value
                            }
                        }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var mtuAutoRow: some View {
        let isAuto = settingsVM.wireGuardMtu == 0
        return Button {
            withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
                if isAuto {
                    // Unchecking reveals the field pre-seeded with Android's
                    // starting value.
                    settingsVM.wireGuardMtu = 1420
                    mtuText = "1420"
                } else {
                    settingsVM.wireGuardMtu = 0
                }
            }
        } label: {
            HStack(spacing: 8) {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .fill(isAuto ? BirdoTheme.accent : Color.clear)
                    .overlay(
                        RoundedRectangle(cornerRadius: 4, style: .continuous)
                            .strokeBorder(isAuto ? BirdoTheme.accent : BirdoTheme.white40, lineWidth: 2)
                    )
                    .overlay {
                        if isAuto {
                            Image(systemName: "checkmark")
                                .font(.system(size: 12, weight: .bold))
                                .foregroundStyle(.white)
                        }
                    }
                    .frame(width: 20, height: 20)
                Text("Automatic (server default)")
                    .font(BirdoTheme.Fonts.bodyMedium)
                    .foregroundStyle(Color.white.opacity(0.85))
                Spacer(minLength: 0)
            }
            .frame(minHeight: 32)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Automatic (server default)")
        .accessibilityValue(isAuto ? "On" : "Off")
        .accessibilityAddTraits(.isButton)
    }

    // MARK: - Info note (§2.13 — exact copy)

    private var infoNote: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "info.circle")
                .font(.system(size: 16))
                .foregroundStyle(BirdoTheme.onSurfaceFaint)
                .accessibilityHidden(true)
            Text("Changes apply when you leave this screen — if you're connected, the VPN reconnects for a moment (still protected) to apply them")
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(BirdoTheme.surfaceRaised)
        )
        .padding(.top, 4)
    }

    // MARK: - Features (Port Forwarding, SOVEREIGN-gated)

    @ViewBuilder
    private var portForwardingRow: some View {
        if vpnVM.isSovereign {
            NavigationLink {
                PortForwardView()
            } label: {
                VpnLinkRow(icon: "arrow.left.arrow.right", iconColor: BirdoTheme.blue,
                           title: "Port Forwarding",
                           description: "Expose ports through your VPN tunnel")
            }
            .buttonStyle(PressScaleButtonStyle())
        } else {
            VpnLockedRow(icon: "arrow.left.arrow.right",
                         title: "Port Forwarding",
                         description: "Expose ports through your VPN tunnel",
                         action: routeToUpgrade)
        }
    }

    /// §0.5: every lock affordance does fetchSubscription() then routes to
    /// the Subscription screen — there is no per-feature upsell.
    private func routeToUpgrade() {
        vpnVM.refreshSubscription()
        showSubscription = true
    }

    // MARK: - Reapply blip toast

    private var reapplyToast: some View {
        ZStack {
            if vpnVM.isReapplyingSettings {
                HStack(spacing: 10) {
                    ProgressView()
                        .controlSize(.small)
                        .tint(BirdoTheme.onSurface)
                    Text("Applying settings — reconnecting…")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(BirdoTheme.onSurface)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .fill(BirdoTheme.surfaceElevated)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
                )
                .padding(.bottom, 24)
                .transition(.opacity.combined(with: .move(edge: .bottom)))
                .accessibilityElement(children: .combine)
            }
        }
        .animation(BirdoTheme.Motion.decel(BirdoTheme.Motion.standard),
                   value: vpnVM.isReapplyingSettings)
    }

    // MARK: - One-time local-state sync

    /// Seed the LOCAL radio/field state from the persisted values once, on
    /// first appear. This is the only direction data flows from the store
    /// into the radio — continuous derivation is the T3 bug.
    private func syncFromStore() {
        guard !didSyncFromStore else { return }
        didSyncFromStore = true

        switch settingsVM.wireGuardPort {
        case "auto":  portChoice = .auto
        case "51820": portChoice = .port51820
        case "53":    portChoice = .port53
        default:
            portChoice = .custom
            customPortText = settingsVM.wireGuardPort
            customPortValid = SettingsViewModel.isValidPortText(settingsVM.wireGuardPort)
        }

        if settingsVM.wireGuardMtu != 0 {
            mtuText = String(settingsVM.wireGuardMtu)
        }
    }
}

// MARK: - Rows (Android §2: card r14, plain 22pt icon, titleSmall + desc)

/// Whole-row toggle target announcing a single switch — the inner Toggle is
/// a visual only; the surrounding Button handles the tap (Android §0.3).
private struct VpnToggleRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String
    @Binding var isOn: Bool

    var body: some View {
        Button {
            withAnimation(BirdoTheme.Motion.easeStandard(BirdoTheme.Motion.quick)) {
                isOn.toggle()
            }
        } label: {
            BirdoCard(cornerRadius: 14, horizontalPadding: 16, verticalPadding: 14) {
                HStack(spacing: 14) {
                    Image(systemName: icon)
                        .font(.system(size: 20))
                        .foregroundStyle(iconColor)
                        .frame(width: 24)
                    VpnRowText(title: title, description: description)
                    Spacer(minLength: 8)
                    Toggle("", isOn: $isOn)
                        .labelsHidden()
                        .tint(BirdoTheme.accent)
                        .allowsHitTesting(false)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityRepresentation {
            Toggle(isOn: $isOn) { Text(title) }
        }
    }
}

/// Plan-locked row (§0.3): switch replaced by a lock, leading icon dimmed,
/// whole row routes to the upgrade flow.
private struct VpnLockedRow: View {
    let icon: String
    let title: String
    let description: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BirdoCard(cornerRadius: 14, horizontalPadding: 16, verticalPadding: 14) {
                HStack(spacing: 14) {
                    Image(systemName: icon)
                        .font(.system(size: 20))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                        .frame(width: 24)
                    VpnRowText(title: title, description: description)
                    Spacer(minLength: 8)
                    Image(systemName: "lock.fill")
                        .font(.system(size: 18))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(title). Premium feature — upgrade to unlock")
        .accessibilityAddTraits(.isButton)
    }
}

/// Navigation row: plain icon + title/desc + trailing chevron (faint).
private struct VpnLinkRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String

    var body: some View {
        BirdoCard(cornerRadius: 14, horizontalPadding: 16, verticalPadding: 14) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundStyle(iconColor)
                    .frame(width: 24)
                    .accessibilityHidden(true)
                VpnRowText(title: title, description: description)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(BirdoTheme.onSurfaceFaint)
                    .accessibilityHidden(true)
            }
        }
    }
}

private struct VpnRowText: View {
    let title: String
    let description: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(BirdoTheme.Fonts.titleSmall)
                .foregroundStyle(BirdoTheme.onSurface)
            Text(description)
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                .lineLimit(3)
        }
        .multilineTextAlignment(.leading)
    }
}
