import SwiftUI

/// MR-1727: keeps the 24-digit anonymous account number out of the app-switcher
/// snapshot.
///
/// The number is the account's only credential, and iOS photographs the window
/// for the app switcher as the app leaves the foreground. The only cover used
/// to be Hide App Contents (`BiometricGate`), which is opt-in and off by
/// default, so the snapshot showed the number in clear — on the "save your
/// account number" step too, which is exactly where a user leaves the app to
/// paste it into a password manager.
///
/// Applied to every view that renders the number, and independent of Hide App
/// Contents: while the scene is not `.active` (the app switcher opening, a
/// Control Center pull, going to the background) the digits are replaced by
/// the redaction placeholder, so the snapshot holds no digits. They return the
/// moment the scene is active again.
///
/// iOS only: the app-switcher snapshot is an iOS behaviour. On a Mac the
/// number stays as it was.
struct AccountNumberSnapshotCover: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase

    func body(content: Content) -> some View {
        #if os(iOS)
        content.redacted(reason: scenePhase == .active ? [] : .placeholder)
        #else
        content
        #endif
    }
}

extension View {
    /// See `AccountNumberSnapshotCover`.
    func accountNumberSnapshotCover() -> some View {
        modifier(AccountNumberSnapshotCover())
    }
}
