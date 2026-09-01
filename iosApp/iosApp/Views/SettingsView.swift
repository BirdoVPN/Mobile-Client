import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Settings tab root — Android SettingsScreen parity (spec-secondary-screens
/// §1). iOS-specific notes:
///   - Appearance/Theme: the shipped design system is dark-only (BirdoTheme
///     carries no light palette yet), but the spec'd segmented control is
///     present and its choice is PERSISTED (`app_theme` AppStorage) so the
///     preference survives the eventual light-palette work — until then only
///     "Dark" has a visible effect (owner decision, spec §1.2).
///   - Display: the master connection-notifications toggle lives here and is
///     persisted (`notifications_enabled` AppStorage). The section's other two
///     spec'd members — Show IP Address and Show Server Location — do NOT
///     exist on iOS: they are per-notification detail switches and there is no
///     in-app notification delivery code to detail. They stay deferred with
///     that code (reported as a gap) rather than shipped as dead switches.
///   - "Notification Settings" keeps its own group: it deep-links to the OS
///     notification page for the app, which is a destination, not a display
///     preference.
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
    /// Settings sub-page: both are SOVEREIGN-gated and route to the upgrade
    /// flow when locked.
    @EnvironmentObject var vpnVM: VpnViewModel
    @Environment(\.openURL) private var openURL
    /// Backstop for the reapply commit — see the .onChange below.
    @Environment(\.scenePhase) private var scenePhase

    /// Theme preference (spec §1.2). "dark" | "light" | "system", default
    /// "system". Persisted view-local so it survives without ViewModel churn;
    /// re-theming the running UI is deferred until the light palette ships.
    @AppStorage("app_theme") private var appTheme = "system"

    /// Master connection-notifications preference (spec §1.7), default ON.
    @AppStorage("notifications_enabled") private var notificationsEnabled = true

    /// Upgrade destination for the two locked VPN rows (§0.5).
    @State private var showSubscription = false

    private let themeOptions = ["dark", "light", "system"]

    private var themeIndexBinding: Binding<Int> {
        Binding(
            get: { themeOptions.firstIndex(of: appTheme) ?? 2 },
            set: { newIndex in
                let value = themeOptions.indices.contains(newIndex) ? themeOptions[newIndex] : "system"
                if value != appTheme {
                    Haptics.selectionChanged()
                    appTheme = value
                }
            }
        )
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader("Appearance")
                    themeCard

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
                    SettingsToggleRow(icon: "bell", iconColor: BirdoTheme.yellow,
                                      title: "Notifications",
                                      description: "Show connection notifications",
                                      isOn: $notificationsEnabled)
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
            message: "If the VPN drops while the kill switch is off, your apps can fall back to the normal, unencrypted connection and briefly leak your real IP address and DNS queries. For the strongest protection keep it on.",
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
    }

    // MARK: - DNS (SOVEREIGN-gated, §0.5) — promoted from VPN Settings

    @ViewBuilder
    private var customDnsRow: some View {
        if vpnVM.isSovereign {
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

    // MARK: - Port Forwarding (SOVEREIGN-gated) — promoted from VPN Settings

    @ViewBuilder
    private var portForwardingRow: some View {
        if vpnVM.isSovereign {
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

    /// Grouped so the body stays a flat list of sections; a @ViewBuilder
    /// property flattens into the enclosing VStack, so spacing is unchanged.
    @ViewBuilder
    private var privacySection: some View {
        // P1-ios-biometric-gate-is-cosmetic: the BEHAVIOUR is accepted as-is
        // (owner decision) — this gate covers the UI and nothing else. It
        // unlocks no keychain item, decrypts nothing, and the app keeps running
        // behind the cover, so Auto-Connect still brings the tunnel up while
        // the screen is "locked". What changed is that it no longer *reads* as
        // a security guarantee: it is out of the Security section, off the
        // green (= protected) icon colour, and the copy says what it actually
        // does. Do not restore the old wording without also making the gate
        // gate something.
        SectionHeader("Privacy")
        SettingsToggleRow(icon: "eye.slash", iconColor: BirdoTheme.white60,
                          title: "Hide App Contents",
                          description: "Covers the screen with a Face ID prompt when you "
                            + "open the app. Hides what is on screen only — it protects "
                            + "no data, and the VPN keeps running behind it, including "
                            + "Auto-Connect.",
                          isOn: $settingsVM.biometricLockEnabled)
        // The two settings that actually protect traffic sit directly under
        // the gate that protects none of it — the ordering is the point. Kill
        // Switch keeps its T2 binding (`killSwitchToggleBinding`, never
        // `killSwitchEnabled`) and the confirm-before-disable dialog attached
        // to this screen's body.
        SettingsToggleRow(icon: "checkmark.shield.fill", iconColor: BirdoTheme.green,
                          title: "Kill Switch",
                          description: "Block all traffic if VPN disconnects",
                          isOn: settingsVM.killSwitchToggleBinding)
        SettingsToggleRow(icon: "lock.fill", iconColor: BirdoTheme.accent,
                          title: "Quantum Protection",
                          description: "Add post-quantum pre-shared key exchange via BirdoPQ v1 (ML-KEM-1024, NIST FIPS 203). Protects against future quantum computer attacks.",
                          isOn: $settingsVM.quantumProtectionEnabled)
    }

    // MARK: - VPN

    /// The sub-page link plus the two rows promoted out of it (Custom DNS
    /// Servers, Port Forwarding) — both SOVEREIGN-gated exactly as before.
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
        if vpnVM.isSovereign && settingsVM.customDnsEnabled {
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

    // MARK: - Theme (spec §1.2)

    /// Theme row + segmented control. The segmented choice is persisted; only
    /// "Dark" currently re-themes (light palette deferred, owner decision).
    private var themeCard: some View {
        BirdoCard(cornerRadius: 16, horizontalPadding: 14, verticalPadding: 14) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 12) {
                    SettingsIconChip(icon: "paintpalette", color: BirdoTheme.accent)
                    SettingsRowText(title: "Theme",
                                    description: "Dark, light, or follow system")
                    Spacer(minLength: 8)
                }
                SegmentedTabs(
                    items: ["Dark", "Light", "System"],
                    selection: themeIndexBinding,
                    style: .accent
                )
            }
        }
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
                // is a max that only bites on the one row that needs the space
                // — "Hide App Contents", whose whole point is saying plainly
                // what it does NOT protect. A truncated honesty disclaimer is
                // worse than none.
                .lineLimit(4)
        }
        .multilineTextAlignment(.leading)
    }
}
