import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

@main
struct BirdoVPNApp: App {
    @StateObject private var authVM = AuthViewModel()
    @StateObject private var vpnVM = VpnViewModel()
    @StateObject private var settingsVM = SettingsViewModel()
    /// The App Store purchase rail. Owned by the App (not by SubscriptionView)
    /// because its `Transaction.updates` listener has to outlive every screen:
    /// an Ask-to-Buy approval, a renewal or a purchase made on another device
    /// can arrive at any moment, and a listener that only exists while the
    /// subscription screen is on-screen would miss all three.
    @StateObject private var storeVM = StoreKitService.shared

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
                    .environmentObject(storeVM)
                    .preferredColorScheme(.dark)
                    // macOS 1.4.23 was rejected under guideline 4 for "windows
                    // laid out and partially hidden under menu bar".
                    //
                    // The scene declared no size, no minimum and no
                    // resizability, so AppKit derived the window from the
                    // content -- and the content is a phone-shaped column
                    // (`.frame(maxWidth: 480)` inside a TabView) with no lower
                    // bound. A window whose height is driven by intrinsic
                    // content can exceed the usable screen, and once it does,
                    // its top is placed above the menu bar rather than below
                    // it. Reviewed on a 15-inch MacBook Air, where the visible
                    // height is ~900pt.
                    //
                    // A floor on the CONTENT is what stops that: it makes the
                    // window's minimum a number we chose rather than whatever
                    // the tallest screen's layout happens to produce.
                    #if os(macOS)
                    .frame(minWidth: 420, minHeight: 560)
                    #endif
            }
        }
        // 760pt fits inside the 15-inch Air's usable height with room for the
        // menu bar; 480 matches the content column so nothing is letterboxed on
        // first launch. `.contentMinSize` ties the smallest window to the frame
        // floor above, so the user cannot drag it to a size where the title bar
        // is unreachable.
        #if os(macOS)
        .defaultSize(width: 480, height: 760)
        .windowResizability(.contentMinSize)
        #endif
    }

    /// Android bottom-bar chrome (spec-home-servers-consent §2): surface fill,
    /// hairlineSoft top divider, selected = accent, unselected =
    /// onSurfaceMuted, 11pt labels (SemiBold selected / Medium unselected).
    /// SwiftUI's TabView takes its chrome from UITabBarAppearance, so this is
    /// configured once at launch.
    @MainActor
    private static func styleTabBar() {
        // iOS only: UITabBarAppearance does not exist on macOS, where
        // SwiftUI renders TabView as a native segmented/sidebar control
        // that takes its chrome from the system rather than from an
        // appearance proxy. There is nothing to configure, so this is a
        // no-op there rather than an approximation.
        #if os(iOS)
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
        #endif
    }
}
