import Foundation

/// MR-1094 (owner decision 2026-10-28): the iOS App Lock is opt-in, but real
/// when it is on.
///
/// - Nothing is shown until the device owner unlocks: Face ID or Touch ID,
///   with the device passcode as the fallback (`.deviceOwnerAuthentication`).
/// - There is no bypass. Only a missing device passcode turns the lock off,
///   and it says so; nothing lets the user in without asking.
/// - It re-locks when the app goes to the background. The VPN tunnel (the
///   packet tunnel extension, on-demand, Auto-Connect) keeps running
///   underneath. The tokens are not tied to Face ID: background reconnects
///   need them while the app is locked.
/// - It can be turned on only with a device passcode. If the passcode is
///   removed later, the lock switches off with a notice.
/// - Revealing or copying the 24-digit account number in Profile always asks
///   for the device owner when a passcode exists, lock on or off. The one-time
///   "save your number" screen after account creation does not.
///
/// Foundation-only by design so AppLockPolicyTests can pin every decision; the
/// LocalAuthentication calls are in DeviceOwnerAuth.swift and the view in
/// BiometricGate.swift.
enum AppLockPolicy {

    /// What the device can do for `.deviceOwnerAuthentication`.
    enum Availability: Equatable, Sendable {
        /// Face ID / Touch ID, or the passcode, can be asked for.
        case available
        /// No device passcode is set, so there is nothing to ask for.
        case passcodeNotSet
        /// LocalAuthentication refused for another reason (`reason` says
        /// which). A passcode may exist, so nothing is let through.
        case unavailable(reason: String)
    }

    /// The scene phases the lock cares about.
    enum Phase: Sendable {
        case active, inactive, background
    }

    /// Turning the lock on needs a device passcode to fall back on.
    static func canTurnOn(_ availability: Availability) -> Bool {
        availability == .available
    }

    /// Run at launch and every time the app comes to the foreground. A passcode
    /// removed while the lock was on switches it off, with a notice; any other
    /// refusal keeps it on, and the app stays covered until it can ask again.
    static func reconcile(lockOn: Bool, _ availability: Availability) -> (lockOn: Bool, notify: Bool) {
        if lockOn && availability == .passcodeNotSet { return (false, true) }
        return (lockOn, false)
    }

    /// Whether the owner must unlock again after the scene reaches `phase`.
    /// Only the background re-locks. `.inactive` (Control Center, the app
    /// switcher, a StoreKit sheet, the Face ID prompt itself) covers the screen
    /// without asking again, so a prompt for the account number does not
    /// re-lock the app behind it.
    static func locked(after phase: Phase, wasLocked: Bool, lockOn: Bool) -> Bool {
        guard lockOn else { return false }
        return phase == .background ? true : wasLocked
    }

    /// Whether the app's content is hidden behind the lock screen: while it is
    /// locked, and while it is out of the foreground so the app-switcher
    /// snapshot shows nothing.
    static func covered(lockOn: Bool, locked: Bool, phase: Phase) -> Bool {
        lockOn && (locked || phase != .active)
    }

    /// Revealing or copying the account number asks for the owner whenever a
    /// passcode exists, with the lock on or off.
    static func revealNeedsOwner(_ availability: Availability) -> Bool {
        availability != .passcodeNotSet
    }
}
