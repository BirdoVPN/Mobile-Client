import SwiftUI
import BirdoShared

@main
struct BirdoVPNApp: App {
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var vpnVM = VpnViewModel()
    @StateObject private var settingsVM = SettingsViewModel()

    var body: some Scene {
        WindowGroup {
            BiometricGate(enabled: settingsVM.biometricLockEnabled) {
                ContentView()
                    .environmentObject(authVM)
                    .environmentObject(vpnVM)
                    .environmentObject(settingsVM)
                    .preferredColorScheme(.dark)
            }
        }
    }
}
