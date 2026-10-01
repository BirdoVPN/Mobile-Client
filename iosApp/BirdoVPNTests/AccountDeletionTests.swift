import XCTest

/// Owner items 43 / 85 / 97 — pins the pure decisions behind account deletion
/// (`AccountDeletion.swift`).
final class AccountDeletionTests: XCTestCase {

    // MARK: - Item 43: which failures leave the outcome UNKNOWN

    /// While connected, the erasure revokes this device's peer before the
    /// server answers, so a successful deletion usually arrives as a TIMEOUT.
    /// That must be checked, never reported as "deletion failed".
    func testLostAnswerIsUnknownNotFailure() {
        XCTAssertTrue(AccountDeletion.outcomeIsUnknown(URLError(.timedOut)))
        XCTAssertTrue(AccountDeletion.outcomeIsUnknown(URLError(.networkConnectionLost)))
        XCTAssertTrue(AccountDeletion.outcomeIsUnknown(URLError(.badServerResponse)))
    }

    /// The request provably never left: a definitive failure, nothing to check.
    func testRequestThatNeverLeftIsADefinitiveFailure() {
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.notConnectedToInternet)))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.cannotFindHost)))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.dnsLookupFailed)))
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.secureConnectionFailed)))
        // The pinning delegate cancels the challenge on a pin mismatch.
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(URLError(.cancelled)))
    }

    /// A server that ANSWERED is never unknown, whatever it said.
    func testAnsweredRefusalsAreNotUnknown() {
        struct NotAURLError: Error {}
        XCTAssertFalse(AccountDeletion.outcomeIsUnknown(NotAURLError()))
    }
}
