import Foundation
import LocalAuthentication

/// MR-1094: the LocalAuthentication half of the App Lock and of revealing the
/// account number. The decisions are `AppLockPolicy`'s.
///
/// Always `.deviceOwnerAuthentication`: Face ID or Touch ID, with the device
/// passcode as the fallback, as Apple's LocalAuthentication guidance
/// describes. Never `.deviceOwnerAuthenticationWithBiometrics` alone, which
/// would lock out a user whose Face ID fails or is not enrolled.
@MainActor
enum DeviceOwnerAuth {

    enum Outcome: Equatable, Sendable {
        case success
        /// No device passcode is set: nothing could be asked.
        case passcodeNotSet
        /// Cancelled, failed, or refused; the message says why.
        case failed(String)
    }

    /// What the device can do right now. Asks nothing of the user.
    static func availability() -> AppLockPolicy.Availability {
        let context = LAContext()
        var error: NSError?
        if context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) {
            return .available
        }
        if let error, error.domain == LAError.errorDomain, error.code == LAError.Code.passcodeNotSet.rawValue {
            return .passcodeNotSet
        }
        return .unavailable(reason: error?.localizedDescription
                            ?? "Face ID, Touch ID and the passcode are not available.")
    }

    /// Asks for the device owner, showing `reason` in the system prompt.
    static func authenticate(reason: String) async -> Outcome {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            if let error, error.domain == LAError.errorDomain, error.code == LAError.Code.passcodeNotSet.rawValue {
                return .passcodeNotSet
            }
            return .failed(error?.localizedDescription ?? "Authentication is not available.")
        }
        let outcome: Outcome = await withCheckedContinuation { continuation in
            // @Sendable: LocalAuthentication replies on a private queue, so the
            // reply must not be inferred main-actor isolated.
            context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) { @Sendable success, evalError in
                continuation.resume(returning: success
                    ? .success
                    : .failed((evalError as NSError?)?.localizedDescription ?? "Authentication failed."))
            }
        }
        // The context must outlive its evaluation.
        withExtendedLifetime(context) {}
        return outcome
    }

    /// Before the account number is revealed or copied: asks whenever a
    /// passcode exists (`AppLockPolicy.revealNeedsOwner`), lock on or off.
    /// True when it may be shown.
    static func confirmReveal(reason: String) async -> Bool {
        guard AppLockPolicy.revealNeedsOwner(availability()) else { return true }
        return await authenticate(reason: reason) == .success
    }
}
