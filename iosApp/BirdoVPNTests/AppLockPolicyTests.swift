import XCTest

/// MR-1094 — the iOS App Lock is opt-in but real when on (owner decision
/// 2026-10-28), and revealing the account number asks for the device owner.
///
/// Before this the gate was a cover only. It let the user in when nothing could
/// be asked for, so it was no lock even on paper. It also re-locked on every
/// `.inactive`, which made a Face ID prompt for a reveal re-lock the app
/// behind it, and nothing asked before the number was shown or copied. These
/// cases pin the decisions. The LocalAuthentication calls (DeviceOwnerAuth)
/// and the views cannot be compiled into this un-hosted bundle.
final class AppLockPolicyTests: XCTestCase {

    private typealias Policy = AppLockPolicy

    func testTheLockTurnsOnOnlyWithADevicePasscode() {
        XCTAssertTrue(Policy.canTurnOn(.available))
        XCTAssertFalse(Policy.canTurnOn(.passcodeNotSet))
        XCTAssertFalse(Policy.canTurnOn(.unavailable(reason: "Face ID is not available.")))
    }

    func testARemovedPasscodeSwitchesTheLockOffWithANotice() {
        let result = Policy.reconcile(lockOn: true, .passcodeNotSet)
        XCTAssertFalse(result.lockOn)
        XCTAssertTrue(result.notify)
    }

    /// No bypass: only a missing passcode switches the lock off. Anything else
    /// LocalAuthentication refuses keeps it on, and the app stays covered.
    func testAnyOtherRefusalKeepsTheLockOnAndTheAppCovered() {
        let result = Policy.reconcile(lockOn: true, .unavailable(reason: "Biometry is locked out."))
        XCTAssertTrue(result.lockOn)
        XCTAssertFalse(result.notify)
        XCTAssertTrue(Policy.covered(lockOn: true, locked: true, phase: .active))
        XCTAssertEqual(Policy.reconcile(lockOn: true, .available).lockOn, true)
    }

    func testALockThatIsOffIsNeverSwitchedOrNoticed() {
        let result = Policy.reconcile(lockOn: false, .passcodeNotSet)
        XCTAssertFalse(result.lockOn)
        XCTAssertFalse(result.notify, "a notice for a lock that was never on")
    }

    func testOnlyGoingToTheBackgroundReLocks() {
        XCTAssertTrue(Policy.locked(after: .background, wasLocked: false, lockOn: true))
        // Control Center, the app switcher, a StoreKit sheet and the Face ID
        // prompt itself only make the scene inactive: they cover, not re-lock.
        XCTAssertFalse(Policy.locked(after: .inactive, wasLocked: false, lockOn: true))
        XCTAssertFalse(Policy.locked(after: .active, wasLocked: false, lockOn: true))
        // Coming back does not unlock by itself.
        XCTAssertTrue(Policy.locked(after: .active, wasLocked: true, lockOn: true))
        XCTAssertTrue(Policy.locked(after: .inactive, wasLocked: true, lockOn: true))
        // Off: nothing is ever locked.
        XCTAssertFalse(Policy.locked(after: .background, wasLocked: true, lockOn: false))
    }

    func testNothingShowsWhileLockedOrOutOfTheForeground() {
        XCTAssertTrue(Policy.covered(lockOn: true, locked: true, phase: .active))
        XCTAssertTrue(Policy.covered(lockOn: true, locked: true, phase: .background))
        // Unlocked but leaving the foreground: the app-switcher snapshot is the cover.
        XCTAssertTrue(Policy.covered(lockOn: true, locked: false, phase: .inactive))
        XCTAssertTrue(Policy.covered(lockOn: true, locked: false, phase: .background))
        XCTAssertFalse(Policy.covered(lockOn: true, locked: false, phase: .active))
        // Off: no cover (the account number keeps its own snapshot cover, MR-1727).
        XCTAssertFalse(Policy.covered(lockOn: false, locked: true, phase: .inactive))
    }

    /// Revealing or copying the number asks whenever a passcode exists, with
    /// the lock on or off. With no passcode there is nothing to ask.
    func testRevealingTheAccountNumberAsksWheneverAPasscodeExists() {
        XCTAssertTrue(Policy.revealNeedsOwner(.available))
        XCTAssertTrue(Policy.revealNeedsOwner(.unavailable(reason: "Biometry is locked out.")))
        XCTAssertFalse(Policy.revealNeedsOwner(.passcodeNotSet))
    }
}
