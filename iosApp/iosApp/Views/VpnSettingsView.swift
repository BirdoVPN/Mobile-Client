import SwiftUI

/// VPN protocol settings — stealth mode, quantum protection, DNS, WireGuard port/MTU.
struct VpnSettingsView: View {
    @EnvironmentObject var settingsVM: SettingsViewModel

    @State private var customPortText = ""
    @State private var mtuText = ""

    var body: some View {
        List {
            // Security
            Section("SECURITY") {
                // iOS has no Xray engine yet — the protocol layer always sends
                // stealthMode: false. Shown disabled rather than hidden so the
                // feature gap is honest.
                VStack(alignment: .leading, spacing: 4) {
                    SettingsToggle(icon: "eye.slash.fill", iconColor: BirdoTheme.white40,
                                   title: "Stealth Mode",
                                   description: "Route through Xray Reality to bypass DPI",
                                   isOn: .constant(false))
                        .disabled(true)

                    Text("Not available on iOS yet")
                        .font(.caption2)
                        .foregroundColor(BirdoTheme.white40)
                        .padding(.leading, 36)
                }

                SettingsToggle(icon: "lock.fill", iconColor: BirdoTheme.accent,
                               title: "Quantum Protection",
                               description: "Post-quantum pre-shared key via Rosenpass",
                               isOn: $settingsVM.quantumProtectionEnabled)
            }
            .listRowBackground(BirdoTheme.surface)

            // Network
            Section("NETWORK") {
                SettingsToggle(icon: "network", iconColor: BirdoTheme.blue,
                               title: "Local Network Sharing",
                               description: "Allow access to LAN devices while connected",
                               isOn: $settingsVM.localNetworkSharing)
            }
            .listRowBackground(BirdoTheme.surface)

            // DNS
            Section("DNS") {
                SettingsToggle(icon: "server.rack", iconColor: BirdoTheme.accent,
                               title: "Custom DNS",
                               description: "Use your own DNS servers",
                               isOn: $settingsVM.customDnsEnabled)

                if settingsVM.customDnsEnabled {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Primary DNS")
                            .font(.caption)
                            .foregroundColor(BirdoTheme.white40)
                        TextField("1.1.1.1", text: $settingsVM.customDnsPrimary)
                            .keyboardType(.decimalPad)
                            .textFieldStyle(BirdoTextFieldStyle())
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Secondary DNS")
                            .font(.caption)
                            .foregroundColor(BirdoTheme.white40)
                        TextField("1.0.0.1", text: $settingsVM.customDnsSecondary)
                            .keyboardType(.decimalPad)
                            .textFieldStyle(BirdoTextFieldStyle())
                    }
                }
            }
            .listRowBackground(BirdoTheme.surface)

            // WireGuard
            Section("WIREGUARD") {
                // Port selection
                VStack(alignment: .leading, spacing: 8) {
                    Label("Port", systemImage: "antenna.radiowaves.left.and.right")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(BirdoTheme.white80)

                    Picker("Port", selection: $settingsVM.wireGuardPort) {
                        Text("Auto").tag("auto")
                        Text("51820").tag("51820")
                        Text("53").tag("53")
                        Text("Custom").tag("custom")
                    }
                    .pickerStyle(.segmented)

                    if settingsVM.wireGuardPort == "custom" {
                        TextField("Port number (1-65535)", text: $customPortText)
                            .keyboardType(.numberPad)
                            .textFieldStyle(BirdoTextFieldStyle())
                            .onChange(of: customPortText) { newValue in
                                let filtered = String(newValue.filter(\.isNumber).prefix(5))
                                customPortText = filtered
                            }
                    }
                }

                // MTU
                VStack(alignment: .leading, spacing: 8) {
                    Label("MTU", systemImage: "slider.horizontal.3")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(BirdoTheme.white80)

                    Toggle("Auto MTU", isOn: Binding(
                        get: { settingsVM.wireGuardMtu == 0 },
                        set: { settingsVM.wireGuardMtu = $0 ? 0 : 1420 }
                    ))
                    .tint(.white)

                    if settingsVM.wireGuardMtu != 0 {
                        TextField("MTU (1280-1500)", text: $mtuText)
                            .keyboardType(.numberPad)
                            .textFieldStyle(BirdoTextFieldStyle())
                            .onChange(of: mtuText) { newValue in
                                mtuText = String(newValue.filter(\.isNumber).prefix(4))
                                if let val = Int(mtuText) {
                                    settingsVM.wireGuardMtu = Int32(min(max(val, 1280), 1500))
                                }
                            }

                        Text("Range: 1280 - 1500")
                            .font(.caption2)
                            .foregroundColor(BirdoTheme.white40)
                    }
                }
            }
            .listRowBackground(BirdoTheme.surface)

            // Info note
            Section {
                HStack(spacing: 10) {
                    Image(systemName: "info.circle")
                        .foregroundColor(BirdoTheme.white40)
                        .font(.caption)
                    Text("Changes take effect on next connection.")
                        .font(.caption)
                        .foregroundColor(BirdoTheme.white60)
                }
            }
            .listRowBackground(BirdoTheme.white10)
        }
        .scrollContentBackground(.hidden)
        .background(BirdoTheme.black)
        .navigationTitle("VPN Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // Reflect the persisted MTU in the text field so a previously
            // saved value is visible (and editable) instead of showing blank.
            if settingsVM.wireGuardMtu != 0 {
                mtuText = String(settingsVM.wireGuardMtu)
            }
        }
    }
}
