import SwiftUI

/// Settings screen matching Android — kill switch, auto-connect, notifications, etc.
struct SettingsView: View {
    @EnvironmentObject var settingsVM: SettingsViewModel
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel

    @State private var showDeleteDialog = false
    /// Cleared on both Delete and Cancel so the plaintext never outlives the alert.
    @State private var deletePassword = ""
    @State private var showKillSwitchWarning = false

    var body: some View {
        NavigationView {
            List {
                // Connection
                Section("Connection") {
                    // Enabling is immediate; disabling routes through a
                    // confirmation alert (leave the toggle ON until confirmed).
                    SettingsToggle(icon: "shield.fill", iconColor: BirdoTheme.green,
                                   title: "Kill Switch", description: "Block internet if VPN drops",
                                   isOn: Binding(
                                       get: { settingsVM.killSwitchEnabled },
                                       set: { newValue in
                                           if newValue {
                                               settingsVM.killSwitchEnabled = true
                                           } else {
                                               showKillSwitchWarning = true
                                           }
                                       }
                                   ))

                    SettingsToggle(icon: "wifi", iconColor: BirdoTheme.blue,
                                   title: "Auto-Connect", description: "Connect on app launch",
                                   isOn: $settingsVM.autoConnect)

                    SettingsToggle(icon: "bell.fill", iconColor: BirdoTheme.yellow,
                                   title: "Notifications", description: "Show connection status alerts",
                                   isOn: $settingsVM.notificationsEnabled)
                }
                .listRowBackground(BirdoTheme.surface)

                // VPN Protocol
                Section("VPN Protocol") {
                    NavigationLink(destination: VpnSettingsView()) {
                        SettingsRow(icon: "slider.horizontal.3", iconColor: BirdoTheme.blue,
                                    title: "VPN Settings", description: "DNS, WireGuard port, MTU, stealth")
                    }
                }
                .listRowBackground(BirdoTheme.surface)

                // Security
                Section("Security") {
                    SettingsToggle(icon: "faceid", iconColor: BirdoTheme.green,
                                   title: "Biometric Lock", description: "Require Face ID / Touch ID to open",
                                   isOn: $settingsVM.biometricLockEnabled)
                }
                .listRowBackground(BirdoTheme.surface)

                // Account
                Section("Account") {
                    NavigationLink(destination: SubscriptionView()) {
                        SettingsRow(icon: "creditcard", iconColor: BirdoTheme.white60,
                                    title: "Subscription", description: "View plans & billing")
                    }

                    Link(destination: URL(string: "https://birdo.app/privacy")!) {
                        SettingsRow(icon: "hand.raised.fill", iconColor: BirdoTheme.white60,
                                    title: "Privacy Policy", description: "birdo.app/privacy")
                    }

                    Link(destination: URL(string: "https://birdo.app/terms")!) {
                        SettingsRow(icon: "doc.text", iconColor: BirdoTheme.white60,
                                    title: "Terms of Service", description: "birdo.app/terms")
                    }

                    Button(action: { showDeleteDialog = true }) {
                        SettingsRow(icon: "trash.fill", iconColor: BirdoTheme.red,
                                    title: "Delete Account", description: "Permanently delete your account",
                                    titleColor: BirdoTheme.red)
                    }
                }
                .listRowBackground(BirdoTheme.surface)

                // About
                Section("About") {
                    HStack(spacing: 14) {
                        Image(systemName: "info.circle")
                            .foregroundColor(BirdoTheme.white60)
                            .frame(width: 22)
                        VStack(alignment: .leading) {
                            Text("Birdo VPN")
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(BirdoTheme.white80)
                            Text("Version \(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")")
                                .font(.caption)
                                .foregroundColor(BirdoTheme.white40)
                        }
                    }
                }
                .listRowBackground(BirdoTheme.surface)
            }
            .scrollContentBackground(.hidden)
            .background(BirdoTheme.black)
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .alert("Delete Account", isPresented: $showDeleteDialog) {
                // The password IS sent now. gdpr.controller.ts rejects erasure
                // with 401 when the account has a password hash and the body
                // carries none, so dropping this field entirely made deletion
                // impossible for every email-signup account. It stays OPTIONAL
                // (not a button gate) because SSO and anonymous accounts have no
                // hash and must still be able to delete — the backend skips the
                // check for those and ignores whatever is sent.
                SecureField("Password (if you have one)", text: $deletePassword)
                Button("Delete", role: .destructive) {
                    authVM.deleteAccount(password: deletePassword)
                    deletePassword = ""
                }
                Button("Cancel", role: .cancel) { deletePassword = "" }
            } message: {
                Text("This action is permanent and cannot be undone. All your data will be deleted. If you signed up with an email and password, enter your password to confirm.")
            }
            .alert("Disable Kill Switch?", isPresented: $showKillSwitchWarning) {
                Button("Disable", role: .destructive) {
                    settingsVM.killSwitchEnabled = false
                }
                Button("Keep Enabled", role: .cancel) { }
            } message: {
                Text("With the kill switch off, your internet keeps flowing if the VPN drops, which can briefly expose your real IP address and traffic.")
            }
        }
    }
}

// MARK: - Reusable Components

struct SettingsToggle: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(isOn: $isOn) {
            HStack(spacing: 14) {
                Image(systemName: icon)
                    .foregroundColor(iconColor)
                    .frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(BirdoTheme.white80)
                    Text(description)
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white40)
                        .lineLimit(1)
                }
            }
        }
        .tint(.white)
    }
}

struct SettingsRow: View {
    let icon: String
    let iconColor: Color
    let title: String
    let description: String
    var titleColor: Color = BirdoTheme.white80

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .foregroundColor(iconColor)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(.medium))
                    .foregroundColor(titleColor)
                Text(description)
                    .font(.caption)
                    .foregroundColor(BirdoTheme.white40)
                    .lineLimit(1)
            }
        }
    }
}
