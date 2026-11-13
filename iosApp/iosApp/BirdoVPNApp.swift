import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(AppKit)
import AppKit
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
            // App Lock (MR-1094): a binding, so the gate can switch the
            // setting off itself when the device passcode has been removed.
            BiometricGate(enabled: $settingsVM.biometricLockEnabled) {
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
                    // The minimum below is a FLOOR, not a ceiling -- it cannot
                    // stop a window being too tall, and saying otherwise (as an
                    // earlier draft of this comment did) describes a mechanism
                    // that does not exist. It is here so the window cannot be
                    // dragged small enough to hide its own title bar. 480 wide,
                    // because any narrower and a pushed screen's back button and
                    // the four tabs no longer fit the toolbar: both collapse
                    // into its overflow menu.
                    //
                    // What actually fixes the rejection is clampToVisibleFrame
                    // below. `.defaultSize` alone would not: AppKit restores a
                    // saved frame in preference to it, and the reviewer's Mac
                    // has ALREADY RUN the rejected build, so it has a saved
                    // frame -- the very machine the fix must work on is the one
                    // where the default is ignored.
                    #if os(macOS)
                    .frame(minWidth: 480, minHeight: 560)
                    .onAppear { MacWindow.clampToVisibleFrame() }
                    .onReceive(NotificationCenter.default.publisher(for: NSWindow.didBecomeMainNotification)) { _ in
                        MacWindow.clampToVisibleFrame()
                    }
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
        // One tunnel and one session, so a second window only shows the same
        // state twice: no File > New Window, no Command-N. This stays a
        // WindowGroup rather than a `Window` scene, which iOS does not have and
        // which quits the app when its window closes.
        .commands { CommandGroup(replacing: .newItem) { } }
        #endif
    }

    #if os(macOS)
    /// Pull every window fully inside its own screen's usable area.
    ///
    /// Guideline 4 was "windows laid out and partially hidden under menu bar".
    /// `visibleFrame` already excludes the menu bar and the Dock, so clamping
    /// to it is exactly the property the reviewer checked. The arithmetic is
    /// `WindowFrameClamp.clamped`, which sits outside `#if os(macOS)` because
    /// CI's test run is on the iOS Simulator.
    ///
    /// EVERY window, each against its own screen, because removing New Window
    /// does not make one the only case: a Mac that ran the rejected build may
    /// restore a saved layout of several, possibly across displays, and SwiftUI
    /// opens a new window for a `birdo://` URL that no open scene claims.
    /// Sheets move with their parent and a full-screen window fills its screen
    /// on purpose, so both are left alone.
    ///
    /// Runs on appear because a RESTORED frame is applied after the scene's
    /// defaultSize, and a Mac that ran the rejected build has one saved. Runs
    /// again when a window becomes main because `canBecomeMain` is false until
    /// a window is visible, and SwiftUI may not have put it on screen yet by
    /// the hop below: nothing would qualify on that pass, and the fix would
    /// silently do nothing. The cost is that a window left hanging off an edge
    /// comes back the next time it takes focus.
    enum MacWindow {
        static func clampToVisibleFrame() {
            DispatchQueue.main.async {
                for window in NSApplication.shared.windows
                where window.isVisible && window.canBecomeMain && !window.isSheet
                    && !window.styleMask.contains(.fullScreen) {
                    guard let visible = (window.screen ?? NSScreen.main)?.visibleFrame
                    else { continue }
                    let frame = WindowFrameClamp.clamped(window.frame, into: visible)
                    guard frame != window.frame else { continue }
                    window.setFrame(frame, display: true)
                }
            }
        }
    }
    #endif

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
                .font: UIFontMetrics(forTextStyle: .caption2)
                    .scaledFont(for: UIFont.systemFont(ofSize: 11, weight: .semibold)),
            ]
            item.normal.iconColor = muted
            item.normal.titleTextAttributes = [
                .foregroundColor: muted,
                .font: UIFontMetrics(forTextStyle: .caption2)
                    .scaledFont(for: UIFont.systemFont(ofSize: 11, weight: .medium)),
            ]
        }

        UITabBar.appearance().standardAppearance = appearance
        UITabBar.appearance().scrollEdgeAppearance = appearance
        #endif
    }
}
