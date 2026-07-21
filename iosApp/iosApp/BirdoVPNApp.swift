import SwiftUI
import UIKit

@main
struct BirdoVPNApp: App {
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var vpnVM = VpnViewModel()
    @StateObject private var settingsVM = SettingsViewModel()

    init() {
        Self.styleTabBar()
    }

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

    /// Android bottom-bar chrome (spec-home-servers-consent §2): surface fill,
    /// hairlineSoft top divider, selected = accent, unselected =
    /// onSurfaceMuted, 11pt labels (SemiBold selected / Medium unselected).
    /// SwiftUI's TabView takes its chrome from UITabBarAppearance, so this is
    /// configured once at launch.
    @MainActor
    private static func styleTabBar() {
        let appearance = UITabBarAppearance()
        appearance.configureWithOpaqueBackground()
        // BirdoTheme.surface #0B0B10
        appearance.backgroundColor = UIColor(red: 0x0B / 255.0, green: 0x0B / 255.0, blue: 0x10 / 255.0, alpha: 1)
        // BirdoTheme.hairlineSoft (white @ 8%) as the top divider
        appearance.shadowColor = UIColor.white.withAlphaComponent(0.08)

        // BirdoTheme.accent #10B981
        let accent = UIColor(red: 0x10 / 255.0, green: 0xB9 / 255.0, blue: 0x81 / 255.0, alpha: 1)
        let muted = UIColor.white.withAlphaComponent(0.6)

        for item in [appearance.stackedLayoutAppearance,
                     appearance.inlineLayoutAppearance,
                     appearance.compactInlineLayoutAppearance] {
            item.selected.iconColor = accent
            item.selected.titleTextAttributes = [
                .foregroundColor: accent,
                .font: UIFont.systemFont(ofSize: 11, weight: .semibold),
            ]
            item.normal.iconColor = muted
            item.normal.titleTextAttributes = [
                .foregroundColor: muted,
                .font: UIFont.systemFont(ofSize: 11, weight: .medium),
            ]
        }

        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
    }
}
