import SwiftUI

/// Settings screen matching Android — kill switch, auto-connect, notifications, etc.
struct SettingsView: View {
    @EnvironmentObject var settingsVM: SettingsViewModel
    @EnvironmentObject var authVM: AuthViewModel
    @EnvironmentObject var vpnVM: VpnViewModel

    @State private var showDeleteDialog = false
    @State private var deletePassword = ""

    var body: some View {
        NavigationView {
            List {
                // Connection
                Section("Connection") {
                    SettingsToggle(icon: "shield.fill", iconColor: BirdoTheme.green,
                                   title: "Kill Switch", description: "Block internet if VPN drops",
                                   isOn: $settingsVM.killSwitchEnabled)

                    SettingsToggle(icon: "wifi", iconColor: BirdoTheme.blue,
                                   title: "Auto-Connect", description: "Connect on app launch",
                                   isOn: $settingsVM.autoConnect)
                }
                .listRowBackground(BirdoTheme.surface)

                // VPN Protocol
                Section("VPN Protocol") {
                    NavigationLink(destination: VpnSettingsView()) {
                        SettingsRow(icon: "slider.horizontal.3", iconColor: BirdoTheme.blue,
                                    title: "VPN Settings", description: "DNS, WireGuard port, MTU, stealth")
                    }

                    NavigationLink(destination: PortForwardView()) {
                        SettingsRow(icon: "network", iconColor: BirdoTheme.accent,
                                    title: "Port Forwarding", description: "Allow inbound connections through the VPN")
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
                SecureField("Enter password", text: $deletePassword)
                Button("Delete", role: .destructive) {
                    // The backend requires the password in the DELETE body
                    // (v1/gdpr/delete). Wipe the entered value immediately
                    // after the call so it doesn't linger in SwiftUI state.
                    authVM.deleteAccount(password: deletePassword)
                    deletePassword = ""
                }
                .disabled(deletePassword.isEmpty)
                Button("Cancel", role: .cancel) {
                    deletePassword = ""
                }
            } message: {
                Text("This action is permanent and cannot be undone. All your data will be deleted.")
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
