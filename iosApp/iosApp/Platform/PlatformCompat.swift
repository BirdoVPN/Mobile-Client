//
//  Cross-platform shims for the handful of UIKit calls the app makes.
//
//  The macOS build shares essentially all of this app's source with iOS — the
//  SwiftUI views, view models, API client and the WireGuard packet-tunnel
//  provider are identical. Only about a dozen call sites touch UIKit, and they
//  are all trivially expressible on AppKit.
//
//  Those call sites go through this file rather than each growing its own
//  `#if os(iOS)`. Scattering conditionals through view bodies makes them
//  unreadable and, more practically, means every future edit has to remember
//  both platforms. Here the platform split is stated once per concept.
//
//  NOTE ON CATALYST: this is a NATIVE macOS target, not Mac Catalyst. Go has no
//  `ios-macabi` target, so libwg-go.a — which the tunnel is built on — cannot
//  be produced for Catalyst at all. A native macOS build uses GOOS=darwin,
//  which WireGuardKitGo's Makefile already supports.
//

import Foundation

#if os(iOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

// MARK: - Navigation chrome

import SwiftUI

/// Hides the system navigation bar where the platform has one.
///
/// Every tab root draws its own glass top bar and hides the system one with
/// `.toolbar(.hidden, for: .navigationBar)`. The `.navigationBar` placement is
/// unavailable on macOS — a Mac window has a title bar, not a navigation bar —
/// so applying it there is a compile error, not a no-op.
///
/// Wrapping it in a modifier keeps five call sites identical across platforms
/// instead of each carrying its own `#if`, and means the macOS behaviour is
/// decided in exactly one place if it ever needs to do something real (hiding
/// the window title, say).
struct HideNavigationBar: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.toolbar(.hidden, for: .navigationBar)
        #else
        content
        #endif
    }
}

/// As `HideNavigationBar`, but for pushed detail screens that also hide the tab
/// bar. `.tabBar` placement is iOS-only for the same reason as `.navigationBar`.
struct HideNavigationAndTabBar: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.toolbar(.hidden, for: .navigationBar, .tabBar)
        #else
        content
        #endif
    }
}

/// Hides the tab bar only (pushed sub-screens that keep the system title bar).
struct HideTabBar: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.toolbar(.hidden, for: .tabBar)
        #else
        content
        #endif
    }
}

/// Inline navigation title, where the platform has the concept.
///
/// `navigationBarTitleDisplayMode` is iOS-only — macOS has no large/inline
/// title distinction — so applying it there is a compile error rather than a
/// no-op.
struct InlineNavigationTitle: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content.navigationBarTitleDisplayMode(.inline)
        #else
        content
        #endif
    }
}

/// Search-field text input configuration, applied only where it exists.
///
/// ServerListView uses a bare SwiftUI TextField rather than BirdoTextField, so
/// it carries these modifiers directly instead of inheriting the component's
/// handling.
struct SearchFieldInput: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content
            .textInputAutocapitalization(.never)
            .submitLabel(.search)
        #else
        content
        #endif
    }
}

/// One-time-code field input configuration (the 2FA step).
///
/// Another bare SwiftUI TextField rather than BirdoTextField. `.oneTimeCode`
/// content type matters on iOS — it is what lets the keyboard offer an SMS
/// code — and has no meaning on a Mac.
struct OneTimeCodeInput: ViewModifier {
    func body(content: Content) -> some View {
        #if os(iOS)
        content
            .keyboardType(.asciiCapable)
            .textContentType(.oneTimeCode)
            .textInputAutocapitalization(.never)
            .submitLabel(.done)
        #else
        content
        #endif
    }
}

// MARK: - Accessibility

enum Announce {
    /// Speak a message to assistive technology.
    ///
    /// VoiceOver exists on both platforms but the posting APIs differ:
    /// UIAccessibility.post on iOS, NSAccessibility.post (which needs an
    /// element to originate from) on macOS. The app window is the correct
    /// origin for an app-level announcement like "copied".
    static func message(_ text: String) {
        #if os(iOS)
        UIAccessibility.post(notification: .announcement, argument: text)
        #elseif os(macOS)
        guard let window = NSApplication.shared.keyWindow ?? NSApplication.shared.windows.first else { return }
        NSAccessibility.post(element: window,
                             notification: .announcementRequested,
                             userInfo: [.announcement: text,
                                        .priority: NSAccessibilityPriorityLevel.high.rawValue])
        #endif
    }
}

// MARK: - Text-input types

#if os(macOS)
/// Stand-ins for the iOS-only text-input types.
///
/// `BirdoTextField` stores a keyboard type, content type and capitalisation
/// mode in its signature. None of those exist on macOS — a Mac has a hardware
/// keyboard, so there is nothing to configure — but redefining the view per
/// platform would fork a component every screen depends on.
///
/// Declaring the types here instead keeps ONE `BirdoTextField` whose callers
/// read identically on both platforms; the modifiers that consume them are
/// simply not applied on macOS (see the view). The cases mirror the UIKit
/// spellings actually used in this app, so call sites need no edits.
enum UIKeyboardType {
    case `default`, numberPad, decimalPad, emailAddress, asciiCapable, numbersAndPunctuation
}

enum UITextContentType {
    case username, password, newPassword, emailAddress, oneTimeCode
}

enum TextInputAutocapitalization {
    case never, words, sentences, characters
}
#endif

