import SwiftUI
import UIKit

/// Settings tab root — Android SettingsScreen parity (spec-secondary-screens
/// §1). iOS-specific notes:
///   - Appearance/Theme: the shipped design system is dark-only (BirdoTheme
///     carries no light palette yet), but the spec'd segmented control is
///     present and its choice is PERSISTED (`app_theme` AppStorage) so the
///     preference survives the eventual light-palette work — until then only
///     "Dark" has a visible effect (owner decision, spec §1.2).
///   - Notifications: iOS has no in-app connection-notification delivery yet,
///     but the spec'd Notifications group is present — the master toggle is
///     persisted (`notifications_enabled` AppStorage) and "Notification
///     Settings" deep-links to the OS notification page for the app. The
///     per-detail Show-IP / Show-Server sub-toggles are deferred with the
///     delivery code (reported as a gap), not faked here.
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

    // REMOVED: the Appearance/theme control and the "Notifications" master toggle.
    // Both were dead. The app is pinned to dark (`BirdoVPNApp.preferredColorScheme(.dark)`)
    // and no light palette exists, so "Light" and "System" silently did nothing;
    // and no notification code ships on iOS, so that switch persisted a value
    // nothing read. They return when the feature behind them does.

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }

    var body: some View {
        VStack(spacing: 0) {
            topBar

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    SectionHeader("Security")
                    SettingsToggleRow(icon: "faceid", iconColor: BirdoTheme.green,
                                      title: "Biometric Lock",
                                      isOn: $settingsVM.biometricLockEnabled)

                    SectionHeader("Connection")
                    SettingsToggleRow(icon: "wifi", iconColor: BirdoTheme.blue,
                                      title: "Auto-Connect",
                                      isOn: $settingsVM.autoConnect)

                    SectionHeader("Notifications")
                    // The in-app "Notifications" master toggle was REMOVED: iOS
                    // ships no notification code, so the switch persisted a value
                    // nothing ever read — a control that silently does nothing is
                    // worse than no control (the same rule SettingsViewModel already
                    // applied when it dropped the dead stealth toggle). The system
                    // link below is the real, working affordance.
                    SettingsLinkButton(icon: "bell.badge", iconColor: BirdoTheme.white60,
                                       title: "Notification Settings",
                                       trailing: "arrow.up.forward.square") {
                        // Deep-link to THIS app's notification page (iOS 16+), which
                        // is what the row claims to do; the generic Settings root is
                        // only the fallback.
                        let target = UIApplication.openNotificationSettingsURLString
                        if let url = URL(string: target), UIApplication.shared.canOpenURL(url) {
                            UIApplication.shared.open(url)
                        } else if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }

                    SectionHeader("VPN")
                    NavigationLink {
                        VpnSettingsView()
                    } label: {
                        SettingsLinkRow(icon: "slider.horizontal.3", iconColor: BirdoTheme.blue,
                                        title: "VPN Settings")
                    }
                    .buttonStyle(PressScaleButtonStyle())

                    SectionHeader("About")
                    aboutCard

                    Spacer().frame(height: 32)
                }
                .padding(.horizontal, BirdoTheme.Spacing.screenH)
                .padding(.vertical, BirdoTheme.Spacing.screenV)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
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
                    Text("Birdo VPN")
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
    var description: String? = nil
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
    var description: String? = nil

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
    var description: String? = nil
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
    var description: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(BirdoTheme.Fonts.labelLarge)
                .foregroundStyle(BirdoTheme.onBackground)
            if let description, !description.isEmpty {
                Text(description)
                    .font(BirdoTheme.Fonts.bodySmall)
                    .foregroundStyle(BirdoTheme.onSurfaceMuted)
                    .lineLimit(2)
            }
        }
        .multilineTextAlignment(.leading)
    }
}
