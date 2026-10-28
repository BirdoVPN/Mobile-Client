import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Settings tab root — Android SettingsScreen parity (spec-secondary-screens
/// §1). iOS-specific notes:
///   - No Appearance/Theme control (MR-1616). The iOS design system is
///     dark-only (BirdoTheme: "dark-only"; both window roots pin
///     `.preferredColorScheme(.dark)`), so its Light and System options
///     changed nothing. They are removed rather than kept as dead choices; a
///     theme control returns with a light palette, if one is ever built.
///   - No Notifications toggle (MR-1615). The iOS app posts no notifications
///     at all (it never imports UserNotifications), so the toggle switched
///     nothing. Show IP Address and Show Server Location are absent for the
///     same reason: they detail notifications that do not exist.
///   - "Notification Settings" deep-links to the app's page in the system
///     Settings app, which is a destination, not a display preference.
///   - Split Tunneling: per-app VPN is MDM-only on iOS, so the section is
///     OMITTED entirely (platform-constraints §1.4) — not shown locked.
///
/// Account/session rows (voucher, policies, sign out, delete) live on the
/// Profile tab per Android — this screen no longer hosts them.
///
/// T1 fix: NO nested NavigationView — ContentView's NavigationStack owns push
/// behavior; this screen hides the system bar and renders the Android-style
/// glass top bar itself. Tab-root contract: background stays transparent so
/// the app-root PixelCanvas shows through.
@MainActor
struct SettingsView: View {
    @EnvironmentObject var settingsVM: SettingsViewModel
    /// Needed since Custom DNS / Port Forwarding moved up from the VPN
    /// Settings sub-page: Port Forwarding is SOVEREIGN-gated and Custom DNS
    /// follows the plan's client-config flag (every plan, owner item 40); a
    /// locked row routes to the upgrade flow.
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.openURL) private var openURL
    /// Backstop for the reapply commit — see the .onChange below.
    @Environment(\.scenePhase) private var scenePhase

    /// Upgrade destination for the two locked VPN rows (§0.5).
    @State private var showSubscription = false
    /// MR-1094: why App Lock could not be turned on (no device passcode).
    @State private var appLockRefusal: String?

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    privacySection

                    SectionHeader("Connection")
                    SettingsToggleRow(icon: "wifi", iconColor: BirdoTheme.blue,
                                      title: "Auto-Connect",
                                      description: "Connect to VPN on app startup",
                                      isOn: $settingsVM.autoConnect)

                    // The system-settings link lives here too. Splitting it into
                    // its own "Notifications" header left two adjacent sections
                    // whose names overlapped, which reads worse than either the
                    // layout before or the one intended.
                    SectionHeader("Display")
                    SettingsLinkButton(icon: "bell.badge", iconColor: BirdoTheme.white60,
                                       title: "Notification Settings",
                                       description: "Open system notification settings",
                                       trailing: "arrow.up.forward.square") {
                        SystemOpen.appSettings()
                    }

                    vpnSection

                    SectionHeader("About")
                    // The policies also live on the Profile tab, but they are
                    // not account based and this is where a signed-out user
                    // looks for them (5.1.1(v)). Both open in the browser and
                    // need no account.
                    SettingsLinkButton(icon: "checkmark.shield", iconColor: BirdoTheme.accent,
                                       title: "Privacy Policy",
                                       description: "birdo.app/privacy",
                                       trailing: "arrow.up.forward.square") {
                        if let url = URL(string: "https://birdo.app/privacy") { openURL(url) }
                    }
                    SettingsLinkButton(icon: "doc.text", iconColor: BirdoTheme.white60,
                                       title: "Terms of Service",
                                       description: "birdo.app/terms",
                                       trailing: "arrow.up.forward.square") {
                        if let url = URL(string: "https://birdo.app/terms") { openURL(url) }
                    }
                    // MR-1688: the notices of the open-source code the app
                    // ships (WireGuardKit, wireguard-go, ...), which their
                    // licences require to travel with it.
                    NavigationLink {
                        AcknowledgementsView()
                    } label: {
                        SettingsLinkRow(icon: "doc.plaintext", iconColor: BirdoTheme.white60,
                                        title: "Acknowledgements",
                                        description: "Open-source software in this app")
                    }
                    .buttonStyle(PressScaleButtonStyle())
                    aboutCard

                    Spacer().frame(height: 32)
                }
                .padding(.horizontal, BirdoTheme.Spacing.screenH)
                .padding(.vertical, BirdoTheme.Spacing.screenV)
            }
        }
        .modifier(HideNavigationBar())
        .navigationDestination(isPresented: $showSubscription) { SubscriptionView() }
        .birdoConfirmDialog(
            isPresented: $settingsVM.showKillSwitchDisableConfirm,
            title: "Disable kill switch?",
            // Android's copy minus its trailing "Always-on VPN" sentence
            // (Android-only system setting; an iOS-equivalent clause is
            // pending owner review — spec-secondary-screens warning 4).
            message: "If the VPN drops while the kill switch is off, your apps fall back to your normal, unencrypted connection and can expose your real IP address and DNS queries until it reconnects. For the strongest protection, keep it on.",
            confirmLabel: "Turn off anyway",
            onConfirm: { settingsVM.confirmDisableKillSwitch() },
            onCancel: { settingsVM.cancelDisableKillSwitch() }
        )
        .onDisappear {
            // §0.6 path 3, same contract VpnSettingsView honours for the port
            // and MTU fields: the DNS fields persist per keystroke but only
            // FLAG a reapply, so leaving the screen fires the one blip with
            // the final values. No-op when nothing was edited.
            settingsVM.commitPendingReapply()
        }
        // BACKSTOP. On the sub-page this contract hung off a pushed screen's
        // pop, which always happens. Here it hangs off a TAB ROOT, and
        // ContentView keeps every tab's content mounted -- so the commit now
        // depends on TabView choosing to fire onDisappear for a deselected tab.
        // Edit a DNS field, background the app, and the value is already
        // persisted while the reapply that makes it live never fires.
        //
        // Backgrounding is the one moment we are always told about, so commit
        // there too. commitPendingReapply is a no-op when nothing is pending,
        // so the two paths cannot double-blip.
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { settingsVM.commitPendingReapply() }
        }
        // The Custom DNS row's plan flag lives in /api/client-config (owner
        // item 40). A no-op until the privacy disclosure is accepted (item 41);
        // the row reads as available meanwhile.
        .task { await settingsVM.refreshClientConfig() }
    }

    // MARK: - DNS — promoted from VPN Settings

    /// Owner item 40: Custom DNS is on EVERY plan — it was SOVEREIGN-only here.
    /// The server's client-config flag for the plan is still honoured, and
    /// says yes for every plan; no answer reads as available (`CustomDnsGate`).
    private var customDnsAvailable: Bool {
        settingsVM.isCustomDnsAvailable(plan: vpnVM.currentPlan)
    }

    @ViewBuilder
    private var customDnsRow: some View {
        if customDnsAvailable {
            SettingsToggleRow(icon: "server.rack", iconColor: BirdoTheme.accent,
                              title: "Custom DNS Servers",
                              description: "Use your own DNS servers instead of the VPN defaults",
                              isOn: $settingsVM.customDnsEnabled)
        } else {
            SettingsLockedRow(icon: "server.rack",
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
    }

    /// Error only when non-blank AND invalid (blank = "use VPN defaults").
    /// Same predicate the tunnel builder uses, so UI and gate never drift.
    private func dnsError(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, !SettingsViewModel.isValidDnsAddress(trimmed) else { return nil }
        return "Enter a valid IP address"
    }

    // MARK: - Port Forwarding (plan-gated) — promoted from VPN Settings

    /// MR-1517: the plan table (the server's, else SOVEREIGN only); locked
    /// while the plan is not loaded, as before.
    @ViewBuilder
    private var portForwardingRow: some View {
        if settingsVM.planHas(.portForwarding, plan: vpnVM.subscription?.plan) == true {
            NavigationLink {
                PortForwardView()
            } label: {
                SettingsLinkRow(icon: "arrow.left.arrow.right", iconColor: BirdoTheme.blue,
                                title: "Port Forwarding",
                                description: "Expose ports through your VPN tunnel")
            }
            .buttonStyle(PressScaleButtonStyle())
        } else {
            SettingsLockedRow(icon: "arrow.left.arrow.right",
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

    // MARK: - Privacy

    /// App Lock (MR-1094). Turning it ON asks for the device owner first: it
    /// needs a device passcode (AppLockPolicy.canTurnOn), and a successful
    /// prompt both proves the owner can unlock and asks iOS for Face ID
    /// permission at a moment the user expects it. The switch stays off until
    /// then. Turning it off asks nothing: the app is already unlocked.
    private var appLockBinding: Binding<Bool> {
        Binding(
            get: { settingsVM.biometricLockEnabled },
            set: { on in
                if on {
                    Task { await turnOnAppLock() }
                } else {
                    settingsVM.biometricLockEnabled = false
                }
            }
        )
    }

    private func turnOnAppLock() async {
        guard AppLockPolicy.canTurnOn(DeviceOwnerAuth.availability()) else {
            appLockRefusal = "Set a passcode for this device first. App Lock asks for Face ID, Touch ID "
                + "or the passcode, and needs a passcode to fall back on."
            return
        }
        switch await DeviceOwnerAuth.authenticate(reason: "Turn on App Lock") {
        case .success:
            settingsVM.biometricLockEnabled = true
        case .passcodeNotSet:
            appLockRefusal = "Set a passcode for this device first. App Lock asks for Face ID, Touch ID "
                + "or the passcode, and needs a passcode to fall back on."
        case .failed:
            break // cancelled or failed: it stays off
        }
    }

    /// Grouped so the body stays a flat list of sections; a @ViewBuilder
    /// property flattens into the enclosing VStack, so spacing is unchanged.
    @ViewBuilder
    private var privacySection: some View {
        // MR-1094 (owner decision 2026-10-28): App Lock is opt-in, but real
        // when on. Nothing is shown until the owner unlocks with Face ID, Touch
        // ID or the passcode; it re-locks in the background; the VPN keeps
        // running underneath (BiometricGate, AppLockPolicy). It replaces the
        // cover-only "Hide App Contents" under the same stored setting.
        SectionHeader("Privacy")
        SettingsToggleRow(icon: "lock.fill", iconColor: BirdoTheme.white60,
                          title: "App Lock",
                          description: "Asks for Face ID, Touch ID or your passcode before BirdoVPN shows "
                            + "anything, each time you open it or come back to it. Needs a device "
                            + "passcode. The VPN keeps running while the app is locked.",
                          isOn: appLockBinding)
            .alert("App Lock needs a passcode",
                   isPresented: Binding(get: { appLockRefusal != nil },
                                        set: { if !$0 { appLockRefusal = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(appLockRefusal ?? "")
            }
        // The two settings that protect traffic sit directly under App Lock,
        // which guards the app, not the traffic. Kill
        // Switch keeps its T2 binding (`killSwitchToggleBinding`, never
        // `killSwitchEnabled`) and the confirm-before-disable dialog attached
        // to this screen's body.
        // Wording: REMEDIATION-DECISIONS §2 (audit 2026-09-29, D-7 / D-3).
        // The kill switch is armed once the tunnel is up and hands traffic
        // back if the re-dial breaker trips (TunnelCircuitBreaker), so "block
        // all traffic" overstated it, and so does "until it reconnects" on
        // its own: the second sentence says the block is released (second-pass
        // #5). It does NOT say "and tells you": with no on-demand rule armed
        // the extension releases the block by itself, and the explanation
        // (HomeView's breaker banner) appears only when the app next runs;
        // there is no notification. The PQ key is derived between the app
        // and the API and reaches the server over TLS that is not yet
        // post-quantum, so "protects against future quantum computer attacks"
        // overstated that. (The row allows four lines; that caveat lives on
        // birdo.app and in the store text.)
        SettingsToggleRow(icon: "checkmark.shield.fill", iconColor: BirdoTheme.green,
                          title: "Kill Switch",
                          description: "If the tunnel drops unexpectedly, blocks traffic until it reconnects. "
                            + "If reconnecting keeps failing, the app stops blocking.",
                          isOn: settingsVM.killSwitchToggleBinding)
        SettingsToggleRow(icon: "lock.fill", iconColor: BirdoTheme.accent,
                          title: "Quantum Protection",
                          description: "Adds a WireGuard pre-shared key derived with ML-KEM-1024 (BirdoPQ) to each connection.",
                          isOn: $settingsVM.quantumProtectionEnabled)
    }

    // MARK: - VPN

    /// The sub-page link plus the two rows promoted out of it: Custom DNS
    /// Servers (every plan, owner item 40) and Port Forwarding (SOVEREIGN).
    @ViewBuilder
    private var vpnSection: some View {
        SectionHeader("VPN")
        NavigationLink {
            VpnSettingsView()
        } label: {
            SettingsLinkRow(icon: "slider.horizontal.3", iconColor: BirdoTheme.blue,
                            title: "VPN Settings",
                            description: "Local network, port, and MTU")
        }
        .buttonStyle(PressScaleButtonStyle())
        customDnsRow
        // Locked rows render checked = persisted && unlocked (§0.5), so the
        // fields also stay hidden while locked.
        if customDnsAvailable && settingsVM.customDnsEnabled {
            dnsFields
        }
        portForwardingRow
    }

    // MARK: - Top bar (Android BirdoTopBar — no back button on a tab root)

    private var topBar: some View {
        VStack(spacing: 0) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Settings")
                        .font(.system(size: 17, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurface)
                        .accessibilityAddTraits(.isHeader)
                    Text("App preferences & account")
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                }
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

    // MARK: - About (not tappable)

    private var aboutCard: some View {
        BirdoCard(cornerRadius: 16, horizontalPadding: 14, verticalPadding: 14) {
            HStack(spacing: 12) {
                BirdoLogo(.boxed(size: 44, cornerRadius: 12))
                VStack(alignment: .leading, spacing: 2) {
                    Text("BirdoVPN")
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(BirdoTheme.onSurface)
                    Text("Version \(appVersion)")
                        .font(BirdoTheme.Fonts.bodySmall)
                        .foregroundStyle(BirdoTheme.onSurfaceMuted)
                }
                Spacer(minLength: 0)
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 20))
                    .foregroundStyle(BirdoTheme.accent)
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Rows (Android §1: card + 36pt icon chip + title/desc + control)

/// Whole-row toggle target announcing a single switch (Android §0.3) — the
/// inner Toggle is a visual only; the surrounding Button handles the tap.
private struct SettingsToggleRow: View {
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
            BirdoCard(cornerRadius: 16, horizontalPadding: 14, verticalPadding: 14) {
                HStack(spacing: 12) {
                    SettingsIconChip(icon: icon, color: iconColor)
                    SettingsRowText(title: title, description: description)
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

/// Navigation row: icon chip + title/desc + trailing chevron (18pt, faint).
private struct SettingsLinkRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String

    var body: some View {
        BirdoCard(cornerRadius: 16, horizontalPadding: 14, verticalPadding: 14) {
            HStack(spacing: 12) {
                SettingsIconChip(icon: icon, color: iconColor)
                SettingsRowText(title: title, description: description)
                Spacer(minLength: 8)
                Image(systemName: "chevron.right")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(BirdoTheme.onSurfaceFaint)
                    .accessibilityHidden(true)
            }
        }
    }
}

/// Tappable link row: icon chip + title/desc + trailing icon (e.g. an
/// "open in new" glyph for external/system destinations). Whole row is the
/// button target.
private struct SettingsLinkButton: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String
    let trailing: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BirdoCard(cornerRadius: 16, horizontalPadding: 14, verticalPadding: 14) {
                HStack(spacing: 12) {
                    SettingsIconChip(icon: icon, color: iconColor)
                    SettingsRowText(title: title, description: description)
                    Spacer(minLength: 8)
                    Image(systemName: trailing)
                        .font(.system(size: 15, weight: .medium))
                        .foregroundStyle(BirdoTheme.onSurfaceFaint)
                        .accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(PressScaleButtonStyle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
    }
}

/// Plan-locked row (§0.3): control replaced by a lock, leading icon dimmed,
/// whole row routes to the upgrade flow. The Settings-tab counterpart of the
/// VPN Settings screen's locked row, so the two promoted SOVEREIGN rows keep
/// their lock affordance in this screen's card idiom.
private struct SettingsLockedRow: View {
    let icon: String
    let title: String
    let description: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BirdoCard(cornerRadius: 16, horizontalPadding: 14, verticalPadding: 14) {
                HStack(spacing: 12) {
                    SettingsIconChip(icon: icon, color: BirdoTheme.onSurfaceFaint)
                    SettingsRowText(title: title, description: description)
                    Spacer(minLength: 8)
                    Image(systemName: "lock.fill")
                        .font(.system(size: 16))
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

/// 36pt rounded chip (r10, surfaceRaised, hairlineSoft border) with an
/// 18pt icon — the Settings-tab leading treatment.
private struct SettingsIconChip: View {
    let icon: String
    let color: Color

    var body: some View {
        Image(systemName: icon)
            .font(.system(size: 16))
            .foregroundStyle(color)
            .frame(width: 36, height: 36)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(BirdoTheme.surfaceRaised)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .strokeBorder(BirdoTheme.hairlineSoft, lineWidth: 1)
            )
            .accessibilityHidden(true)
    }
}

private struct SettingsRowText: View {
    let title: String
    let description: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(BirdoTheme.Fonts.labelLarge)
                .foregroundStyle(BirdoTheme.onBackground)
            Text(description)
                .font(BirdoTheme.Fonts.bodySmall)
                .foregroundStyle(BirdoTheme.onSurfaceMuted)
                // 4, not 2: every other row's copy is one or two lines, so this
                // is a max that only bites on the rows that need the space
                // (App Lock, Kill Switch).
                .lineLimit(4)
        }
        .multilineTextAlignment(.leading)
    }
}