// MARK: - Haptics

/// Tactile feedback, where the platform has any.
///
/// macOS has no equivalent for the notification haptics used on iOS (the
/// trackpad haptic API is for scrubbing, not success/failure notices), so these
/// are deliberately no-ops there rather than approximated with a sound or an
/// animation the designer never asked for.
enum Haptics {
    enum Notice { case success, warning, error }

    /// Light tick when a selection changes (segmented controls, filter chips).
    /// No macOS equivalent, so a no-op there — see the type doc.
    static func selectionChanged() {
        #if os(iOS)
        UISelectionFeedbackGenerator().selectionChanged()
        #endif
    }

    static func notify(_ notice: Notice) {
        #if os(iOS)
        let generator = UINotificationFeedbackGenerator()
        switch notice {
        case .success: generator.notificationOccurred(.success)
        case .warning: generator.notificationOccurred(.warning)
        case .error:   generator.notificationOccurred(.error)
        }
        #endif
    }
}

// MARK: - Clipboard

enum Clipboard {
    static func copy(_ string: String) {
        #if os(iOS)
        UIPasteboard.general.string = string
        #elseif os(macOS)
        // AppKit requires an explicit clear: the pasteboard is append-style and
        // writing without declaring types first is silently dropped.
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(string, forType: .string)
        #endif
    }

    /// Copy a CREDENTIAL (the anonymous account number is the account's sole
    /// credential). Unlike `copy`, this never syncs off-device via Universal
    /// Clipboard / iCloud and self-clears after [ttl] seconds, so the secret
    /// does not sit on the shared pasteboard indefinitely for other apps to
    /// read.
    static func copySensitive(_ string: String, ttl: TimeInterval = 60) {
        #if os(iOS)
        UIPasteboard.general.setItems(
            [["public.utf8-plain-text": string]],
            options: [
                .localOnly: true,
                .expirationDate: Date().addingTimeInterval(ttl),
            ])
        #elseif os(macOS)
        let pb = NSPasteboard.general
        pb.clearContents()
        // org.nspasteboard.ConcealedType: the de-facto marker password managers
        // set so clipboard tools skip/expire the entry; macOS has no localOnly.
        pb.setString(string, forType: NSPasteboard.PasteboardType("org.nspasteboard.ConcealedType"))
        pb.setString(string, forType: .string)
        #endif
    }
}

// MARK: - Device identity

/// Identity reported to the backend on every request.
///
/// `identifierForVendor` is iOS-only. On macOS the closest stable equivalent
/// under the App Sandbox is the hardware UUID, which is not readable without
/// entitlements the Mac App Store will not grant — so a random identifier is
/// generated once and persisted instead. It is per-install rather than
/// per-device, which is the correct privacy posture for a VPN anyway: it must
/// not be a stable hardware fingerprint.
enum PlatformDevice {
    private static let installIdKey = "birdo_install_id"

    static var vendorId: String? {
        #if os(iOS)
        return UIDevice.current.identifierForVendor?.uuidString
        #elseif os(macOS)
        let defaults = UserDefaults.standard
        if let existing = defaults.string(forKey: installIdKey) { return existing }
        let fresh = UUID().uuidString
        defaults.set(fresh, forKey: installIdKey)
        return fresh
        #endif
    }

    static var systemVersion: String {
        #if os(iOS)
        return UIDevice.current.systemVersion
        #elseif os(macOS)
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
        #endif
    }

    static var deviceName: String {
        #if os(iOS)
        return UIDevice.current.name
        #elseif os(macOS)
        return Host.current().localizedName ?? "Mac"
        #endif
    }
}

// MARK: - Opening URLs and system settings

enum SystemOpen {
    static func url(_ url: URL) {
        #if os(iOS)
        UIApplication.shared.open(url)
        #elseif os(macOS)
        NSWorkspace.shared.open(url)
        #endif
    }

    /// The Apple Account subscription manager — where an auto-renewable
    /// subscription is actually cancelled or changed.
    ///
    /// Deliberately a URL rather than `AppStore.showManageSubscriptions(in:)`:
    /// that API needs a `UIWindowScene` and does not exist on macOS at all, and
    /// this one target ships on both. The App Store apps register these
    /// schemes, so the link opens the subscriptions pane directly rather than
    /// a web page.
    static func manageSubscriptions() {
        #if os(iOS)
        let candidates = ["itms-apps://apps.apple.com/account/subscriptions",
                          "https://apps.apple.com/account/subscriptions"]
        #elseif os(macOS)
        let candidates = ["macappstore://apps.apple.com/account/subscriptions",
                          "https://apps.apple.com/account/subscriptions"]
        #endif
        // Fall through to the https form if the app-scheme URL is unusable —
        // never leave the button doing nothing.
        for candidate in candidates {
            if let url = URL(string: candidate) {
                Self.url(url)
                return
            }
        }
    }

    /// The app's own settings pane.
    ///
    /// iOS deep-links into Settings.app. macOS has no per-app settings pane at
    /// all — an app's preferences live inside the app — so this opens the
    /// system Network settings, which is where a user would go to inspect or
    /// remove the VPN configuration.
    static func appSettings() {
        #if os(iOS)
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
        #elseif os(macOS)
        if let url = URL(string: "x-apple.systempreferences:com.apple.Network-Settings.extension") {
            NSWorkspace.shared.open(url)
        }
        #endif
    }
}
